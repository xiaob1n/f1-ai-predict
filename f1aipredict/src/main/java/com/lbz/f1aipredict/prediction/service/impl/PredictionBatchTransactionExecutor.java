package com.lbz.f1aipredict.prediction.service.impl;

import com.lbz.f1aipredict.prediction.PredictionBatchStatus;
import com.lbz.f1aipredict.prediction.PredictionJobStatus;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDto;
import com.lbz.f1aipredict.prediction.entity.PredictionBatch;
import com.lbz.f1aipredict.prediction.entity.PredictionJob;
import com.lbz.f1aipredict.prediction.mapper.PredictionBatchMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionJobMapper;
import com.lbz.f1aipredict.prediction.outbox.PredictionRequestOutbox;
import com.lbz.f1aipredict.prediction.outbox.PredictionRequestOutboxMapper;
import com.lbz.f1aipredict.question.service.PredictionQuestionView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 预测批次与任务的短事务执行组件。
 * <p>
 * 只承担已校验数据的原子写入；每次调用开启独立事务，使 batchNo 冲突重试不会复用已回滚事务。
 */
@Component
public class PredictionBatchTransactionExecutor {

    private static final int DEFAULT_MAX_RETRIES = 3;

    private final PredictionBatchMapper batchMapper;
    private final PredictionJobMapper jobMapper;
    private final PredictionRequestOutboxMapper outboxMapper;
    private final PredictionRequestSerializer requestSerializer;

    public PredictionBatchTransactionExecutor(PredictionBatchMapper batchMapper,
                                              PredictionJobMapper jobMapper,
                                              PredictionRequestOutboxMapper outboxMapper,
                                              PredictionRequestSerializer requestSerializer) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper must not be null");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper must not be null");
        this.outboxMapper = Objects.requireNonNull(outboxMapper, "outboxMapper must not be null");
        this.requestSerializer = Objects.requireNonNull(requestSerializer, "requestSerializer must not be null");
    }

    /**
     * 在独立短事务中写入一个批次及其全部任务。
     *
     * @param context 已完成事务外校验的冻结上下文
     * @return 创建结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PredictionBatchDto create(PredictionBatchCreateContext context) {
        Objects.requireNonNull(context, "context must not be null");
        int questionCount = context.questions().size();
        Integer maxBatchNo = batchMapper.selectMaxBatchNo(context.roundId());
        // 避免 int 加一溢出为负数；该状态不是唯一键冲突，外层只会安全失败而不会重试。
        if (Integer.valueOf(Integer.MAX_VALUE).equals(maxBatchNo)) {
            throw new IllegalStateException("Prediction batch number limit reached");
        }
        int nextBatchNo = maxBatchNo == null ? 1 : maxBatchNo + 1;

        PredictionBatch batch = new PredictionBatch();
        batch.setRoundId(context.roundId());
        batch.setBatchNo(nextBatchNo);
        batch.setStatus(PredictionBatchStatus.PENDING.name());
        batch.setDataCutoff(context.dataCutoff());
        batch.setQuestionCount(questionCount);
        requireSingleRow(batchMapper.insert(batch), "Prediction batch insert failed");
        if (batch.getId() == null) {
            throw new IllegalStateException("Prediction batch ID was not generated");
        }

        List<String> jobIds = new ArrayList<>(questionCount);
        for (PredictionQuestionView question : context.questions()) {
            PredictionJob job = newJob(batch.getId(), question, context);
            requireSingleRow(jobMapper.insert(job), "Prediction job insert failed");
            PredictionRequestOutbox outbox = new PredictionRequestOutbox();
            outbox.setPredictionJobId(job.getPredictionJobId());
            outbox.setMessageId(job.getMessageId());
            outbox.setPayloadJson(requestSerializer.serialize(job, question, context));
            outbox.setStatus("PENDING");
            outbox.setAttempts(0);
            Instant now = Instant.now();
            outbox.setNextAttemptAt(now);
            outbox.setCreatedAt(now);
            outbox.setUpdatedAt(now);
            requireSingleRow(outboxMapper.insert(outbox), "Prediction outbox insert failed");
            jobIds.add(job.getPredictionJobId());
        }

        return PredictionBatchDto.builder()
                .batchId(batch.getId())
                .roundId(context.roundId())
                .status(PredictionBatchStatus.PENDING.name())
                .questionCount(jobIds.size())
                .jobIds(jobIds)
                .build();
    }

    /**
     * 从冻结上下文构造单个 PENDING 任务，并固定首次发布的消息 ID。
     */
    private static PredictionJob newJob(Long batchId,
                                        PredictionQuestionView question,
                                        PredictionBatchCreateContext context) {
        PredictionJob job = new PredictionJob();
        job.setPredictionJobId(UUID.randomUUID().toString());
        job.setMessageId(UUID.randomUUID().toString());
        job.setBatchId(batchId);
        job.setQuestionId(question.getQuestionId());
        job.setQuestionSnapshotId(question.getSnapshotId());
        job.setDataCutoff(context.dataCutoff());
        job.setFeatureVersion(context.featureVersion());
        job.setModelVersion(context.modelVersion());
        job.setPromptVersion(context.promptVersion());
        job.setStatus(PredictionJobStatus.PENDING.name());
        job.setRetryCount(0);
        job.setMaxRetries(DEFAULT_MAX_RETRIES);
        return job;
    }

    /**
     * 任一 insert 未精确影响一行都视为写入失败，抛运行时异常让 Spring 回滚整次事务。
     */
    private static void requireSingleRow(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }
}

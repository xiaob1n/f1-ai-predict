package com.lbz.f1aipredict.prediction.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.prediction.PredictionJobStatus;
import com.lbz.f1aipredict.prediction.dto.PredictionJobDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobPageDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobQuery;
import com.lbz.f1aipredict.prediction.entity.PredictionJob;
import com.lbz.f1aipredict.prediction.mapper.PredictionBatchMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionJobMapper;
import com.lbz.f1aipredict.prediction.service.PredictionJobService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 预测任务只读查询服务实现。
 * <p>
 * 列表查询在服务边界再次裁剪分页参数，单任务查询只使用公开业务键，并显式映射公开字段。
 */
@Service
public class PredictionJobServiceImpl implements PredictionJobService {

    /** 业务键最大长度与 SQL 004 的 prediction_job_id 列宽保持一致。 */
    private static final int BUSINESS_ID_MAX_LENGTH = 64;

    private final PredictionBatchMapper batchMapper;
    private final PredictionJobMapper jobMapper;

    /**
     * 使用构造器注入批次和任务 Mapper，便于在无数据库的服务单测中验证查询边界。
     */
    public PredictionJobServiceImpl(PredictionBatchMapper batchMapper, PredictionJobMapper jobMapper) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper must not be null");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper must not be null");
    }

    /**
     * 先做状态白名单和分页裁剪，再确认父批次存在；空任务仍返回准确的空分页。
     */
    @Override
    public PredictionJobPageDto listByBatchId(Long batchId, PredictionJobQuery query) {
        PredictionJobQuery effectiveQuery = query == null ? new PredictionJobQuery() : query;
        effectiveQuery.clampPaging();
        String status = normalizeStatus(effectiveQuery.getStatus());
        if (batchId == null || batchMapper.selectById(batchId) == null) {
            throw new ResourceNotFoundException("Prediction batch not found: " + batchId);
        }

        List<PredictionJob> records = jobMapper.selectPageByBatchId(
                batchId, status, effectiveQuery.getPage(), effectiveQuery.getSize());
        Long total = jobMapper.selectCount(countWrapper(batchId, status));
        List<PredictionJobDto> items = new ArrayList<>();
        if (records != null) {
            for (PredictionJob record : records) {
                if (record != null) {
                    items.add(toDto(record));
                }
            }
        }
        return PredictionJobPageDto.builder()
                .items(items)
                .page(effectiveQuery.getPage())
                .size(effectiveQuery.getSize())
                .total(total == null ? 0L : total)
                .build();
    }

    /**
     * 业务键严格按 null、trim、空值与规范化长度的顺序校验，合法后只调用业务键 Mapper 查询。
     */
    @Override
    public PredictionJobDto getByBusinessId(String predictionJobId) {
        if (predictionJobId == null) {
            throw new InvalidRequestException("predictionJobId is invalid");
        }
        String normalizedId = predictionJobId.trim();
        if (normalizedId.isEmpty() || normalizedId.length() > BUSINESS_ID_MAX_LENGTH) {
            throw new InvalidRequestException("predictionJobId is invalid");
        }
        PredictionJob job = jobMapper.selectByPredictionJobId(normalizedId);
        if (job == null) {
            throw new ResourceNotFoundException("Prediction job not found: " + normalizedId);
        }
        return toDto(job);
    }

    /**
     * 状态过滤只允许 SQL 004 声明的枚举值；null/空白统一表示不过滤。
     */
    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalizedStatus = status.trim();
        try {
            return PredictionJobStatus.valueOf(normalizedStatus).name();
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException("Unsupported prediction job status");
        }
    }

    /**
     * 复用 BaseMapper 计数能力，并保持 total 与分页列表使用同一批次和状态过滤条件。
     */
    private static QueryWrapper<PredictionJob> countWrapper(Long batchId, String status) {
        QueryWrapper<PredictionJob> wrapper = new QueryWrapper<>();
        wrapper.eq("batch_id", batchId);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return wrapper;
    }

    /**
     * 只复制公开字段，明确不映射内部自增 id、messageId 和 lastError。
     */
    private static PredictionJobDto toDto(PredictionJob job) {
        return PredictionJobDto.builder()
                .predictionJobId(job.getPredictionJobId())
                .batchId(job.getBatchId())
                .questionId(job.getQuestionId())
                .questionSnapshotId(job.getQuestionSnapshotId())
                .status(job.getStatus())
                .retryCount(job.getRetryCount())
                .maxRetries(job.getMaxRetries())
                .workerNode(job.getWorkerNode())
                .dataCutoff(job.getDataCutoff())
                .featureVersion(job.getFeatureVersion())
                .modelVersion(job.getModelVersion())
                .promptVersion(job.getPromptVersion())
                .lockedAt(job.getLockedAt())
                .completedAt(job.getCompletedAt())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}

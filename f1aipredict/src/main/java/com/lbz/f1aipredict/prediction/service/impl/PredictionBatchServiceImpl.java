package com.lbz.f1aipredict.prediction.service.impl;

import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.prediction.dto.CreatePredictionBatchRequest;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDetailDto;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDto;
import com.lbz.f1aipredict.prediction.dto.PredictionJobStatusCountsDto;
import com.lbz.f1aipredict.prediction.entity.PredictionBatch;
import com.lbz.f1aipredict.prediction.mapper.PredictionBatchMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionJobMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionJobStatusCounts;
import com.lbz.f1aipredict.prediction.service.PredictionBatchService;
import com.lbz.f1aipredict.question.service.PredictionQuestionReadService;
import com.lbz.f1aipredict.question.service.PredictionQuestionView;
import com.lbz.f1aipredict.season.dto.RoundDto;
import com.lbz.f1aipredict.season.service.RoundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 预测批次创建编排实现。
 * <p>
 * 分站与题目读取、全部业务校验均在事务外完成，随后仅把冻结上下文交给短事务组件写入。
 */
@Service
@Slf4j
public class PredictionBatchServiceImpl implements PredictionBatchService {

    private static final String STATUS_SCHEDULED = "SCHEDULED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String BATCH_NUMBER_UNIQUE_KEY = "uk_batch_round_no";
    private static final int MAX_CREATE_ATTEMPTS = 3;

    private final RoundService roundService;
    private final PredictionQuestionReadService questionReadService;
    private final PredictionBatchTransactionExecutor transactionExecutor;
    private final PredictionBatchMapper batchMapper;
    private final PredictionJobMapper jobMapper;

    public PredictionBatchServiceImpl(RoundService roundService,
                                      PredictionQuestionReadService questionReadService,
                                      PredictionBatchTransactionExecutor transactionExecutor,
                                      PredictionBatchMapper batchMapper,
                                      PredictionJobMapper jobMapper) {
        this.roundService = Objects.requireNonNull(roundService, "roundService must not be null");
        this.questionReadService = Objects.requireNonNull(
                questionReadService, "questionReadService must not be null");
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor, "transactionExecutor must not be null");
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper must not be null");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper must not be null");
    }

    /**
     * 完成事务外读取与校验后，委托短事务创建批次和任务。
     */
    @Override
    public PredictionBatchDto create(Long roundId, CreatePredictionBatchRequest request) {
        validateRequest(roundId, request);
        RoundDto round = roundService.getById(roundId);
        if (round == null) {
            throw new InvalidRequestException("Round not found");
        }
        validateRoundStatus(round);
        List<PredictionQuestionView> questions = questionReadService.loadForPrediction(
                roundId, Boolean.TRUE.equals(request.getAllOpenQuestions()) ? null : request.getQuestionIds());
        validateQuestions(questions);
        PredictionBatchCreateContext context = new PredictionBatchCreateContext(
                roundId,
                request.getDataCutoff(),
                request.getFeatureVersion(),
                request.getModelVersion(),
                request.getPromptVersion(),
                questions);
        return createWithBatchNumberRetry(context);
    }

    /**
     * 读取批次后只执行一次状态聚合，避免按任务逐条查询并保持批次题目数真值。
     */
    @Override
    public PredictionBatchDetailDto getById(Long batchId) {
        PredictionBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("Prediction batch not found: " + batchId);
        }
        PredictionJobStatusCounts counts = jobMapper.selectStatusCountsByBatchId(batchId);
        long unknownCount = count(counts == null ? null : counts.getUnknownCount());
        if (unknownCount > 0) {
            // 告警只携带批次主键和数量，不输出未知状态原值或其他数据库内容。
            log.warn("预测批次包含未知任务状态: batchId={}, unknownCount={}", batchId, unknownCount);
        }
        long jobCount = countJobStatuses(counts);
        if (batch.getQuestionCount() != null && batch.getQuestionCount() != jobCount) {
            // 批次表的题目数是真值；聚合总数仅用于发现数据一致性问题并安全告警。
            log.warn("预测批次题目数与任务状态总数不一致: batchId={}, questionCount={}, jobCount={}",
                    batchId, batch.getQuestionCount(), jobCount);
        }
        return PredictionBatchDetailDto.builder()
                .batchId(batch.getId())
                .roundId(batch.getRoundId())
                .batchNo(batch.getBatchNo())
                .status(batch.getStatus())
                .questionCount(batch.getQuestionCount())
                .dataCutoff(batch.getDataCutoff())
                .createdAt(batch.getCreatedAt())
                .updatedAt(batch.getUpdatedAt())
                .statusCounts(toStatusCountsDto(counts))
                .build();
    }

    /**
     * 计算状态投影中的互斥任务总数，避免对外失败合计重复计算 DEAD_LETTER。
     */
    private static long countJobStatuses(PredictionJobStatusCounts counts) {
        if (counts == null) {
            return 0L;
        }
        return count(counts.getPendingCount())
                + count(counts.getRunningCount())
                + count(counts.getRetryingCount())
                + count(counts.getSucceededCount())
                + count(counts.getFailedCount())
                + count(counts.getDeadLetterCount())
                + count(counts.getUnknownCount());
    }

    /**
     * 将聚合投影的 Long 安全归零并转换为对外 Integer，失败数按 FAILED 加 DEAD_LETTER 计算。
     */
    private static PredictionJobStatusCountsDto toStatusCountsDto(PredictionJobStatusCounts counts) {
        long failed = count(counts == null ? null : counts.getFailedCount());
        long deadLetter = count(counts == null ? null : counts.getDeadLetterCount());
        return PredictionJobStatusCountsDto.builder()
                .pendingCount(toInteger(count(counts == null ? null : counts.getPendingCount())))
                .runningCount(toInteger(count(counts == null ? null : counts.getRunningCount())))
                .retryingCount(toInteger(count(counts == null ? null : counts.getRetryingCount())))
                .succeededCount(toInteger(count(counts == null ? null : counts.getSucceededCount())))
                .failedCount(toInteger(failed + deadLetter))
                .deadLetterCount(toInteger(deadLetter))
                .unknownCount(toInteger(count(counts == null ? null : counts.getUnknownCount())))
                .build();
    }

    /** 空批次的 SUM 结果可能为 null，统一按零处理。 */
    private static long count(Long value) {
        return value == null ? 0L : value;
    }

    private static int toInteger(long value) {
        return Math.toIntExact(value);
    }

    /**
     * batchNo 并发冲突最多尝试三次；每次委托 REQUIRES_NEW 组件重新读取最大序号并开启新事务。
     */
    private PredictionBatchDto createWithBatchNumberRetry(PredictionBatchCreateContext context) {
        for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
            try {
                return transactionExecutor.create(context);
            } catch (DataAccessException ex) {
                if (!isBatchNumberConflict(ex)) {
                    log.error("预测批次数据库写入失败: roundId={}, attempt={}, errorType={}",
                            context.roundId(), attempt, ex.getClass().getSimpleName());
                    throw new InvalidRequestException("Prediction batch creation failed", ex);
                }
                log.warn("预测批次序号冲突: roundId={}, attempt={}", context.roundId(), attempt);
                if (attempt == MAX_CREATE_ATTEMPTS) {
                    throw new InvalidRequestException("Prediction batch number conflict", ex);
                }
            } catch (RuntimeException ex) {
                log.error("预测批次事务执行失败: roundId={}, attempt={}, errorType={}",
                        context.roundId(), attempt, ex.getClass().getSimpleName());
                throw new InvalidRequestException("Prediction batch creation failed", ex);
            }
        }
        throw new IllegalStateException("Prediction batch retry loop exhausted unexpectedly");
    }

    /**
     * 仅当异常 cause 链明确包含 uk_batch_round_no 时才允许重试，其他完整性错误直接安全失败。
     */
    private static boolean isBatchNumberConflict(Throwable throwable) {
        Throwable current = throwable;
        boolean duplicateKeyFailure = false;
        boolean matchingConstraint = false;
        while (current != null) {
            duplicateKeyFailure = duplicateKeyFailure
                    || current instanceof DuplicateKeyException
                    || isMysqlDuplicateKey(current);
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT)
                    .contains(BATCH_NUMBER_UNIQUE_KEY)) {
                matchingConstraint = true;
            }
            current = current.getCause();
        }
        return duplicateKeyFailure && matchingConstraint;
    }

    private static boolean isMysqlDuplicateKey(Throwable throwable) {
        if (!(throwable instanceof SQLIntegrityConstraintViolationException sqlException)) {
            return false;
        }
        return sqlException.getErrorCode() == 1062;
    }

    /**
     * Service 入口不依赖 HTTP Bean Validation，先防御所有必填项与题目 ID 边界。
     */
    private static void validateRequest(Long roundId, CreatePredictionBatchRequest request) {
        if (roundId == null || roundId <= 0) {
            throw new InvalidRequestException("roundId must be positive");
        }
        if (request == null) {
            throw new InvalidRequestException("request is required");
        }
        if (request.getDataCutoff() == null
                || isBlank(request.getFeatureVersion())
                || isBlank(request.getModelVersion())
                || isBlank(request.getPromptVersion())) {
            throw new InvalidRequestException("Prediction versions and dataCutoff are required");
        }
        // Service 可被非 HTTP 调用，版本长度必须在任何只读服务和写入前再次守住数据库列宽。
        if (request.getFeatureVersion().length() > CreatePredictionBatchRequest.FEATURE_VERSION_MAX_LENGTH
                || request.getModelVersion().length() > CreatePredictionBatchRequest.MODEL_VERSION_MAX_LENGTH
                || request.getPromptVersion().length() > CreatePredictionBatchRequest.PROMPT_VERSION_MAX_LENGTH) {
            throw new InvalidRequestException("Prediction version exceeds maximum length");
        }

        boolean allOpen = Boolean.TRUE.equals(request.getAllOpenQuestions());
        List<Long> questionIds = request.getQuestionIds();
        if (allOpen) {
            if (questionIds != null) {
                throw new InvalidRequestException("questionIds conflicts with allOpenQuestions");
            }
            return;
        }
        if (questionIds == null || questionIds.isEmpty()) {
            throw new InvalidRequestException("questionIds must not be empty");
        }
        if (questionIds.size() > CreatePredictionBatchRequest.MAX_QUESTION_IDS) {
            throw new InvalidRequestException("questionIds exceeds maximum size");
        }
        Set<Long> uniqueIds = new HashSet<>(questionIds.size());
        for (Long questionId : questionIds) {
            if (questionId == null || questionId <= 0) {
                throw new InvalidRequestException("questionIds must contain positive IDs");
            }
            if (!uniqueIds.add(questionId)) {
                throw new InvalidRequestException("questionIds must not contain duplicates");
            }
        }
    }

    /**
     * 题目读取契约已校验归属和选项，这里仍防御空结果、空视图及未冻结快照。
     */
    private static void validateQuestions(List<PredictionQuestionView> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new InvalidRequestException("No predictable questions");
        }
        for (PredictionQuestionView question : questions) {
            if (question == null || question.getQuestionId() == null || question.getQuestionId() <= 0) {
                throw new InvalidRequestException("Invalid prediction question");
            }
            if (question.getSnapshotId() == null) {
                throw new InvalidRequestException("Prediction question snapshotId is required");
            }
        }
    }

    /**
     * 只有未结束或进行中的分站允许创建预测，未知状态按拒绝处理。
     */
    private static void validateRoundStatus(RoundDto round) {
        String status = round.getStatus();
        if (!STATUS_SCHEDULED.equals(status) && !STATUS_IN_PROGRESS.equals(status)) {
            throw new InvalidRequestException("Unsupported round status");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

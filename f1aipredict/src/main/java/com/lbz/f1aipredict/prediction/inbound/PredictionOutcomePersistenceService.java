package com.lbz.f1aipredict.prediction.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.prediction.PredictionBatchStatus;
import com.lbz.f1aipredict.prediction.PredictionJobStatus;
import com.lbz.f1aipredict.prediction.entity.PredictionBatch;
import com.lbz.f1aipredict.prediction.entity.PredictionEvidence;
import com.lbz.f1aipredict.prediction.entity.PredictionFailure;
import com.lbz.f1aipredict.prediction.entity.PredictionJob;
import com.lbz.f1aipredict.prediction.entity.PredictionOutcomeQuarantine;
import com.lbz.f1aipredict.prediction.entity.PredictionOutcomeReceipt;
import com.lbz.f1aipredict.prediction.entity.PredictionResult;
import com.lbz.f1aipredict.prediction.entity.PredictionResultItem;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Evidence;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Failure;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Result;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.SelectedOption;
import com.lbz.f1aipredict.prediction.mapper.PredictionBatchMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionEvidenceMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionFailureMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionJobMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionOutcomeQuarantineMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionOutcomeReceiptMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionResultItemMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionResultMapper;
import com.lbz.f1aipredict.prediction.outbox.PredictionRequestOutbox;
import com.lbz.f1aipredict.prediction.outbox.PredictionRequestOutboxMapper;
import com.lbz.f1aipredict.question.entity.QuestionOption;
import com.lbz.f1aipredict.question.mapper.QuestionOptionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在单个短事务内验证冻结任务并提交终态、接收凭证及批次聚合。 */
@Service
@Slf4j
public class PredictionOutcomePersistenceService {

    private static final Set<String> NON_TERMINAL = Set.of("PENDING", "RUNNING", "RETRYING");

    private final PredictionJobMapper jobMapper;
    private final PredictionBatchMapper batchMapper;
    private final PredictionRequestOutboxMapper outboxMapper;
    private final PredictionOutcomeReceiptMapper receiptMapper;
    private final PredictionOutcomeQuarantineMapper quarantineMapper;
    private final PredictionResultMapper resultMapper;
    private final PredictionResultItemMapper resultItemMapper;
    private final PredictionEvidenceMapper evidenceMapper;
    private final PredictionFailureMapper failureMapper;
    private final QuestionOptionMapper optionMapper;
    private final ObjectMapper objectMapper;

    public PredictionOutcomePersistenceService(PredictionJobMapper jobMapper,
                                              PredictionBatchMapper batchMapper,
                                              PredictionRequestOutboxMapper outboxMapper,
                                              PredictionOutcomeReceiptMapper receiptMapper,
                                              PredictionOutcomeQuarantineMapper quarantineMapper,
                                              PredictionResultMapper resultMapper,
                                              PredictionResultItemMapper resultItemMapper,
                                              PredictionEvidenceMapper evidenceMapper,
                                              PredictionFailureMapper failureMapper,
                                              QuestionOptionMapper optionMapper,
                                              ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.batchMapper = batchMapper;
        this.outboxMapper = outboxMapper;
        this.receiptMapper = receiptMapper;
        this.quarantineMapper = quarantineMapper;
        this.resultMapper = resultMapper;
        this.resultItemMapper = resultItemMapper;
        this.evidenceMapper = evidenceMapper;
        this.failureMapper = failureMapper;
        this.optionMapper = optionMapper;
        this.objectMapper = objectMapper;
    }

    /** 幂等写入 RESULT；运行于 READ COMMITTED，避免持有批次锁后读取旧快照聚合。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OutcomeApplyResult applyResult(Result result, String payloadSha256) {
        PredictionJob job = lockAndValidateJob(result.getPredictionJobId(), result.getBatchId(), result.getQuestionId(),
                result.getQuestionSnapshotId(), result.getTraceId(), result.getSourceDataCutoff(), result.getModelVersion(),
                result.getPromptVersion(), result.getFeatureVersion(), result.getEmbeddingVersion(),
                result.getRetrieverVersion());
        OutcomeApplyResult duplicate = checkExistingReceipt(job, result.getMessageId(), payloadSha256, "RESULT");
        if (duplicate != null) return duplicate;
        validateEvidence(result);
        Map<Integer, QuestionOption> selectedOptions = resolveSelectedOptions(result);
        validateChoiceLimit(result, retrieveFrozenPayload(job.getPredictionJobId()));

        Instant now = Instant.now();
        PredictionResult persistedResult = new PredictionResult();
        persistedResult.setJobId(job.getId());
        persistedResult.setQuestionId(job.getQuestionId());
        persistedResult.setQuestionSnapshotId(job.getQuestionSnapshotId());
        persistedResult.setConfidence(result.getConfidence());
        persistedResult.setReasoningSummary(result.getReasoningSummary());
        persistedResult.setSourceDataCutoff(result.getSourceDataCutoff());
        persistedResult.setModel(null);
        persistedResult.setModelVersion(result.getModelVersion());
        persistedResult.setPromptVersion(result.getPromptVersion());
        persistedResult.setFeatureVersion(result.getFeatureVersion());
        persistedResult.setEmbeddingVersion(result.getEmbeddingVersion());
        persistedResult.setRetrieverVersion(result.getRetrieverVersion());
        persistedResult.setRawAgentResponse(null);
        persistedResult.setGeneratedAt(result.getGeneratedAt());
        persistedResult.setCreatedAt(now);
        persistedResult.setUpdatedAt(now);
        requireInserted(resultMapper.insert(persistedResult), "RESULT_INSERT_FAILED");
        for (SelectedOption selected : result.getSelectedOptions()) {
            if (!selectedOptions.containsKey(selected.getOptionId())) {
                throw rejected("OPTION_NOT_IN_FROZEN_SNAPSHOT");
            }
            PredictionResultItem item = new PredictionResultItem();
            item.setResultId(persistedResult.getId());
            item.setOptionId(selected.getOptionId());
            item.setPosition(selected.getPosition());
            item.setCreatedAt(now);
            requireInserted(resultItemMapper.insert(item), "RESULT_ITEM_INSERT_FAILED");
        }
        for (Evidence source : result.getEvidence()) {
            PredictionEvidence evidence = new PredictionEvidence();
            evidence.setResultId(persistedResult.getId());
            evidence.setSourceType(source.getSourceType());
            evidence.setSourceName(source.getSourceName());
            evidence.setSourceUrl(source.getSourceUrl());
            evidence.setFirstSeenAt(source.getFirstSeenAt());
            evidence.setEventTime(source.getEventTime());
            evidence.setDocumentId(source.getDocumentId());
            evidence.setChunkId(source.getChunkId());
            evidence.setPublishedAt(null);
            evidence.setCreatedAt(now);
            requireInserted(evidenceMapper.insert(evidence), "EVIDENCE_INSERT_FAILED");
        }
        persistReceipt(job, result.getMessageId(), "RESULT", payloadSha256, result.getTraceId(), now);
        moveToTerminal(job, PredictionJobStatus.SUCCEEDED, result.getGeneratedAt());
        aggregateBatch(job.getBatchId());
        return OutcomeApplyResult.APPLIED;
    }

    /** 幂等写入 FAILURE；Python attempt 只进入失败详情，不修改 Java retryCount。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OutcomeApplyResult applyFailure(Failure failure, String payloadSha256) {
        PredictionJob job = lockAndValidateJob(failure.getPredictionJobId(), failure.getBatchId(),
                failure.getQuestionId(), failure.getQuestionSnapshotId(), failure.getTraceId(), failure.getSourceDataCutoff(),
                failure.getModelVersion(), failure.getPromptVersion(), failure.getFeatureVersion(),
                failure.getEmbeddingVersion(), failure.getRetrieverVersion());
        OutcomeApplyResult duplicate = checkExistingReceipt(job, failure.getMessageId(), payloadSha256, "FAILURE");
        if (duplicate != null) return duplicate;
        Instant now = Instant.now();
        PredictionFailure persistedFailure = new PredictionFailure();
        persistedFailure.setJobId(job.getId());
        persistedFailure.setMessageId(failure.getMessageId());
        persistedFailure.setTraceId(failure.getTraceId());
        persistedFailure.setFailureCode(failure.getFailureCode());
        persistedFailure.setSummary(failure.getSummary());
        persistedFailure.setAttempt(failure.getAttempt());
        persistedFailure.setGeneratedAt(failure.getGeneratedAt());
        persistedFailure.setSourceDataCutoff(failure.getSourceDataCutoff());
        persistedFailure.setModelVersion(failure.getModelVersion());
        persistedFailure.setPromptVersion(failure.getPromptVersion());
        persistedFailure.setFeatureVersion(failure.getFeatureVersion());
        persistedFailure.setEmbeddingVersion(failure.getEmbeddingVersion());
        persistedFailure.setRetrieverVersion(failure.getRetrieverVersion());
        persistedFailure.setSchemaVersion(failure.getSchemaVersion());
        persistedFailure.setCreatedAt(now);
        requireInserted(failureMapper.insert(persistedFailure), "FAILURE_INSERT_FAILED");
        persistReceipt(job, failure.getMessageId(), "FAILURE", payloadSha256, failure.getTraceId(), now);
        moveToTerminal(job, PredictionJobStatus.FAILED, failure.getGeneratedAt());
        aggregateBatch(job.getBatchId());
        return OutcomeApplyResult.APPLIED;
    }

    /** 永久坏消息先存有界原文和摘要，调用方必须在本事务提交后才 ACK。 */
    @Transactional
    public void quarantine(byte[] body, String messageId, String reasonCode) {
        if (body == null || body.length > 1_048_576) {
            throw new OutcomeQuarantineUnavailableException("QUARANTINE_BODY_LIMIT");
        }
        PredictionOutcomeQuarantine quarantine = new PredictionOutcomeQuarantine();
        quarantine.setMessageId(safeMessageId(messageId));
        quarantine.setBodySha256(OutcomeMessageParser.sha256(body));
        quarantine.setReasonCode(reasonCode);
        quarantine.setRawBody(body.clone());
        quarantine.setReceivedAt(Instant.now());
        quarantine.setReplayStatus("PENDING");
        quarantine.setReplayedAt(null);
        try {
            if (quarantineMapper.insert(quarantine) != 1) {
                throw new OutcomeQuarantineUnavailableException("QUARANTINE_INSERT_FAILED");
            }
            log.warn("预测终态消息已持久隔离: messageId={}, reasonCode={}", quarantine.getMessageId(), reasonCode);
        } catch (DuplicateKeyException duplicate) {
            // 原文摘要与原因码唯一，说明同一毒消息的隔离凭证已提交，可安全确认重投。
            log.warn("预测终态消息重复隔离已存在: messageId={}, reasonCode={}", quarantine.getMessageId(), reasonCode);
        }
    }

    private PredictionJob lockAndValidateJob(String predictionJobId, Long batchId, Long questionId,
                                              Long snapshotId, String traceId, Instant cutoff, String modelVersion,
                                              String promptVersion, String featureVersion,
                                              String embeddingVersion, String retrieverVersion) {
        PredictionJob job = jobMapper.selectByPredictionJobIdForUpdate(predictionJobId);
        if (job == null) throw rejected("PREDICTION_JOB_NOT_FOUND");
        PredictionRequestOutbox outbox = outboxMapper.selectByPredictionJobId(predictionJobId);
        if (outbox == null || outbox.getPayloadJson() == null) throw rejected("FROZEN_REQUEST_MISSING");
        JsonNode frozen = parseFrozenPayload(outbox.getPayloadJson());
        if (!Objects.equals(job.getBatchId(), batchId) || !Objects.equals(job.getQuestionId(), questionId)
                || !Objects.equals(job.getQuestionSnapshotId(), snapshotId)
                || !Objects.equals(job.getBatchId(), frozen.path("batchId").isIntegralNumber()
                    ? frozen.path("batchId").longValue() : null)
                || !Objects.equals(job.getQuestionId(), frozen.path("questionId").isIntegralNumber()
                    ? frozen.path("questionId").longValue() : null)
                || !Objects.equals(job.getQuestionSnapshotId(), frozen.path("questionSnapshotId").isIntegralNumber()
                    ? frozen.path("questionSnapshotId").longValue() : null)
                || !Objects.equals(job.getMessageId(), outbox.getMessageId())
                || !Objects.equals(textOrNull(frozen.get("traceId")), traceId)
                || !isFrozenCutoffCompatible(job.getDataCutoff(), parseInstant(frozen.get("dataCutoff")), cutoff,
                    job.getMessageId(), outbox.getMessageId())
                || !Objects.equals(job.getModelVersion(), modelVersion)
                || !Objects.equals(job.getPromptVersion(), promptVersion)
                || !Objects.equals(job.getFeatureVersion(), featureVersion)
                || !Objects.equals(textOrNull(frozen.get("modelVersion")), modelVersion)
                || !Objects.equals(textOrNull(frozen.get("promptVersion")), promptVersion)
                || !Objects.equals(textOrNull(frozen.get("featureVersion")), featureVersion)
                || !Objects.equals(textOrNull(frozen.get("embeddingVersion")), embeddingVersion)
                || !Objects.equals(textOrNull(frozen.get("retrieverVersion")), retrieverVersion)) {
            throw rejected("FROZEN_CONTEXT_MISMATCH");
        }
        return job;
    }

    /** 精确以请求 outbox 的 ISO 时点为证据截止；仅兼容可证明的历史 DATETIME(3) 精度损失。 */
    static boolean isFrozenCutoffCompatible(Instant jobCutoff, Instant frozenCutoff, Instant outcomeCutoff,
                                                    String jobMessageId, String outboxMessageId) {
        if (jobCutoff == null || frozenCutoff == null || outcomeCutoff == null
                || !frozenCutoff.equals(outcomeCutoff)) {
            return false;
        }
        if (frozenCutoff.getNano() % 1_000_000 == 0) {
            return frozenCutoff.equals(jobCutoff);
        }
        if (jobMessageId == null || !jobMessageId.equals(outboxMessageId)) {
            return false;
        }
        java.time.Duration precisionLoss = java.time.Duration.between(jobCutoff, frozenCutoff).abs();
        return precisionLoss.compareTo(java.time.Duration.ofMillis(1)) < 0;
    }

    private OutcomeApplyResult checkExistingReceipt(PredictionJob job, String messageId,
                                                   String digest, String type) {
        PredictionOutcomeReceipt byMessage = receiptMapper.selectByMessageId(messageId);
        if (byMessage != null) {
            if (Objects.equals(byMessage.getJobId(), job.getId())
                    && Objects.equals(byMessage.getOutcomeType(), type)
                    && Objects.equals(byMessage.getPayloadSha256(), digest)) {
                return OutcomeApplyResult.DUPLICATE;
            }
            throw rejected("OUTCOME_MESSAGE_ID_CONFLICT");
        }
        PredictionOutcomeReceipt byJob = receiptMapper.selectByJobId(job.getId());
        if (byJob != null) {
            throw rejected("OUTCOME_TERMINAL_CONFLICT");
        }
        if (!NON_TERMINAL.contains(job.getStatus())) {
            throw rejected("JOB_ALREADY_TERMINAL");
        }
        return null;
    }

    private Map<Integer, QuestionOption> resolveSelectedOptions(Result result) {
        List<QuestionOption> options = optionMapper.selectBySnapshotIds(List.of(result.getQuestionSnapshotId()));
        if (options == null || options.isEmpty()) throw rejected("FROZEN_OPTIONS_MISSING");
        Map<Integer, List<QuestionOption>> matches = new HashMap<>();
        for (QuestionOption option : options) {
            if (Objects.equals(option.getSnapshotId(), result.getQuestionSnapshotId()) && option.getOptionId() != null) {
                matches.computeIfAbsent(option.getOptionId(), ignored -> new ArrayList<>()).add(option);
            }
        }
        Map<Integer, QuestionOption> resolved = new HashMap<>();
        for (SelectedOption selected : result.getSelectedOptions()) {
            List<QuestionOption> found = matches.get(selected.getOptionId());
            if (found == null || found.size() != 1 || resolved.put(selected.getOptionId(), found.getFirst()) != null) {
                throw rejected("FROZEN_OPTION_AMBIGUOUS_OR_MISSING");
            }
        }
        return resolved;
    }

    static void validateChoiceLimit(Result result, JsonNode frozenPayload) {
        JsonNode limitNode = frozenPayload.path("question").get("choiceLimit");
        if (limitNode == null || result.getSelectedOptions() == null || result.getSelectedOptions().isEmpty()) {
            throw rejected("CHOICE_LIMIT_UNVERIFIABLE_OR_EXCEEDED");
        }
        // 冻结请求允许 choiceLimit 为 null，表示题目没有指定最大选择数。
        if (limitNode.isNull()) return;
        if (!limitNode.isIntegralNumber() || !limitNode.canConvertToInt() || limitNode.intValue() < 1
                || result.getSelectedOptions().size() > limitNode.intValue()) {
            throw rejected("CHOICE_LIMIT_UNVERIFIABLE_OR_EXCEEDED");
        }
    }

    private static void validateEvidence(Result result) {
        for (Evidence source : result.getEvidence()) {
            if (source.getFirstSeenAt().isAfter(result.getSourceDataCutoff())
                    || (source.getEventTime() != null && source.getEventTime().isAfter(result.getSourceDataCutoff()))) {
                throw rejected("EVIDENCE_AFTER_CUTOFF");
            }
        }
    }

    private void persistReceipt(PredictionJob job, String messageId, String outcomeType,
                                String digest, String traceId, Instant now) {
        PredictionOutcomeReceipt receipt = new PredictionOutcomeReceipt();
        receipt.setJobId(job.getId());
        receipt.setMessageId(messageId);
        receipt.setOutcomeType(outcomeType);
        receipt.setPayloadSha256(digest);
        receipt.setTraceId(traceId);
        receipt.setReceivedAt(now);
        receipt.setCreatedAt(now);
        requireInserted(receiptMapper.insert(receipt), "OUTCOME_RECEIPT_INSERT_FAILED");
    }

    private void moveToTerminal(PredictionJob job, PredictionJobStatus status, Instant completedAt) {
        if (jobMapper.updateTerminalStatus(job.getId(), status.name(), completedAt) != 1) {
            throw rejected("JOB_TERMINAL_UPDATE_CONFLICT");
        }
    }

    private void aggregateBatch(Long batchId) {
        PredictionBatch batch = batchMapper.selectByIdForUpdate(batchId);
        if (batch == null) throw rejected("PREDICTION_BATCH_NOT_FOUND");
        List<PredictionJob> jobs = jobMapper.selectAllByBatchId(batchId);
        if (batch.getQuestionCount() == null || batch.getQuestionCount() != jobs.size()) {
            log.warn("预测批次题目数与任务数不一致: batchId={}, questionCount={}, jobCount={}",
                    batchId, batch.getQuestionCount(), jobs.size());
        }
        long terminal = jobs.stream().filter(job -> isTerminal(job.getStatus())).count();
        long succeeded = jobs.stream().filter(job -> PredictionJobStatus.SUCCEEDED.name().equals(job.getStatus())).count();
        long failed = jobs.stream().filter(job -> PredictionJobStatus.FAILED.name().equals(job.getStatus())
                || PredictionJobStatus.DEAD_LETTER.name().equals(job.getStatus())).count();
        String status;
        if (terminal == 0) status = PredictionBatchStatus.PENDING.name();
        else if (terminal < jobs.size()) status = PredictionBatchStatus.PARTIAL.name();
        else if (succeeded == jobs.size()) status = PredictionBatchStatus.COMPLETED.name();
        else if (failed > 0) status = PredictionBatchStatus.FAILED.name();
        else status = PredictionBatchStatus.PARTIAL.name();
        if (batchMapper.updateStatus(batchId, status) != 1) throw rejected("BATCH_AGGREGATION_UPDATE_FAILED");
    }

    private JsonNode retrieveFrozenPayload(String jobId) {
        PredictionRequestOutbox outbox = outboxMapper.selectByPredictionJobId(jobId);
        if (outbox == null || outbox.getPayloadJson() == null) throw rejected("FROZEN_REQUEST_MISSING");
        return parseFrozenPayload(outbox.getPayloadJson());
    }

    private JsonNode parseFrozenPayload(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isObject()) throw rejected("FROZEN_REQUEST_INVALID");
            return node;
        } catch (OutcomePoisonMessageException error) {
            throw error;
        } catch (Exception error) {
            throw rejected("FROZEN_REQUEST_INVALID");
        }
    }

    private Instant parseInstant(JsonNode node) {
        try {
            return node == null || !node.isTextual() ? null : objectMapper.treeToValue(node, Instant.class);
        } catch (Exception error) {
            throw rejected("FROZEN_REQUEST_INVALID");
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.isTextual() ? node.textValue() : "!invalid!";
    }

    private static boolean isTerminal(String status) {
        return PredictionJobStatus.SUCCEEDED.name().equals(status)
                || PredictionJobStatus.FAILED.name().equals(status)
                || PredictionJobStatus.DEAD_LETTER.name().equals(status);
    }

    private static String safeMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) return null;
        return messageId.length() <= 128 ? messageId : messageId.substring(0, 128);
    }

    private static void requireInserted(int inserted, String reasonCode) {
        if (inserted != 1) throw rejected(reasonCode);
    }

    private static OutcomePoisonMessageException rejected(String reasonCode) {
        return new OutcomePoisonMessageException(reasonCode);
    }

    public enum OutcomeApplyResult { APPLIED, DUPLICATE }
}

package com.lbz.f1aipredict.prediction.inbound;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Evidence;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Failure;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.Result;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos.SelectedOption;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** 先限长再严格解码，并把契约错误分类为可隔离的永久坏消息。 */
@Component
public class OutcomeMessageParser {

    private static final Set<String> RESULT_FIELDS = Set.of("schemaVersion", "messageId", "predictionJobId",
            "batchId", "questionId", "questionSnapshotId", "traceId", "selectedOptions", "confidence",
            "reasoningSummary", "evidence", "sourceDataCutoff", "generatedAt", "modelVersion",
            "promptVersion", "featureVersion", "embeddingVersion", "retrieverVersion");
    private static final Set<String> FAILURE_FIELDS = Set.of("schemaVersion", "messageId", "predictionJobId",
            "batchId", "questionId", "questionSnapshotId", "traceId", "failureCode", "summary", "attempt",
            "sourceDataCutoff", "generatedAt", "modelVersion", "promptVersion", "featureVersion",
            "embeddingVersion", "retrieverVersion");
    private static final Set<String> OPTION_FIELDS = Set.of("optionId", "position");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("sourceType", "sourceName", "sourceUrl",
            "firstSeenAt", "eventTime", "documentId", "chunkId");

    private final ObjectMapper mapper;
    private final ObjectReader strictTreeReader;

    public OutcomeMessageParser(ObjectMapper mapper) {
        this.mapper = mapper;
        this.strictTreeReader = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    /** 解析并验证 RESULT v2 消息。 */
    public Result parseResult(byte[] body, int maxBytes) {
        JsonNode root = parseTree(body, maxBytes);
        validateShape(root, RESULT_FIELDS, "INVALID_RESULT_SCHEMA");
        JsonNode options = root.get("selectedOptions");
        if (!options.isArray() || options.isEmpty() || options.size() > 100) {
            throw poison("INVALID_RESULT_OPTIONS");
        }
        for (JsonNode option : options) {
            validateShape(option, OPTION_FIELDS, "INVALID_RESULT_OPTION");
        }
        JsonNode evidence = root.get("evidence");
        if (!evidence.isArray() || evidence.size() > 100) {
            throw poison("INVALID_RESULT_EVIDENCE");
        }
        for (JsonNode item : evidence) {
            validateShape(item, EVIDENCE_FIELDS, "INVALID_RESULT_EVIDENCE");
        }
        Result result = bind(root, Result.class);
        validateCommon(result.getSchemaVersion(), result.getMessageId(), result.getPredictionJobId(),
                result.getBatchId(), result.getQuestionId(), result.getQuestionSnapshotId(), result.getTraceId(),
                result.getSourceDataCutoff(), result.getGeneratedAt(), result.getModelVersion(),
                result.getPromptVersion(), result.getFeatureVersion(), result.getEmbeddingVersion(),
                result.getRetrieverVersion());
        if (result.getConfidence() == null || !Double.isFinite(result.getConfidence())
                || result.getConfidence() < 0 || result.getConfidence() > 1) {
            throw poison("INVALID_CONFIDENCE");
        }
        requireText(result.getReasoningSummary(), 1, 2048, "INVALID_REASONING_SUMMARY");
        Set<Integer> optionIds = new java.util.HashSet<>();
        Set<Integer> positions = new java.util.HashSet<>();
        for (SelectedOption option : result.getSelectedOptions()) {
            if (option.getOptionId() == null || option.getOptionId() <= 0
                    || option.getPosition() == null || option.getPosition() < 1
                    || !optionIds.add(option.getOptionId()) || !positions.add(option.getPosition())) {
                throw poison("INVALID_RESULT_OPTION");
            }
        }
        for (int position = 1; position <= positions.size(); position++) {
            if (!positions.contains(position)) {
                throw poison("INVALID_RESULT_OPTION_POSITION");
            }
        }
        for (Evidence item : result.getEvidence()) {
            requireText(item.getSourceType(), 1, 64, "INVALID_RESULT_EVIDENCE");
            requireText(item.getSourceName(), 1, 256, "INVALID_RESULT_EVIDENCE");
            optionalText(item.getSourceUrl(), 2048, "INVALID_RESULT_EVIDENCE");
            requireText(item.getDocumentId(), 1, 256, "INVALID_RESULT_EVIDENCE");
            optionalText(item.getChunkId(), 256, "INVALID_RESULT_EVIDENCE");
            if (item.getFirstSeenAt() == null || item.getFirstSeenAt().isAfter(result.getSourceDataCutoff())
                    || (item.getEventTime() != null && item.getEventTime().isAfter(result.getSourceDataCutoff()))) {
                throw poison("EVIDENCE_AFTER_CUTOFF");
            }
        }
        return result;
    }

    /** 解析并验证 FAILURE v2 消息。 */
    public Failure parseFailure(byte[] body, int maxBytes) {
        JsonNode root = parseTree(body, maxBytes);
        validateShape(root, FAILURE_FIELDS, "INVALID_FAILURE_SCHEMA");
        Failure failure = bind(root, Failure.class);
        validateCommon(failure.getSchemaVersion(), failure.getMessageId(), failure.getPredictionJobId(),
                failure.getBatchId(), failure.getQuestionId(), failure.getQuestionSnapshotId(), failure.getTraceId(),
                failure.getSourceDataCutoff(), failure.getGeneratedAt(), failure.getModelVersion(),
                failure.getPromptVersion(), failure.getFeatureVersion(), failure.getEmbeddingVersion(),
                failure.getRetrieverVersion());
        if (failure.getAttempt() == null || failure.getAttempt() < 1 || failure.getFailureCode() == null
                || !Set.of("INVALID_REQUEST", "UNSUPPORTED_QUESTION", "INSUFFICIENT_DATA", "VERSION_UNAVAILABLE",
                "FEATURE_UNAVAILABLE", "RETRIEVAL_FAILED", "MODEL_TIMEOUT", "MODEL_FAILED",
                "INVALID_MODEL_OUTPUT", "INTERNAL_ERROR").contains(failure.getFailureCode())) {
            throw poison("INVALID_FAILURE_CODE_OR_ATTEMPT");
        }
        requireText(failure.getSummary(), 1, 256, "INVALID_FAILURE_SUMMARY");
        String lower = failure.getSummary().toLowerCase(java.util.Locale.ROOT);
        if (failure.getSummary().contains("\n") || failure.getSummary().contains("\r")
                || Set.of("http://", "https://", "traceback", " at ", "password", "token", "secret")
                .stream().anyMatch(lower::contains)) {
            throw poison("UNSAFE_FAILURE_SUMMARY");
        }
        return failure;
    }

    /** 计算原始 UTF-8 消息的 SHA-256，用于持久化幂等凭证和隔离审计。 */
    public static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private JsonNode parseTree(byte[] body, int maxBytes) {
        if (body == null || body.length == 0 || body.length > maxBytes) {
            throw poison("MESSAGE_SIZE_INVALID");
        }
        try {
            JsonNode root = strictTreeReader.readTree(body);
            if (root == null || !root.isObject()) {
                throw poison("INVALID_JSON_ROOT");
            }
            return root;
        } catch (OutcomePoisonMessageException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw poison("INVALID_JSON");
        }
    }

    private void validateShape(JsonNode node, Set<String> allowed, String reason) {
        if (node == null || !node.isObject()) {
            throw poison(reason);
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw poison("UNKNOWN_FIELD");
            }
        });
        for (String field : allowed) {
            if (!node.has(field)) {
                throw poison("MISSING_FIELD");
            }
        }
    }

    private <T> T bind(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (IOException | RuntimeException error) {
            throw poison("INVALID_FIELD_TYPE");
        }
    }

    private static void validateCommon(String schemaVersion, String messageId, String jobId, Long batchId,
                                       Long questionId, Long snapshotId, String traceId,
                                       java.time.Instant cutoff, java.time.Instant generatedAt,
                                       String modelVersion, String promptVersion, String featureVersion,
                                       String embeddingVersion, String retrieverVersion) {
        if (!"2".equals(schemaVersion)) throw poison("UNSUPPORTED_SCHEMA_VERSION");
        requireText(messageId, 1, 128, "INVALID_MESSAGE_ID");
        requireText(jobId, 1, 128, "INVALID_JOB_ID");
        requireText(traceId, 1, 128, "INVALID_TRACE_ID");
        if (batchId == null || batchId <= 0 || questionId == null || questionId <= 0
                || snapshotId == null || snapshotId <= 0 || cutoff == null || generatedAt == null) {
            throw poison("INVALID_REQUIRED_VALUE");
        }
        requireText(modelVersion, 1, 128, "INVALID_VERSION");
        requireText(promptVersion, 1, 128, "INVALID_VERSION");
        requireText(featureVersion, 1, 128, "INVALID_VERSION");
        optionalText(embeddingVersion, 128, "INVALID_VERSION");
        optionalText(retrieverVersion, 128, "INVALID_VERSION");
    }

    private static void requireText(String value, int min, int max, String reason) {
        if (value == null || value.length() < min || value.length() > max || value.isBlank()) {
            throw poison(reason);
        }
    }

    private static void optionalText(String value, int max, String reason) {
        if (value != null && value.length() > max) throw poison(reason);
    }

    private static OutcomePoisonMessageException poison(String reason) {
        return new OutcomePoisonMessageException(reason);
    }
}

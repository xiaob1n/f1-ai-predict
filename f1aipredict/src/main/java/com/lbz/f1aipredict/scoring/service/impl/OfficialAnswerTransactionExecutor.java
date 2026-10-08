package com.lbz.f1aipredict.scoring.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.question.entity.Question;
import com.lbz.f1aipredict.question.mapper.QuestionMapper;
import com.lbz.f1aipredict.scoring.dto.AnswerUpdateRequest;
import com.lbz.f1aipredict.scoring.entity.OfficialAnswer;
import com.lbz.f1aipredict.scoring.feed.AnswerFeedResponse;
import com.lbz.f1aipredict.scoring.mapper.OfficialAnswerMapper;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.entity.FeedRawPayload;
import com.lbz.f1aipredict.sync.entity.SyncRecord;
import com.lbz.f1aipredict.sync.store.SyncPersistenceStore;
import com.lbz.f1aipredict.sync.util.FeedSyncUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 将答案写入、版本归档和同步审计限制在短事务内。
 * <p>
 * 时间语义（D8 边界）：
 * <ul>
 *   <li>{@code published_at}：仅表示官方发布时间。Feed 未提供时为 NULL（UNKNOWN），
 *       绝不用本系统首次观察或同步时间推导；</li>
 *   <li>{@code created_at}：本系统首次观察到该题答案的时间，仅首次插入时写入，修订不改；</li>
 *   <li>{@code synced_at}：最近一次写入当前态内容的时间（Feed 内容变化或人工修订）；
 *       内容哈希未变的重复同步不刷新。</li>
 * </ul>
 */
@Slf4j
@Component
public class OfficialAnswerTransactionExecutor {

    private static final String SOURCE_TYPE = "ANSWERS";
    private final SyncPersistenceStore store;
    private final QuestionMapper questionMapper;
    private final OfficialAnswerMapper answerMapper;
    private final ObjectMapper objectMapper;

    public OfficialAnswerTransactionExecutor(SyncPersistenceStore store, QuestionMapper questionMapper,
                                             OfficialAnswerMapper answerMapper) {
        this.store = Objects.requireNonNull(store);
        this.questionMapper = Objects.requireNonNull(questionMapper);
        this.answerMapper = Objects.requireNonNull(answerMapper);
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public SyncResultDto persist(Integer gamedayId, String sourceUrl, String rawJson, long startedAt) {
        String json = rawJson == null ? "" : rawJson;
        String hash = FeedSyncUtils.sha256Hex(json);
        JsonNode tree;
        if (rawJson == null || rawJson.isBlank()) {
            return record(gamedayId, sourceUrl, hash, null, "FAILED", 200,
                    "Empty or blank response body", startedAt);
        }
        try {
            tree = objectMapper.readTree(json);
            if (tree == null || tree.isMissingNode() || tree.isNull()) {
                return record(gamedayId, sourceUrl, hash, null, "FAILED", 200,
                        "Empty or blank response body", startedAt);
            }
        } catch (JsonProcessingException ex) {
            // raw_json 列为 JSON，非法 JSON 无法留档；仅记录安全的失败审计。
            return record(gamedayId, sourceUrl, hash, null, "FAILED", 200,
                    "Invalid answer feed format", startedAt);
        }
        FeedRawPayload payload = new FeedRawPayload();
        payload.setSourceType(SOURCE_TYPE);
        payload.setSourceUrl(sourceUrl);
        payload.setGamedayId(gamedayId);
        payload.setRawJson(json);
        payload.setContentHash(hash);
        Long payloadId = store.saveRawPayload(payload);
        List<AnswerFeedResponse.Question> questions;
        try {
            AnswerFeedResponse response = objectMapper.readValue(json, AnswerFeedResponse.class);
            if (response == null || response.getData() == null || response.getData().getValue() == null
                    || response.getData().getValue().getQuestions() == null) {
                throw new IllegalArgumentException("Missing answer feed questions");
            }
            questions = response.getData().getValue().getQuestions();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            // 解析失败仍保留整包原文，但不把原文或解析异常消息写进审计。
            log.warn("官方答案 Feed 格式无效: gamedayId={}, errorType={}", gamedayId, ex.getClass().getSimpleName());
            return record(gamedayId, sourceUrl, hash, payloadId, "FAILED", 200,
                    "Invalid answer feed format", startedAt);
        }
        int changed = 0;
        for (AnswerFeedResponse.Question source : questions) {
            if (source == null || source.getId() == null) {
                continue;
            }
            Question question = questionMapper.selectByGamedayIdAndSourceQuestionId(gamedayId, source.getId());
            if (question == null) {
                continue;
            }
            OfficialAnswer current = answerMapper.selectByQuestionIdForUpdate(question.getId());
            if (source.getAnswer() == null || source.getAnswer().isNull() || source.getAnswer().isEmpty()) {
                if (current != null) {
                    Instant revokedAt = Instant.now();
                    if (answerMapper.archiveCurrent(current.getId(), revokedAt, "FEED_REVOKED") != 1) {
                        throw new IllegalStateException("Revoked answer could not be archived");
                    }
                    if (answerMapper.deleteCurrent(current.getId()) != 1) {
                        throw new IllegalStateException("Revoked answer could not be removed");
                    }
                    changed++;
                }
                continue;
            }
            String answerJson = source.getAnswer().toString();
            String answerHash = FeedSyncUtils.sha256Hex(answerJson);
            if (current != null && answerHash.equals(current.getContentHash())) {
                continue;
            }
            Instant now = Instant.now();
            if (current == null) {
                current = new OfficialAnswer();
                current.setQuestionId(question.getId());
                current.setGamedayId(gamedayId);
                current.setCreatedAt(now);
            } else if (answerMapper.archiveCurrent(current.getId(), now, "FEED") != 1) {
                throw new IllegalStateException("Answer revision could not be archived");
            }
            current.setRawJson(answerJson);
            current.setAnswerContent(answerJson);
            current.setOfficialPoints(readPoints(source.getAnswer()));
            // D8：Feed 不提供官方发布时间，置 NULL 表示 UNKNOWN，不用 now 冒充；
            // 修订时旧值（可能来自人工修订）已随 archiveCurrent 进入历史表，新答案的发布时间未知故一并清空。
            current.setPublishedAt(null);
            current.setContentHash(answerHash);
            current.setSyncedAt(now);
            current.setUpdatedAt(now);
            if (current.getId() == null) {
                answerMapper.insert(current);
            } else if (answerMapper.updateCurrent(current) != 1) {
                throw new IllegalStateException("Answer revision could not be saved");
            }
            changed++;
        }
        String status = changed == 0 && store.findLatestUnchanged(SOURCE_TYPE, hash) != null
                ? "SKIPPED_UNCHANGED" : "SUCCESS";
        return record(gamedayId, sourceUrl, hash, payloadId, status, 200, null, startedAt);
    }

    @Transactional
    public SyncResultDto recordFailure(Integer gamedayId, String sourceUrl, String hash,
                                       Integer httpStatus, long startedAt) {
        return record(gamedayId, sourceUrl, hash, null, "FAILED", httpStatus,
                "Feed request failed", startedAt);
    }

    /**
     * 人工修订当前答案：先归档旧版本再覆盖当前态。
     * publishedAt 仅在请求显式给出时写入，否则保留原值（允许为 NULL），不会回填当前时间。
     */
    @Transactional
    public void updateAnswer(Long questionId, AnswerUpdateRequest request) {
        String content = request.getAnswerContent();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("answerContent must not be blank");
        }
        try {
            JsonNode answer = objectMapper.readTree(content);
            if (answer == null || (!answer.isArray() && !answer.isObject()) || answer.isEmpty()) {
                throw new IllegalArgumentException("answerContent must be a non-empty JSON array or object");
            }
            String canonicalJson = objectMapper.writeValueAsString(answer);
            OfficialAnswer current = answerMapper.selectByQuestionIdForUpdate(questionId);
            if (current == null) {
                throw new ResourceNotFoundException("Official answer not found: " + questionId);
            }
            String hash = FeedSyncUtils.sha256Hex(canonicalJson);
            Instant publishedAt = request.getPublishedAt() == null ? current.getPublishedAt() : request.getPublishedAt();
            if (hash.equals(current.getContentHash()) && Objects.equals(request.getOfficialPoints(), current.getOfficialPoints())
                    && Objects.equals(publishedAt, current.getPublishedAt())) {
                return;
            }
            Instant now = Instant.now();
            if (answerMapper.archiveCurrent(current.getId(), now, "MANUAL") != 1) {
                throw new IllegalStateException("Answer revision could not be archived");
            }
            current.setRawJson(canonicalJson);
            current.setAnswerContent(canonicalJson);
            current.setContentHash(hash);
            current.setOfficialPoints(request.getOfficialPoints());
            // D8：仅请求显式给出时才变更；缺省保留原值（可为 NULL），不回填 now。
            current.setPublishedAt(publishedAt);
            current.setSyncedAt(now);
            current.setUpdatedAt(now);
            if (answerMapper.updateCurrent(current) != 1) {
                throw new IllegalStateException("Answer revision could not be saved");
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("answerContent must be valid JSON", ex);
        }
    }

    private static BigDecimal readPoints(JsonNode answer) {
        JsonNode points = answer.get("Points");
        if (points == null || points.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(points.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private SyncResultDto record(Integer gamedayId, String sourceUrl, String hash, Long payloadId,
                                 String status, Integer httpStatus, String errorMessage, long startedAt) {
        SyncRecord audit = new SyncRecord();
        audit.setSourceType(SOURCE_TYPE);
        audit.setSourceUrl(sourceUrl);
        audit.setGamedayId(gamedayId);
        audit.setContentHash(hash);
        audit.setStatus(status);
        audit.setHttpStatus(httpStatus);
        audit.setErrorMessage(errorMessage);
        audit.setDurationMs((int) Math.min(Integer.MAX_VALUE, Math.max(0L, System.currentTimeMillis() - startedAt)));
        Long recordId = store.saveSyncRecord(audit);
        return SyncResultDto.builder().sourceType(SOURCE_TYPE).status(status).gamedayId(gamedayId)
                .contentHash(hash).payloadId(payloadId).syncRecordId(recordId).errorMessage(errorMessage).build();
    }
}

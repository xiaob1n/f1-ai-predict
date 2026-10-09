package com.lbz.f1aipredict.scoring.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.question.entity.Question;
import com.lbz.f1aipredict.question.mapper.QuestionMapper;
import com.lbz.f1aipredict.scoring.dto.AnswerUpdateRequest;
import com.lbz.f1aipredict.scoring.entity.OfficialAnswer;
import com.lbz.f1aipredict.scoring.feed.AnswerFeedResponse;
import com.lbz.f1aipredict.scoring.mapper.OfficialAnswerMapper;
import com.lbz.f1aipredict.season.entity.MeetingSession;
import com.lbz.f1aipredict.season.entity.Round;
import com.lbz.f1aipredict.season.mapper.MeetingSessionMapper;
import com.lbz.f1aipredict.season.mapper.RoundMapper;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.entity.FeedRawPayload;
import com.lbz.f1aipredict.sync.entity.SyncRecord;
import com.lbz.f1aipredict.sync.store.SyncPersistenceStore;
import com.lbz.f1aipredict.sync.util.FeedSyncUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 官方答案登记、栅栏核验与写入在独立短事务中完成；HTTP 不在此组件执行。
 */
@Slf4j
@Component
public class OfficialAnswerTransactionExecutor {

    private static final String SOURCE_TYPE = "ANSWERS";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED_SUPERSEDED = "SKIPPED_SUPERSEDED";
    private final SyncPersistenceStore store;
    private final QuestionMapper questionMapper;
    private final OfficialAnswerMapper answerMapper;
    private final RoundMapper roundMapper;
    private final MeetingSessionMapper meetingSessionMapper;
    private final ObjectMapper objectMapper;

    public OfficialAnswerTransactionExecutor(SyncPersistenceStore store, QuestionMapper questionMapper,
                                             OfficialAnswerMapper answerMapper, RoundMapper roundMapper,
                                             MeetingSessionMapper meetingSessionMapper) {
        this.store = Objects.requireNonNull(store);
        this.questionMapper = Objects.requireNonNull(questionMapper);
        this.answerMapper = Objects.requireNonNull(answerMapper);
        this.roundMapper = Objects.requireNonNull(roundMapper);
        this.meetingSessionMapper = Objects.requireNonNull(meetingSessionMapper);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 在共同 round 锁内核验归属并登记请求序号，提交后调用方才可发起 HTTP。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long beginAnswerSync(Integer gamedayId, String sourceUrl, long startedAt) {
        lockAndVerifyGameday(gamedayId);
        SyncRecord record = new SyncRecord();
        record.setSourceType(SOURCE_TYPE);
        record.setSourceUrl(sourceUrl);
        record.setGamedayId(gamedayId);
        record.setStatus(STATUS_RUNNING);
        record.setDurationMs(elapsedMs(startedAt));
        return store.saveSyncRecord(record);
    }

    /**
     * 在登记事务持续发生瞬态数据库失败时，另开短事务尽力记录独立 FAILED 审计。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long recordBeginFailure(Integer gamedayId, String sourceUrl, long startedAt) {
        lockAndVerifyGameday(gamedayId);
        SyncRecord record = new SyncRecord();
        record.setSourceType(SOURCE_TYPE);
        record.setSourceUrl(sourceUrl);
        record.setGamedayId(gamedayId);
        record.setStatus(STATUS_FAILED);
        record.setErrorMessage("Answer sync registration failed");
        record.setDurationMs(elapsedMs(startedAt));
        return store.saveSyncRecord(record);
    }

    /**
     * 仅当前最新且仍 RUNNING 的登记可提交 Feed 答案；过期响应只留档并标记跳过。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SyncResultDto persist(Long syncRecordId, Integer gamedayId, String sourceUrl, String rawJson,
                                 long startedAt) {
        SyncRecord record = lockRegistration(syncRecordId, gamedayId);
        String json = rawJson == null ? "" : rawJson;
        String hash = FeedSyncUtils.sha256Hex(json);
        List<AnswerFeedResponse.Question> questions = null;
        boolean validJson = false;
        String validationError = null;
        if (rawJson == null || rawJson.isBlank()) {
            validationError = "Empty or blank response body";
        } else {
            try {
                JsonNode tree = objectMapper.readTree(json);
                if (tree == null || tree.isMissingNode() || tree.isNull()) {
                    validationError = "Empty or blank response body";
                } else {
                    validJson = true;
                    AnswerFeedResponse response = objectMapper.readValue(json, AnswerFeedResponse.class);
                    if (response == null || response.getData() == null || response.getData().getValue() == null
                            || response.getData().getValue().getQuestions() == null) {
                        validationError = "Invalid answer feed format";
                    } else {
                        questions = response.getData().getValue().getQuestions();
                    }
                }
            } catch (JsonProcessingException | IllegalArgumentException ex) {
                validationError = "Invalid answer feed format";
            }
        }

        Long payloadId = null;
        if (validJson) {
            FeedRawPayload payload = new FeedRawPayload();
            payload.setSourceType(SOURCE_TYPE);
            payload.setSourceUrl(sourceUrl);
            payload.setGamedayId(gamedayId);
            payload.setRawJson(json);
            payload.setContentHash(hash);
            payloadId = store.saveRawPayload(payload);
        }

        if (!isLatestRunning(record, gamedayId)) {
            finish(record, STATUS_SKIPPED_SUPERSEDED, 200, "Superseded by a newer answer sync", hash, startedAt);
            return result(STATUS_SKIPPED_SUPERSEDED, gamedayId, hash, payloadId,
                    record.getId(), "Superseded by a newer answer sync");
        }
        if (validationError != null) {
            finish(record, STATUS_FAILED, 200, validationError, hash, startedAt);
            return result(STATUS_FAILED, gamedayId, hash, null, record.getId(), validationError);
        }

        int changed = persistAnswers(gamedayId, questions);
        String status = changed == 0 && store.findLatestUnchanged(SOURCE_TYPE, hash) != null
                ? "SKIPPED_UNCHANGED" : STATUS_SUCCESS;
        finish(record, status, 200, null, hash, startedAt);
        return result(status, gamedayId, hash, payloadId, record.getId(), null);
    }

    /** 拉取失败也必须终结原登记，且失败的最新 token 会继续阻止旧响应覆盖。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SyncResultDto recordFailure(Long syncRecordId, Integer gamedayId, Integer httpStatus, long startedAt) {
        SyncRecord record = lockRegistration(syncRecordId, gamedayId);
        if (!isLatestRunning(record, gamedayId)) {
            finish(record, STATUS_SKIPPED_SUPERSEDED, httpStatus, "Superseded by a newer answer sync", null, startedAt);
            return result(STATUS_SKIPPED_SUPERSEDED, gamedayId, null, null, record.getId(),
                    "Superseded by a newer answer sync");
        }
        finish(record, STATUS_FAILED, httpStatus, "Feed request failed", null, startedAt);
        return result(STATUS_FAILED, gamedayId, null, null, record.getId(), "Feed request failed");
    }

    /**
     * 人工修订当前答案：先归档旧版本再覆盖当前态；人工答案不具备永久优先级。
     * publishedAt 仅在请求显式给出时写入，否则保留原值（允许为 NULL）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateAnswer(Long questionId, AnswerUpdateRequest request) {
        String content = request.getAnswerContent();
        if (content == null || content.isBlank()) {
            throw new InvalidRequestException("answerContent must not be blank");
        }
        try {
            JsonNode answer = objectMapper.readTree(content);
            if (answer == null || (!answer.isArray() && !answer.isObject()) || answer.isEmpty()) {
                throw new InvalidRequestException("answerContent must be a non-empty JSON array or object");
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
            current.setPublishedAt(publishedAt);
            current.setSyncedAt(now);
            current.setUpdatedAt(now);
            if (answerMapper.updateCurrent(current) != 1) {
                throw new IllegalStateException("Answer revision could not be saved");
            }
        } catch (JsonProcessingException ex) {
            throw new InvalidRequestException("answerContent must be valid JSON", ex);
        }
    }

    private SyncRecord lockRegistration(Long syncRecordId, Integer gamedayId) {
        if (syncRecordId == null) {
            throw new InvalidRequestException("Answer sync token is required");
        }
        lockAndVerifyGameday(gamedayId);
        SyncRecord record = store.findSyncRecordById(syncRecordId);
        if (record == null || !SOURCE_TYPE.equals(record.getSourceType())
                || !Objects.equals(gamedayId, record.getGamedayId())) {
            throw new InvalidRequestException("Answer sync token is invalid");
        }
        SyncRecord latest = store.findLatestAnswerRegistrationForUpdate(gamedayId);
        if (latest == null) {
            throw new InvalidRequestException("Answer sync registration is missing");
        }
        if (Objects.equals(latest.getId(), record.getId())) {
            record.setStatus(latest.getStatus());
        }
        return record;
    }

    private boolean isLatestRunning(SyncRecord record, Integer gamedayId) {
        SyncRecord latest = store.findLatestAnswerRegistrationForUpdate(gamedayId);
        return latest != null && Objects.equals(latest.getId(), record.getId())
                && STATUS_RUNNING.equals(record.getStatus());
    }

    private Long lockAndVerifyGameday(Integer gamedayId) {
        List<MeetingSession> before = meetingSessionMapper.selectAllByGamedayId(gamedayId);
        Set<Long> roundIds = roundIds(before, gamedayId);
        if (roundIds.size() != 1) {
            throw new InvalidRequestException("Gameday must belong to exactly one existing round");
        }
        Long roundId = roundIds.iterator().next();
        Round lockedRound = roundMapper.selectByIdForUpdate(roundId);
        if (lockedRound == null) {
            throw new InvalidRequestException("Gameday round anchor is missing");
        }
        Set<Long> lockedRoundIds = roundIds(meetingSessionMapper.selectAllByGamedayId(gamedayId), gamedayId);
        List<Long> questionRoundIds = questionMapper.selectDistinctRoundIdsByGamedayId(gamedayId);
        Set<Long> questionRounds = questionRoundIds == null ? Set.of() : new HashSet<>(questionRoundIds);
        if (!lockedRoundIds.equals(Set.of(roundId))
                || questionRounds.stream().anyMatch(questionRoundId -> !Objects.equals(roundId, questionRoundId))) {
            throw new InvalidRequestException("Gameday question or session round ownership is inconsistent");
        }
        return roundId;
    }

    private static Set<Long> roundIds(List<MeetingSession> sessions, Integer gamedayId) {
        Set<Long> ids = new HashSet<>();
        if (sessions != null) {
            for (MeetingSession session : sessions) {
                if (session == null || !Objects.equals(gamedayId, session.getGamedayId()) || session.getRoundId() == null) {
                    throw new InvalidRequestException("Gameday session ownership is missing or inconsistent");
                }
                ids.add(session.getRoundId());
            }
        }
        return ids;
    }

    private int persistAnswers(Integer gamedayId, List<AnswerFeedResponse.Question> questions) {
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
                    if (answerMapper.archiveCurrent(current.getId(), revokedAt, "FEED_REVOKED") != 1
                            || answerMapper.deleteCurrent(current.getId()) != 1) {
                        throw new IllegalStateException("Revoked answer could not be archived");
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
        return changed;
    }

    private void finish(SyncRecord record, String status, Integer httpStatus, String errorMessage,
                        String hash, long startedAt) {
        record.setStatus(status);
        record.setHttpStatus(httpStatus);
        record.setErrorMessage(errorMessage);
        record.setContentHash(hash);
        record.setDurationMs(elapsedMs(startedAt));
        store.finishSyncRecord(record);
    }

    private static SyncResultDto result(String status, Integer gamedayId, String hash, Long payloadId,
                                        Long syncRecordId, String errorMessage) {
        return SyncResultDto.builder().sourceType(SOURCE_TYPE).status(status).gamedayId(gamedayId)
                .contentHash(hash).payloadId(payloadId).syncRecordId(syncRecordId).errorMessage(errorMessage).build();
    }

    private static Integer elapsedMs(long startedAt) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, System.currentTimeMillis() - startedAt));
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
}

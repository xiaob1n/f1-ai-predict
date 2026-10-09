package com.lbz.f1aipredict.scoring.service.impl;

import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.scoring.dto.AnswerUpdateRequest;
import com.lbz.f1aipredict.scoring.entity.OfficialAnswer;
import com.lbz.f1aipredict.scoring.mapper.OfficialAnswerMapper;
import com.lbz.f1aipredict.scoring.service.OfficialAnswerService;
import com.lbz.f1aipredict.sync.FeedSyncException;
import com.lbz.f1aipredict.sync.client.F1PredictFeedClient;
import com.lbz.f1aipredict.sync.config.F1PredictFeedProperties;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** 官方答案同步编排：HTTP 拉取发生在事务之外。 */
@Slf4j
@Service
public class OfficialAnswerServiceImpl implements OfficialAnswerService {

    private final F1PredictFeedClient feedClient;
    private final F1PredictFeedProperties properties;
    private final OfficialAnswerTransactionExecutor executor;
    private final OfficialAnswerMapper mapper;

    public OfficialAnswerServiceImpl(F1PredictFeedClient feedClient, F1PredictFeedProperties properties,
                                     OfficialAnswerTransactionExecutor executor, OfficialAnswerMapper mapper) {
        this.feedClient = Objects.requireNonNull(feedClient);
        this.properties = Objects.requireNonNull(properties);
        this.executor = Objects.requireNonNull(executor);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public SyncResultDto syncAnswers(Integer gamedayId) {
        if (gamedayId == null || gamedayId <= 0) {
            throw new InvalidRequestException("gamedayId must be positive");
        }
        long startedAt = System.currentTimeMillis();
        String path = properties.getQuestionsPath().replace("{gamedayId}", String.valueOf(gamedayId));
        String sourceUrl = properties.getBaseUrl().replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
        Long syncRecordId;
        try {
            syncRecordId = retryTransaction(() -> executor.beginAnswerSync(gamedayId, sourceUrl, startedAt));
        } catch (TransientDataAccessException | DuplicateKeyException ex) {
            try {
                retryTransaction(() -> executor.recordBeginFailure(gamedayId, sourceUrl, startedAt));
            } catch (RuntimeException auditFailure) {
                log.warn("答案同步登记失败且独立审计不可用: gamedayId={}, auditErrorType={}",
                        gamedayId, auditFailure.getClass().getSimpleName());
            }
            throw ex;
        }

        String rawJson;
        try {
            rawJson = feedClient.fetchQuestions(gamedayId);
        } catch (FeedSyncException ex) {
            log.warn("官方答案 Feed 拉取失败: gamedayId={}, httpStatus={}", gamedayId, ex.getHttpStatus());
            return retryTransaction(() -> executor.recordFailure(syncRecordId, gamedayId, ex.getHttpStatus(), startedAt));
        }
        try {
            return retryTransaction(() -> executor.persist(syncRecordId, gamedayId, sourceUrl, rawJson, startedAt));
        } catch (RuntimeException ex) {
            try {
                retryTransaction(() -> executor.recordFailure(syncRecordId, gamedayId, null, startedAt));
            } catch (RuntimeException auditFailure) {
                log.warn("答案同步事务回滚且独立失败审计不可用: gamedayId={}, auditErrorType={}",
                        gamedayId, auditFailure.getClass().getSimpleName());
            }
            throw ex;
        }
    }

    @Override
    public Optional<OfficialAnswer> getByQuestionId(Long questionId) {
        Objects.requireNonNull(questionId, "questionId must not be null");
        return Optional.ofNullable(mapper.selectByQuestionId(questionId));
    }

    @Override
    public void updateAnswer(Long questionId, AnswerUpdateRequest request) {
        Objects.requireNonNull(questionId, "questionId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        executor.updateAnswer(questionId, request);
    }

    /** 瞬态死锁/重复键重试每次都重新进入事务代理，不重复发起 HTTP 请求。 */
    private static <T> T retryTransaction(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (TransientDataAccessException | DuplicateKeyException ex) {
                if (attempt >= 3) {
                    throw ex;
                }
            }
        }
    }
}

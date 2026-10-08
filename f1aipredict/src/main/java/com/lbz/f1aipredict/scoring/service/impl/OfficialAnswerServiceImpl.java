package com.lbz.f1aipredict.scoring.service.impl;

import com.lbz.f1aipredict.scoring.dto.AnswerUpdateRequest;
import com.lbz.f1aipredict.scoring.entity.OfficialAnswer;
import com.lbz.f1aipredict.scoring.mapper.OfficialAnswerMapper;
import com.lbz.f1aipredict.scoring.service.OfficialAnswerService;
import com.lbz.f1aipredict.sync.FeedSyncException;
import com.lbz.f1aipredict.sync.client.F1PredictFeedClient;
import com.lbz.f1aipredict.sync.config.F1PredictFeedProperties;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

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
            throw new IllegalArgumentException("gamedayId must be positive");
        }
        long startedAt = System.currentTimeMillis();
        String path = properties.getQuestionsPath().replace("{gamedayId}", String.valueOf(gamedayId));
        String sourceUrl = properties.getBaseUrl().replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
        try {
            String rawJson = feedClient.fetchQuestions(gamedayId);
            return executor.persist(gamedayId, sourceUrl, rawJson, startedAt);
        } catch (FeedSyncException ex) {
            log.warn("官方答案 Feed 拉取失败: gamedayId={}, httpStatus={}", gamedayId, ex.getHttpStatus());
            return executor.recordFailure(gamedayId, sourceUrl, null, ex.getHttpStatus(), startedAt);
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
}

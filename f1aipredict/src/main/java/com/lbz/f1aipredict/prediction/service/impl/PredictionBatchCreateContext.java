package com.lbz.f1aipredict.prediction.service.impl;

import com.lbz.f1aipredict.question.service.PredictionQuestionView;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 已完成事务外校验的预测批次创建上下文。 */
public record PredictionBatchCreateContext(
        Long roundId,
        Instant dataCutoff,
        Instant predictionDeadline,
        String featureVersion,
        String modelVersion,
        String promptVersion,
        List<PredictionQuestionView> questions,
        PredictionRequestSnapshotResolver.FrozenBatch frozenBatch,
        String traceId) {

    /** 复制题目列表，防止写事务期间改变冻结顺序。 */
    public PredictionBatchCreateContext {
        questions = List.copyOf(questions);
        Objects.requireNonNull(frozenBatch, "frozenBatch must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
    }
}

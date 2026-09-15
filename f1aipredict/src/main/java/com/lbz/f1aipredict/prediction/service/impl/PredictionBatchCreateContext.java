package com.lbz.f1aipredict.prediction.service.impl;

import com.lbz.f1aipredict.question.service.PredictionQuestionView;

import java.time.Instant;
import java.util.List;

/**
 * 已完成事务外校验的预测批次创建上下文。
 * <p>
 * 进入短事务前复制题目列表，保证写入期间使用同一组快照、截止时间和版本信息。
 */
public record PredictionBatchCreateContext(
        Long roundId,
        Instant dataCutoff,
        String featureVersion,
        String modelVersion,
        String promptVersion,
        List<PredictionQuestionView> questions) {

    /**
     * 构造冻结上下文，阻止调用方在校验后修改题目顺序或内容引用列表。
     */
    public PredictionBatchCreateContext {
        questions = List.copyOf(questions);
    }
}

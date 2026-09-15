package com.lbz.f1aipredict.question.service;

import lombok.Value;

import java.util.List;

/**
 * 预测创建用的不可变题目输入视图。
 * <p>
 * {@code snapshotId} 必须等于题目当前的 {@code latestSnapshotId}，
 * 调用方不得回退到其他历史快照。{@code options} 在构造时复制为不可变列表。
 * 本类型不是对外 REST DTO，不参与公开 JSON 契约。
 */
@Value
public class PredictionQuestionView {

    /** 题目主键 */
    Long questionId;

    /** 题目序号，用于稳定排序 */
    Integer questionNo;

    /** 创建时冻结的最新快照 ID，等于 Question.latestSnapshotId */
    Long snapshotId;

    /** 该最新快照下的非空选项，不可变 */
    List<PredictionQuestionOptionView> options;

    /**
     * 构造不可变视图，选项集合会被复制，避免调用方持有可变内部列表。
     */
    public PredictionQuestionView(Long questionId,
                                  Integer questionNo,
                                  Long snapshotId,
                                  List<PredictionQuestionOptionView> options) {
        this.questionId = questionId;
        this.questionNo = questionNo;
        this.snapshotId = snapshotId;
        this.options = options == null ? List.of() : List.copyOf(options);
    }
}

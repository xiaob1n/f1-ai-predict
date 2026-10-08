package com.lbz.f1aipredict.scoring;

/**
 * 单题评分状态，值与 {@code sql/011_scoring_status.sql} 中
 * {@code scoring_detail.score_status} 的 CHECK 约束逐字一致。
 * <p>
 * 只有 {@link #SCORED_CORRECT} 与 {@link #SCORED_ZERO} 计入批次总分，
 * 因此“已评分得 0 分”与“未评分”可以通过状态区分。
 */
public enum ScoreStatus {

    /** 命中官方答案，计满分 */
    SCORED_CORRECT(true),

    /** 已评分但未命中，得 0 分且计入分母 */
    SCORED_ZERO(true),

    /** 结果在业务截止之后到达，不计入有效评分 */
    LATE(false),

    /** 官方答案尚未公布 */
    NO_ANSWER(false),

    /** 题目已取消 */
    CANCELLED(false),

    /** 其他无法评分的原因，具体原因见 detail_json */
    UNSCORED(false);

    private final boolean counted;

    ScoreStatus(boolean counted) {
        this.counted = counted;
    }

    /** 是否计入批次总分与满分。 */
    public boolean isCounted() {
        return counted;
    }
}

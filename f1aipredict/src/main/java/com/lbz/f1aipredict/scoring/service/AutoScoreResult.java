package com.lbz.f1aipredict.scoring.service;

import com.lbz.f1aipredict.scoring.entity.BatchTotalScore;

/**
 * 自动首次评分的判定结果。
 * <p>
 * 只有 {@link Decision#SCORED} 与 {@link Decision#ALREADY_SCORED} 携带总分；
 * 其余均为明确的跳过原因，本次没有写入任何评分数据，等待下一轮调度再判定。
 *
 * @param decision 判定结果
 * @param total    本次写入或此前已存在的总分；跳过时为 null
 */
public record AutoScoreResult(Decision decision, BatchTotalScore total) {

    /** 自动评分判定。 */
    public enum Decision {

        /** 本次完成首次评分 */
        SCORED,

        /** 批次此前已有总分，自动流程不重算 */
        ALREADY_SCORED,

        /** 批次尚未锁定，预测仍可能变化 */
        SKIPPED_NOT_LOCKED,

        /** 仍有任务未进入终态（SUCCEEDED / FAILED / DEAD_LETTER），或批次没有任务 */
        SKIPPED_JOBS_NOT_TERMINAL,

        /** 全部任务已终态但没有任何成功结果，不伪造评分 */
        SKIPPED_NO_RESULTS,

        /** 存在准时、未取消的成功结果缺少官方答案，等待答案同步 */
        SKIPPED_ANSWERS_MISSING
    }

    /** 构造不携带总分的跳过结果。 */
    public static AutoScoreResult skipped(Decision decision) {
        return new AutoScoreResult(decision, null);
    }
}

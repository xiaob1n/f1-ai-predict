package com.lbz.f1aipredict.prediction;

/**
 * 预测任务状态，值与 {@code sql/004_prediction.sql} 中
 * {@code prediction_job.status} COMMENT 逐字一致：
 * {@code PENDING/RUNNING/RETRYING/SUCCEEDED/FAILED/DEAD_LETTER}。
 * <p>
 * 本枚举只提供只读状态常量，不提供状态更新或流转行为。
 */
public enum PredictionJobStatus {

    /** 任务已落库，等待执行 */
    PENDING,

    /** 任务正在执行 */
    RUNNING,

    /** 任务重试中 */
    RETRYING,

    /** 任务成功 */
    SUCCEEDED,

    /** 任务失败 */
    FAILED,

    /** 超过重试上限进入死信 */
    DEAD_LETTER
}

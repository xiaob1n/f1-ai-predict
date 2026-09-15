package com.lbz.f1aipredict.prediction;

/**
 * 预测批次状态，值与 {@code sql/004_prediction.sql} 中
 * {@code prediction_batch.status} COMMENT 逐字一致：
 * {@code PENDING/TASK_CREATED/PARTIAL/COMPLETED/FAILED}。
 * <p>
 * 本枚举只提供只读状态常量，不提供状态更新或流转行为。
 */
public enum PredictionBatchStatus {

    /** 批次已落库，任务尚未发布 */
    PENDING,

    /** 批次任务已创建（本阶段接口不写入此状态） */
    TASK_CREATED,

    /** 批次部分完成 */
    PARTIAL,

    /** 批次全部完成 */
    COMPLETED,

    /** 批次失败 */
    FAILED
}

package com.lbz.f1aipredict.prediction.mapper;

import lombok.Getter;
import lombok.Setter;

/**
 * 批次内任务状态计数投影，对应一次 {@code GROUP}/条件聚合查询。
 * 每种 {@link com.lbz.f1aipredict.prediction.PredictionJobStatus} 一个 Long 计数，
 * 不是表实体，禁止标注 {@code @TableName}。
 */
@Getter
@Setter
public class PredictionJobStatusCounts {

    /** PENDING 任务数 */
    private Long pendingCount;

    /** RUNNING 任务数 */
    private Long runningCount;

    /** RETRYING 任务数 */
    private Long retryingCount;

    /** SUCCEEDED 任务数 */
    private Long succeededCount;

    /** FAILED 任务数 */
    private Long failedCount;

    /** DEAD_LETTER 任务数 */
    private Long deadLetterCount;

    /** 不属于 Java 已知枚举的任务数 */
    private Long unknownCount;
}

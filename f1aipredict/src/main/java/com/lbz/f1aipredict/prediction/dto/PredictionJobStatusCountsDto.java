package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批次详情中按任务状态聚合的计数。
 * <p>
 * 口径由后续查询服务填充：{@code succeededCount} 对应 SUCCEEDED，
 * {@code failedCount} 对应 FAILED+DEAD_LETTER，{@code deadLetterCount} 单独给出，
 * 未知库内状态计入 {@code unknownCount}。本 DTO 不包含流转行为。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionJobStatusCountsDto {

    /** PENDING 任务数 */
    @JsonProperty("pendingCount")
    private Integer pendingCount;

    /** RUNNING 任务数 */
    @JsonProperty("runningCount")
    private Integer runningCount;

    /** RETRYING 任务数 */
    @JsonProperty("retryingCount")
    private Integer retryingCount;

    /** SUCCEEDED 任务数 */
    @JsonProperty("succeededCount")
    private Integer succeededCount;

    /** FAILED + DEAD_LETTER 合计失败数 */
    @JsonProperty("failedCount")
    private Integer failedCount;

    /** DEAD_LETTER 任务数（同时计入 failedCount） */
    @JsonProperty("deadLetterCount")
    private Integer deadLetterCount;

    /** 未知状态任务数，避免被静默并入其他计数 */
    @JsonProperty("unknownCount")
    private Integer unknownCount;
}

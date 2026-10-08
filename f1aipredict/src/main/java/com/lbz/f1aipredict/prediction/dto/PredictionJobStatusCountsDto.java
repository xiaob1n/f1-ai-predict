package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批次详情中按任务状态聚合的计数。
 * <p>
 * 口径由后续查询服务填充：{@code succeeded_count} 对应 SUCCEEDED，
 * {@code failed_count} 对应 FAILED+DEAD_LETTER，{@code dead_letter_count} 单独给出，
 * 未知库内状态计入 {@code unknown_count}。REST JSON 键为 snake_case。本 DTO 不包含流转行为。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionJobStatusCountsDto {

    /** PENDING 任务数 */
    @JsonProperty("pending_count")
    private Integer pendingCount;

    /** RUNNING 任务数 */
    @JsonProperty("running_count")
    private Integer runningCount;

    /** RETRYING 任务数 */
    @JsonProperty("retrying_count")
    private Integer retryingCount;

    /** SUCCEEDED 任务数 */
    @JsonProperty("succeeded_count")
    private Integer succeededCount;

    /** FAILED + DEAD_LETTER 合计失败数 */
    @JsonProperty("failed_count")
    private Integer failedCount;

    /** DEAD_LETTER 任务数（同时计入 failedCount） */
    @JsonProperty("dead_letter_count")
    private Integer deadLetterCount;

    /** 未知状态任务数，避免被静默并入其他计数 */
    @JsonProperty("unknown_count")
    private Integer unknownCount;
}

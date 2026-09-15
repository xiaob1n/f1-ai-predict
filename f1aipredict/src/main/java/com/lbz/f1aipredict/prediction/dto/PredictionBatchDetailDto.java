package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 预测批次详情 DTO。
 * <p>
 * 时间字段均为 UTC {@link Instant}。不暴露 Entity、内部任务主键、messageId 或 lastError。
 * 任务状态计数见 {@link PredictionJobStatusCountsDto}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionBatchDetailDto {

    /** 批次主键 */
    @JsonProperty("batchId")
    private Long batchId;

    /** 所属分站 ID */
    @JsonProperty("roundId")
    private Long roundId;

    /** 同分站内批次序号 */
    @JsonProperty("batchNo")
    private Integer batchNo;

    /** 批次状态，JSON 值与 SQL 004 注释一致 */
    @JsonProperty("status")
    private String status;

    /** 批次表上的题目总数真值 */
    @JsonProperty("questionCount")
    private Integer questionCount;

    /** 本批次统一数据截止时间（UTC） */
    @JsonProperty("dataCutoff")
    private Instant dataCutoff;

    /** 创建时间（UTC） */
    @JsonProperty("createdAt")
    private Instant createdAt;

    /** 更新时间（UTC） */
    @JsonProperty("updatedAt")
    private Instant updatedAt;

    /** 任务状态实时聚合计数 */
    @JsonProperty("statusCounts")
    private PredictionJobStatusCountsDto statusCounts;
}

package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建预测批次成功后的响应 DTO。
 * <p>
 * 本阶段创建成功只表示已落库，状态固定由调用方填 {@code PENDING}，
 * 不表示 RabbitMQ 已发布。jobIds 为公开业务键，默认空列表避免序列化为 null。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionBatchDto {

    /** 批次主键 */
    @JsonProperty("batchId")
    private Long batchId;

    /** 所属分站 ID */
    @JsonProperty("roundId")
    private Long roundId;

    /** 批次状态，JSON 值与 SQL 004 注释一致，如 PENDING */
    @JsonProperty("status")
    private String status;

    /** 批次内题目/任务总数 */
    @JsonProperty("questionCount")
    private Integer questionCount;

    /** 已创建任务的公开业务键列表，默认空列表 */
    @JsonProperty("jobIds")
    @Builder.Default
    private List<String> jobIds = new ArrayList<>();
}

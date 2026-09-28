package com.lbz.f1aipredict.prediction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 预测任务终态查询 DTO；仅包含面向客户端的白名单字段。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionJobOutcomeDto {

    /** 任务公开业务键。 */
    @JsonProperty("predictionJobId")
    private String predictionJobId;

    /** 当前任务状态。 */
    @JsonProperty("status")
    private String status;

    /** 成功结果；任务尚未完成或失败时为空。 */
    @JsonProperty("result")
    private Result result;

    /** 安全失败摘要；任务尚未完成或成功时为空。 */
    @JsonProperty("failure")
    private Failure failure;

    /** 成功结果公开字段。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {

        @JsonProperty("selectedOptions")
        private List<SelectedOption> selectedOptions;

        @JsonProperty("confidence")
        private Double confidence;
    }

    /** 模型选择的题目选项，不公开选项行的数据库主键。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelectedOption {

        @JsonProperty("optionId")
        private Integer optionId;

        @JsonProperty("position")
        private Integer position;
    }

    /** 安全失败信息，不含内部异常或工作器堆栈。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Failure {

        @JsonProperty("failureCode")
        private String failureCode;

        @JsonProperty("summary")
        private String summary;
    }
}

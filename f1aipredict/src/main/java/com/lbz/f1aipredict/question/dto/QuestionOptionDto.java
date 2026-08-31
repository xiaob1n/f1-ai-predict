package com.lbz.f1aipredict.question.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 题目选项 DTO。
 * <p>
 * 对应问题快照下的单个选项，字段与 json 响应示例一致（camelCase）。
 * 所有字段显式声明 {@link JsonProperty} 以锁定 JSON 序列化名称。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionOptionDto {

    /** 选项在 Feed 中的唯一标识 ID */
    @JsonProperty("optionId")
    private Integer optionId;

    /** 选项序号，用于展示顺序排序 */
    @JsonProperty("optionNo")
    private Integer optionNo;

    /** 选项文本 */
    @JsonProperty("optionText")
    private String optionText;

    /** 选项分值 */
    @JsonProperty("points")
    private Integer points;

    /** 模型预测概率，0~1 之间的小数 */
    @JsonProperty("chance")
    private BigDecimal chance;
}
package com.lbz.f1aipredict.question.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 题目列表项 DTO。
 * <p>
 * 字段对应题目公开接口的展示形态（camelCase 显式 {@link JsonProperty}）。
 * options 默认初始化为空列表，避免未附带选项时序列化为 null。
 * questionType 首版固定为 UNKNOWN，后续由 Feed 同步映射替换。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionDto {

    /** 题目 ID */
    @JsonProperty("questionId")
    private Long questionId;

    /** 所属分站（gameday）ID */
    @JsonProperty("gamedayId")
    private Integer gamedayId;

    /** 来源题目的 ID（Feed 侧） */
    @JsonProperty("sourceQuestionId")
    private Integer sourceQuestionId;

    /** 题目在分站内的编号，用于排序展示 */
    @JsonProperty("questionNo")
    private Integer questionNo;

    /** 题目文本 */
    @JsonProperty("questionText")
    private String questionText;

    /** 题目副文本，可空 */
    @JsonProperty("subText")
    private String subText;

    /** 题目类型，首版固定为 UNKNOWN */
    @JsonProperty("questionType")
    @Builder.Default
    private String questionType = "UNKNOWN";

    /** 选项模板 ID，原样保留，暂不映射 */
    @JsonProperty("optionTemplateId")
    private Integer optionTemplateId;

    /** 可选选项数限制 */
    @JsonProperty("choiceLimit")
    private Integer choiceLimit;

    /** 题目状态，如 OPEN */
    @JsonProperty("status")
    private String status;

    /** 最新快照 ID */
    @JsonProperty("latestSnapshotId")
    private Long latestSnapshotId;

    /** 选项列表，默认空列表 */
    @JsonProperty("options")
    @Builder.Default
    private List<QuestionOptionDto> options = new ArrayList<>();
}
package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * questions_{gamedayId}_en.json 中的单道题目。
 * Answer 字段为官方答案，本任务不解析、不持久化。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionsFeedQuestion {

    /** 题目来源唯一 ID */
    @JsonProperty("Id")
    private Integer id;

    /** 题目序号 */
    @JsonProperty("No")
    private Integer no;

    /** 题目正文 */
    @JsonProperty("Text")
    private String text;

    /** 补充说明 */
    @JsonProperty("SubText")
    private String subText;

    /** 题型模板 ID */
    @JsonProperty("OptionTemplateId")
    private Integer optionTemplateId;

    /** 题目状态（数值） */
    @JsonProperty("Status")
    private Integer status;

    /** 题目配置，如可选答案数量 */
    @JsonProperty("Config")
    private QuestionsFeedConfig config;

    /** 选项列表 */
    @JsonProperty("Options")
    private List<QuestionsFeedOption> options;
}

package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 题目配置节点，当前主要承载可选答案数量 ChoiceLimit。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionsFeedConfig {

    /** 可选答案数量 */
    @JsonProperty("ChoiceLimit")
    private Integer choiceLimit;
}

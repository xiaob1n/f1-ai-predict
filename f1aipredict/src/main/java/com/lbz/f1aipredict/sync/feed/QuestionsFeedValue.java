package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * questions_{gamedayId}_en.json 的 Data.Value 节点，包含 Questions 数组。
 * 注意：Value 是对象而非数组。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionsFeedValue {

    /** 题目列表 */
    @JsonProperty("Questions")
    private List<QuestionsFeedQuestion> questions;
}

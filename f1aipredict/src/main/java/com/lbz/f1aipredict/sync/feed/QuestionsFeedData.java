package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * questions_{gamedayId}_en.json 的 Data 节点，Value 为题目包装对象。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionsFeedData {

    /** 题目包装对象；缺失时同步仍记 SUCCESS，不做业务写入 */
    @JsonProperty("Value")
    private QuestionsFeedValue value;
}

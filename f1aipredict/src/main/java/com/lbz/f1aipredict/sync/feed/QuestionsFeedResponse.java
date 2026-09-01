package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * questions_{gamedayId}_en.json 根对象。Feed 使用 PascalCase，未知字段忽略。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionsFeedResponse {

    /** Feed 数据包装层 */
    @JsonProperty("Data")
    private QuestionsFeedData data;
}

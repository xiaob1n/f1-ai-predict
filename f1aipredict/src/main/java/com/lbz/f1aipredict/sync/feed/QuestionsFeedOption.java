package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * questions_{gamedayId}_en.json 中的单个选项。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionsFeedOption {

    /** 选项来源 ID */
    @JsonProperty("Id")
    private Integer id;

    /** 选项文本 */
    @JsonProperty("Value")
    private String value;

    /** 积分（字符串形式，需解析为 Integer） */
    @JsonProperty("Points")
    private String points;

    /** 预测概率（字符串形式，按源值存为 BigDecimal） */
    @JsonProperty("Chance")
    private String chance;
}

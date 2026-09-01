package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * limits/constraints.json 的 Data 节点，Value 为当前轮次约束对象。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LimitsFeedData {

    /** 当前轮次约束；缺失时无法解析 gamedayId */
    @JsonProperty("Value")
    private LimitsFeedValue value;
}

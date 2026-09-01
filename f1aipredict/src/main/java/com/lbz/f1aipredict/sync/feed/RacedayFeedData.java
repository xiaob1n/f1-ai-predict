package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * raceday_en.json 的 Data 节点，Value 为 Session 数组。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RacedayFeedData {

    /** Session 列表；缺失或空数组时同步仍记 SUCCESS，不做业务 upsert */
    @JsonProperty("Value")
    private List<RacedayFeedSession> value;
}

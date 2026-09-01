package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * limits/constraints.json 根对象。Feed 使用 PascalCase，未知字段忽略。
 * 根级 GamedayId / CurrentGamedayId / currentGamedayId / gamedayId 仅作回退。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LimitsFeedResponse {

    /** Feed 数据包装层，优先从 Data.Value.GamedayId 取当前比赛日 */
    @JsonProperty("Data")
    private LimitsFeedData data;

    /** 根级回退：GamedayId */
    @JsonProperty("GamedayId")
    private Integer gamedayId;

    /** 根级回退：CurrentGamedayId */
    @JsonProperty("CurrentGamedayId")
    private Integer currentGamedayIdPascal;

    /** 根级回退：currentGamedayId */
    @JsonProperty("currentGamedayId")
    private Integer currentGamedayIdCamel;

    /** 根级回退：gamedayId */
    @JsonProperty("gamedayId")
    private Integer gamedayIdCamel;
}

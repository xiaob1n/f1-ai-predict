package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * limits/constraints.json 的 Data.Value 节点。
 * 主键字段为官方 PascalCase {@code GamedayId}；其余为兼容回退。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LimitsFeedValue {

    /** 当前比赛日 ID（官方字段，优先使用） */
    @JsonProperty("GamedayId")
    private Integer gamedayId;

    /** 兼容回退：CurrentGamedayId */
    @JsonProperty("CurrentGamedayId")
    private Integer currentGamedayIdPascal;

    /** 兼容回退：currentGamedayId */
    @JsonProperty("currentGamedayId")
    private Integer currentGamedayIdCamel;

    /** 兼容回退：gamedayId */
    @JsonProperty("gamedayId")
    private Integer gamedayIdCamel;
}

package com.lbz.f1aipredict.scoring.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 人工修订官方答案；修订前的当前态会写入历史表。 */
@Getter
@Setter
public class AnswerUpdateRequest {

    @JsonProperty("answerContent")
    private String answerContent;

    @JsonProperty("officialPoints")
    private BigDecimal officialPoints;

    /** 官方发布时间(UTC)；仅在显式给出时写入，缺省则保留原值（可为 NULL），不会回填当前时间。 */
    @JsonProperty("publishedAt")
    private Instant publishedAt;
}

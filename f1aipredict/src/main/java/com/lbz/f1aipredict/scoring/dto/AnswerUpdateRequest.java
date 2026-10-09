package com.lbz.f1aipredict.scoring.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.lbz.f1aipredict.prediction.dto.jackson.OffsetInstantDeserializer;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 人工修订官方答案；修订前的当前态会写入历史表。REST JSON 键为 snake_case。 */
@Getter
@Setter
public class AnswerUpdateRequest {

    /** 答案 JSON 文本，必须是非空 JSON 数组或对象，由服务端校验。 */
    @JsonProperty("answer_content")
    private String answerContent;

    /** 与 official_answer.official_points 的 DECIMAL(8,2) 一致。 */
    @JsonProperty("official_points")
    @Digits(integer = 6, fraction = 2)
    private BigDecimal officialPoints;

    /** 官方发布时间(UTC)；必须带时区偏移，仅在显式给出时写入，缺省则保留原值（可为 NULL），不会回填当前时间。 */
    @JsonProperty("published_at")
    @JsonDeserialize(using = OffsetInstantDeserializer.class)
    private Instant publishedAt;
}

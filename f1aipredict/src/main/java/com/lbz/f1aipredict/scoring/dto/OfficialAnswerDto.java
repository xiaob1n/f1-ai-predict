package com.lbz.f1aipredict.scoring.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lbz.f1aipredict.scoring.entity.OfficialAnswer;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 官方答案当前态的 REST 响应；JSON 键为 snake_case。
 * first_observed_at 对应 official_answer.created_at（本系统首次观察到当前答案行的时间），不是官方发布时间。
 */
public record OfficialAnswerDto(
        @JsonProperty("question_id") Long questionId,
        @JsonProperty("gameday_id") Integer gamedayId,
        @JsonProperty("answer_content") String answerContent,
        @JsonProperty("official_points") BigDecimal officialPoints,
        @JsonProperty("published_at") Instant publishedAt,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("synced_at") Instant syncedAt,
        @JsonProperty("first_observed_at") Instant firstObservedAt,
        @JsonProperty("updated_at") Instant updatedAt) {

    /** 由答案实体转换。 */
    public static OfficialAnswerDto from(OfficialAnswer answer) {
        return new OfficialAnswerDto(answer.getQuestionId(), answer.getGamedayId(), answer.getAnswerContent(),
                answer.getOfficialPoints(), answer.getPublishedAt(), answer.getContentHash(), answer.getSyncedAt(),
                answer.getCreatedAt(), answer.getUpdatedAt());
    }
}

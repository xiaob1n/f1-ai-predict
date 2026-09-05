package com.lbz.f1aipredict.season.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 分站 Session 对外 DTO。
 * <p>
 * 对应 OpenF1 meeting/session 的只读展示形态。
 * 时间字段为 UTC Instant，序列化为带 Z 的 ISO-8601；不暴露 rawJson 或审计列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingSessionDto {

    /** Session 主键 */
    @JsonProperty("id")
    private Long id;

    /** 所属分站主键 */
    @JsonProperty("roundId")
    private Long roundId;

    /** OpenF1 meeting_key */
    @JsonProperty("meetingKey")
    private Integer meetingKey;

    /** OpenF1 session_key */
    @JsonProperty("sessionKey")
    private Integer sessionKey;

    /** Session 名称，如 Practice 1 / Qualifying / Race */
    @JsonProperty("sessionName")
    private String sessionName;

    /** Session 类型，如 Practice / Qualifying / Race */
    @JsonProperty("sessionType")
    private String sessionType;

    /** F1 Predict 比赛日 ID，可空 */
    @JsonProperty("gamedayId")
    private Integer gamedayId;

    /** 开始时间（UTC Instant） */
    @JsonProperty("startDateUtc")
    private Instant startDateUtc;

    /** 结束时间（UTC Instant） */
    @JsonProperty("endDateUtc")
    private Instant endDateUtc;

    /** Session 状态，如 SCHEDULED / IN_PROGRESS / FINISHED */
    @JsonProperty("status")
    private String status;
}

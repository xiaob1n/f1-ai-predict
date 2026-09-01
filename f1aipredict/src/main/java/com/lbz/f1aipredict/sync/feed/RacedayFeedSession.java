package com.lbz.f1aipredict.sync.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * raceday_en.json 中的单条 Session。
 * 分组键是 MeetingId（缺省回退 MeetingNumber），禁止用 RaceId 当 round。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RacedayFeedSession {

    /** Feed 内部 RaceId，仅作溯源，不得当作 round_number */
    @JsonProperty("RaceId")
    private Integer raceId;

    /** F1 Predict 比赛日 ID */
    @JsonProperty("GamedayId")
    private Integer gamedayId;

    /** 赛季年份字符串，如 "2026" */
    @JsonProperty("Season")
    private String season;

    /** OpenF1 session_key 字符串；空白时跳过该行 */
    @JsonProperty("FOMMEETINGSESSIONKEY")
    private String fomMeetingSessionKey;

    /** Session 名称 */
    @JsonProperty("SessionName")
    private String sessionName;

    /** Session 类型 */
    @JsonProperty("SessionType")
    private String sessionType;

    /** Session 开始时间（带偏移的 ISO-8601） */
    @JsonProperty("SessionStartDateISO8601")
    private String sessionStartDateIso8601;

    /** Session 结束时间（带偏移的 ISO-8601） */
    @JsonProperty("SessionEndDateISO8601")
    private String sessionEndDateIso8601;

    /** 会议 ID：同一 Grand Prix 下多 Session 共享，用作分组主键 */
    @JsonProperty("MeetingId")
    private Integer meetingId;

    /** 分站序号，写入 round.round_number；MeetingId 缺失时作为分组回退键 */
    @JsonProperty("MeetingNumber")
    private Integer meetingNumber;

    /** 大奖赛名称 */
    @JsonProperty("MeetingName")
    private String meetingName;

    /** 官方全名 */
    @JsonProperty("MeetingOfficialName")
    private String meetingOfficialName;

    /** 比赛城市 */
    @JsonProperty("MeetingLocation")
    private String meetingLocation;

    /** 国家 */
    @JsonProperty("CountryName")
    private String countryName;

    /** 赛道官方名称 */
    @JsonProperty("CircuitOfficialName")
    private String circuitOfficialName;

    /** 是否奖励轮，本任务不入库 */
    @JsonProperty("IsBonusRound")
    private Integer isBonusRound;
}

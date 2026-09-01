package com.lbz.f1aipredict.season.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.Instant;

/**
 * 分站下的会议/Session 实体，映射 meeting_session 表。
 * 对应 OpenF1 meeting/session；session_key 为 SQL 唯一键 uk_session_key。
 * DATETIME(3) 映射 Instant；INT UNSIGNED 映射 Integer。不额外增加列。
 */
@Getter
@Setter
@TableName("meeting_session")
public class MeetingSession {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属分站 id（逻辑关联 round.id） */
    @TableField("round_id")
    private Long roundId;

    /** OpenF1 meeting_key（INT UNSIGNED → Integer） */
    @TableField("meeting_key")
    private Integer meetingKey;

    /** OpenF1 session_key（INT UNSIGNED → Integer；唯一键 uk_session_key） */
    @TableField("session_key")
    private Integer sessionKey;

    /** Session 名称，如 Practice 1 / Qualifying / Race */
    @TableField("session_name")
    private String sessionName;

    /** Session 类型，如 Practice/Qualifying/Race */
    @TableField("session_type")
    private String sessionType;

    /** F1 Predict 当前比赛 gamedayId（INT UNSIGNED → Integer） */
    @TableField("gameday_id")
    private Integer gamedayId;

    /** 开始时间(UTC)，DATETIME(3) → Instant */
    @TableField("start_date_utc")
    private Instant startDateUtc;

    /** 结束时间(UTC)，DATETIME(3) → Instant */
    @TableField("end_date_utc")
    private Instant endDateUtc;

    /** 状态：SCHEDULED/IN_PROGRESS/FINISHED，默认 SCHEDULED */
    @TableField("status")
    private String status;

    /** 创建时间(UTC)，DATETIME(3) → Instant */
    @TableField("created_at")
    private Instant createdAt;

    /** 更新时间(UTC)，DATETIME(3) → Instant */
    @TableField("updated_at")
    private Instant updatedAt;
}

package com.lbz.f1aipredict.season.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 分站实体，映射 round 表（一个 Grand Prix 轮次）。
 * 对应 sql/002_season_round.sql：同一赛季下 round_number 唯一（uk_round_season_no）。
 * 不将每个 RaceId 当作独立分站；一个分站可包含多个 MeetingSession。
 */
@Getter
@Setter
@TableName("round")
public class Round {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 赛季 id（逻辑关联 season.id，不建物理外键；唯一键组合列） */
    @TableField("season_id")
    private Long seasonId;

    /** 分站序号（SMALLINT UNSIGNED → Integer；唯一键组合列） */
    @TableField("round_number")
    private Integer roundNumber;

    /** 大奖赛名称，如 Monaco Grand Prix */
    @TableField("grand_prix_name")
    private String grandPrixName;

    /** 官方全名 */
    @TableField("official_name")
    private String officialName;

    /** 赛道名称 */
    @TableField("circuit_name")
    private String circuitName;

    /** 国家/地区 */
    @TableField("country")
    private String country;

    /** 比赛城市 */
    @TableField("locality")
    private String locality;

    /** 分站开始日期(UTC)，DATE → LocalDate */
    @TableField("start_date")
    private LocalDate startDate;

    /** 分站结束日期(UTC)，DATE → LocalDate */
    @TableField("end_date")
    private LocalDate endDate;

    /** 状态：SCHEDULED/IN_PROGRESS/FINISHED/CANCELLED，默认 SCHEDULED */
    @TableField("status")
    private String status;

    /** 创建时间(UTC)，DATETIME(3) → Instant */
    @TableField("created_at")
    private Instant createdAt;

    /** 更新时间(UTC)，DATETIME(3) → Instant */
    @TableField("updated_at")
    private Instant updatedAt;
}

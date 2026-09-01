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
 * 赛季实体，映射 season 表。
 * 对应 sql/002_season_round.sql：年份唯一（uk_season_year），
 * 状态默认 UPCOMING。DATE 映射 LocalDate，DATETIME(3) 映射 Instant。
 * 唯一键约束在 SQL 层，Java 侧仅映射 year 列，不伪造唯一键异常测试。
 */
@Getter
@Setter
@TableName("season")
public class Season {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 赛季年份（SMALLINT UNSIGNED → Integer；唯一键 uk_season_year） */
    @TableField("year")
    private Integer year;

    /** 赛季名称，如 2026 FIA Formula One World Championship */
    @TableField("name")
    private String name;

    /** 状态：UPCOMING/IN_PROGRESS/FINISHED，默认 UPCOMING */
    @TableField("status")
    private String status;

    /** 赛季开始日期(UTC)，DATE → LocalDate */
    @TableField("start_date")
    private LocalDate startDate;

    /** 赛季结束日期(UTC)，DATE → LocalDate */
    @TableField("end_date")
    private LocalDate endDate;

    /** 创建时间(UTC)，DATETIME(3) → Instant */
    @TableField("created_at")
    private Instant createdAt;

    /** 更新时间(UTC)，DATETIME(3) → Instant */
    @TableField("updated_at")
    private Instant updatedAt;
}

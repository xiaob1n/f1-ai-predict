package com.lbz.f1aipredict.season.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 赛季对外 DTO。
 * <p>
 * 字段与查询接口展示形态一一对应，JSON 键为 camelCase。
 * 不暴露 createdAt/updatedAt 等审计列，也不包含预测/评分字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeasonDto {

    /** 赛季主键 */
    @JsonProperty("id")
    private Long id;

    /** 赛季年份 */
    @JsonProperty("year")
    private Integer year;

    /** 赛季名称 */
    @JsonProperty("name")
    private String name;

    /** 赛季状态，如 IN_PROGRESS / UPCOMING / FINISHED */
    @JsonProperty("status")
    private String status;

    /** 赛季开始日期（UTC，DATE → LocalDate） */
    @JsonProperty("startDate")
    private LocalDate startDate;

    /** 赛季结束日期（UTC，DATE → LocalDate） */
    @JsonProperty("endDate")
    private LocalDate endDate;
}

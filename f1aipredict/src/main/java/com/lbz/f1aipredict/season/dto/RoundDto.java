package com.lbz.f1aipredict.season.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 分站对外 DTO。
 * <p>
 * 对应一个 Grand Prix 轮次，不含 Session 列表（当前分站聚合见 {@link CurrentRoundDto}）。
 * JSON 键为 camelCase，不暴露审计时间。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoundDto {

    /** 分站主键 */
    @JsonProperty("id")
    private Long id;

    /** 所属赛季主键 */
    @JsonProperty("seasonId")
    private Long seasonId;

    /** 分站序号（MeetingNumber，不是 RaceId） */
    @JsonProperty("roundNumber")
    private Integer roundNumber;

    /** 大奖赛名称 */
    @JsonProperty("grandPrixName")
    private String grandPrixName;

    /** 官方全名 */
    @JsonProperty("officialName")
    private String officialName;

    /** 赛道名称 */
    @JsonProperty("circuitName")
    private String circuitName;

    /** 国家/地区 */
    @JsonProperty("country")
    private String country;

    /** 比赛城市 */
    @JsonProperty("locality")
    private String locality;

    /** 分站开始日期（UTC） */
    @JsonProperty("startDate")
    private LocalDate startDate;

    /** 分站结束日期（UTC） */
    @JsonProperty("endDate")
    private LocalDate endDate;

    /** 分站状态，如 SCHEDULED / IN_PROGRESS / FINISHED / CANCELLED */
    @JsonProperty("status")
    private String status;
}

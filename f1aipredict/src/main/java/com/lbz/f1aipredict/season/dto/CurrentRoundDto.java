package com.lbz.f1aipredict.season.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前分站聚合 DTO。
 * <p>
 * 同时携带分站、所属赛季、Session 列表以及从 Session 推导出的 gamedayId。
 * sessions 默认空列表，避免未附带时序列化为 null。
 * 不包含预测截止、锁定或评分完成字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentRoundDto {

    /** 当前分站基本信息 */
    @JsonProperty("round")
    private RoundDto round;

    /** 所属赛季 */
    @JsonProperty("season")
    private SeasonDto season;

    /** 当前分站下的 Session 列表，默认空列表 */
    @JsonProperty("sessions")
    @Builder.Default
    private List<MeetingSessionDto> sessions = new ArrayList<>();

    /**
     * 从该分站 Session 按 startDateUtc ASC（空值最后）、id ASC 选取的第一个非空 gamedayId。
     * 没有则为 JSON null。
     */
    @JsonProperty("gamedayId")
    private Integer gamedayId;
}

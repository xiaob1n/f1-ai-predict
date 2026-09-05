package com.lbz.f1aipredict.season.service;

import com.lbz.f1aipredict.season.dto.CurrentRoundDto;
import com.lbz.f1aipredict.season.dto.RoundDto;

import java.util.List;

/**
 * 分站只读查询服务契约。
 * <p>
 * 当前分站返回包含所属赛季与 Session 的聚合 DTO；普通详情和列表只返回分站 DTO。
 */
public interface RoundService {

    /**
     * 按状态和 UTC 时间确定当前分站并返回完整聚合信息。
     *
     * @return 当前分站聚合 DTO
     */
    CurrentRoundDto getCurrentRound();

    /**
     * 按主键查询分站详情。
     *
     * @param roundId 分站主键
     * @return 分站 DTO
     */
    RoundDto getById(Long roundId);

    /**
     * 先确认赛季存在，再列出该赛季下的全部分站。
     *
     * @param seasonId 赛季主键
     * @return 按 Mapper 契约稳定排序的分站列表，无数据返回空列表
     */
    List<RoundDto> listBySeasonId(Long seasonId);
}

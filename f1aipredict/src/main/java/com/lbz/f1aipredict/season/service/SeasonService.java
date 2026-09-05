package com.lbz.f1aipredict.season.service;

import com.lbz.f1aipredict.season.dto.SeasonDto;
import com.lbz.f1aipredict.season.dto.SeasonPageDto;
import com.lbz.f1aipredict.season.dto.SeasonQuery;

/**
 * 赛季只读查询服务（接口契约）。
 * <p>
 * 对外暴露当前赛季选择与分页列表；只读、不写库、不访问 Feed。
 * 返回 DTO 而非实体。调用方只依赖本接口，不感知 Mapper 与时钟实现。
 */
public interface SeasonService {

    /**
     * 按 UTC 时钟选择当前赛季。
     * <p>
     * 优先级：{@code IN_PROGRESS}（{@code year DESC, id DESC}）→
     * 当前 UTC 年份（同样降序决胜）→ 最近未来年份（{@code year ASC, id ASC}）。
     * 历史赛季不是候选；均无命中时抛出
     * {@code ResourceNotFoundException("Current season not found")}。
     *
     * @return 当前赛季 DTO
     */
    SeasonDto getCurrentSeason();

    /**
     * 分页查询赛季列表。
     * <p>
     * query 可空；page 为 0-based，size 默认 20、上限 100。
     * 可选 status 做字符串等值过滤，未知值由 Mapper 返回空页而不是 400。
     * 稳定排序 {@code year DESC, id DESC}。
     *
     * @param query 过滤与分页条件，可空
     * @return 分页 DTO（items 非 null）
     */
    SeasonPageDto listSeasons(SeasonQuery query);
}

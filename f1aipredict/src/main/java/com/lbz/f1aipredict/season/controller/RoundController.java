package com.lbz.f1aipredict.season.controller;

import com.lbz.f1aipredict.season.dto.CurrentRoundDto;
import com.lbz.f1aipredict.season.dto.RoundDto;
import com.lbz.f1aipredict.season.service.RoundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分站只读查询接口控制器。
 * <p>
 * 当前分站返回聚合 {@link CurrentRoundDto}；普通详情和赛季分站列表只返回 {@link RoundDto}。
 * 数字路径一律加 {@code \\d+} 约束，{@code /current} 用字面量，避免把 {@code current}/{@code foo}
 * 当成 Long 做类型转换。本类不声明 {@code /rounds/{roundId}/questions}，该路由仍由 QuestionController 独占。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoundController {

    /** 分站只读查询服务，构造器注入 */
    private final RoundService roundService;

    /**
     * 查询当前分站聚合（GET /api/v1/rounds/current）。
     * <p>
     * 字面量路由，不能被 {@code /{roundId:\\d+}} 捕获。
     *
     * @return 当前分站聚合 DTO（round / season / sessions / gamedayId）
     */
    @GetMapping("/rounds/current")
    public CurrentRoundDto getCurrent() {
        return roundService.getCurrentRound();
    }

    /**
     * 查询分站详情（GET /api/v1/rounds/{roundId}）。
     * <p>
     * {@code \\d+} 保证非数字路径（如 {@code /rounds/foo}）不会进入方法，
     * 也不会触发 Long 类型转换异常，由 Spring 按无匹配路由返回 404。
     *
     * @param roundId 分站主键（仅数字）
     * @return 普通分站 DTO，不含 Session 聚合
     */
    @GetMapping("/rounds/{roundId:\\d+}")
    public RoundDto getById(@PathVariable Long roundId) {
        return roundService.getById(roundId);
    }

    /**
     * 查询赛季下的全部分站（GET /api/v1/seasons/{seasonId}/rounds）。
     * <p>
     * 父赛季存在性由 {@link RoundService#listBySeasonId(Long)} 确认：
     * 父缺失 404，父存在但无子分站返回空列表。
     *
     * @param seasonId 赛季主键（仅数字）
     * @return 分站 DTO 列表，无包装
     */
    @GetMapping("/seasons/{seasonId:\\d+}/rounds")
    public List<RoundDto> listBySeasonId(@PathVariable Long seasonId) {
        return roundService.listBySeasonId(seasonId);
    }
}

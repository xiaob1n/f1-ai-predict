package com.lbz.f1aipredict.season.controller;

import com.lbz.f1aipredict.season.dto.SeasonDto;
import com.lbz.f1aipredict.season.dto.SeasonPageDto;
import com.lbz.f1aipredict.season.dto.SeasonQuery;
import com.lbz.f1aipredict.season.service.SeasonService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 赛季只读查询接口控制器。
 * <p>
 * 暴露当前赛季与分页列表两条 GET 路由。{@code /current} 必须是字面量路径，
 * 不能被数字 ID 变量路由吞掉。只依赖 {@link SeasonService} 接口、只返回 DTO，
 * 不访问 Mapper / Feed，也不新增错误体（404 由全局异常处理器映射）。
 */
@RestController
@RequestMapping("/api/v1/seasons")
@RequiredArgsConstructor
public class SeasonController {

    /** 赛季只读查询服务，构造器注入 */
    private final SeasonService seasonService;

    /**
     * 查询当前赛季（GET /api/v1/seasons/current）。
     * <p>
     * 字面量路由优先于任何潜在的 {@code /{seasonId}} 映射，
     * 避免把 {@code current} 当成数字主键做类型转换。
     *
     * @return 当前赛季 DTO，无包装
     */
    @GetMapping("/current")
    public SeasonDto getCurrent() {
        return seasonService.getCurrentSeason();
    }

    /**
     * 分页查询赛季列表（GET /api/v1/seasons）。
     * <p>
     * status / page / size 通过 {@link ModelAttribute} 绑定到单个 {@link SeasonQuery}。
     * 进入服务前先 {@link SeasonQuery#clampPaging()}：page 0-based 默认 0，
     * size 默认 20、上限 100。未知 status 原样转发，由服务返回空页而不是 400。
     *
     * @param query GET 查询条件 DTO
     * @return 分页 DTO（items 非 null），无包装
     */
    @GetMapping
    public SeasonPageDto list(@ModelAttribute SeasonQuery query) {
        query.clampPaging();
        return seasonService.listSeasons(query);
    }
}

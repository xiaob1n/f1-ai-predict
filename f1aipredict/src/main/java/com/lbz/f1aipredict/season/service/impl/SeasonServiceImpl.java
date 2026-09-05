package com.lbz.f1aipredict.season.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.season.dto.SeasonDto;
import com.lbz.f1aipredict.season.dto.SeasonPageDto;
import com.lbz.f1aipredict.season.dto.SeasonQuery;
import com.lbz.f1aipredict.season.entity.Season;
import com.lbz.f1aipredict.season.mapper.SeasonMapper;
import com.lbz.f1aipredict.season.service.SeasonService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 赛季只读查询服务实现。
 * <p>
 * 当前赛季一次 {@code selectList} 后在内存按 UTC 优先级筛选；
 * 分页一次 {@code selectPage}，禁止循环访问 Mapper，也不依赖 Feed。
 */
@Slf4j
@Service
public class SeasonServiceImpl implements SeasonService {

    /** 进行中赛季状态，优先于年份回退。 */
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";

    private final SeasonMapper seasonMapper;
    private final Clock clock;

    /**
     * 构造器注入 Mapper 与共享 UTC Clock（由 {@code TimeConfig} 提供）。
     * 禁止在方法内调用 {@code Clock.systemUTC()} / {@code Instant.now()} / {@code LocalDate.now()}。
     */
    public SeasonServiceImpl(SeasonMapper seasonMapper, Clock clock) {
        this.seasonMapper = seasonMapper;
        this.clock = clock;
    }

    /**
     * 一次读取全部候选后按固定优先级选择当前赛季，历史年份永不回退。
     */
    @Override
    public SeasonDto getCurrentSeason() {
        int currentYear = LocalDate.now(clock).getYear();
        log.debug("查询当前赛季: utcYear={}", currentYear);
        List<Season> candidates = seasonMapper.selectList(new QueryWrapper<>());
        if (candidates == null || candidates.isEmpty()) {
            log.warn("当前赛季候选为空");
            throw new ResourceNotFoundException("Current season not found");
        }

        // 1) 进行中优先：并列按 year DESC, id DESC，避免同一状态多条时抖动。
        Season selected = firstInProgress(candidates);
        if (selected == null) {
            // 2) 无进行中则取当前 UTC 年份，同样降序决胜；FINISHED 当前年仍可命中。
            selected = firstCurrentYear(candidates, currentYear);
        }
        if (selected == null) {
            // 3) 再取最近未来（year > 当前年），year ASC, id ASC；不回退历史年。
            selected = firstNearestFuture(candidates, currentYear);
        }
        if (selected == null) {
            log.warn("当前赛季无进行中/当前年/未来候选: utcYear={}", currentYear);
            throw new ResourceNotFoundException("Current season not found");
        }
        log.debug("当前赛季命中: id={}, year={}, status={}",
                selected.getId(), selected.getYear(), selected.getStatus());
        return toSeasonDto(selected);
    }

    /**
     * 裁剪分页后构造 MP Page（current = API page + 1），可选 status 等值过滤。
     */
    @Override
    public SeasonPageDto listSeasons(SeasonQuery query) {
        SeasonQuery effectiveQuery = query == null ? new SeasonQuery() : query;
        effectiveQuery.clampPaging();
        int page = effectiveQuery.getPage();
        int size = effectiveQuery.getSize();
        log.debug("分页查询赛季: status={}, page={}, size={}",
                effectiveQuery.getStatus(), page, size);

        Page<Season> mpPage = new Page<>((long) page + 1, size);
        QueryWrapper<Season> wrapper = new QueryWrapper<>();
        String status = effectiveQuery.getStatus();
        // 未知 status 仍作为等值谓词下推，由 Mapper 返回空页，Service 不校验白名单。
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("year").orderByDesc("id");

        Page<Season> result = seasonMapper.selectPage(mpPage, wrapper);
        List<Season> records = result.getRecords();
        List<SeasonDto> items = records == null
                ? new ArrayList<>()
                : records.stream().map(this::toSeasonDto).toList();

        SeasonPageDto dto = new SeasonPageDto();
        dto.setItems(items);
        dto.setPage(page);
        dto.setSize(size);
        dto.setTotal(result.getTotal());
        log.debug("赛季分页完成: page={}, size={}, total={}, rows={}",
                page, size, dto.getTotal(), items.size());
        return dto;
    }

    /**
     * 从候选中取 IN_PROGRESS，year DESC, id DESC。
     */
    private static Season firstInProgress(List<Season> candidates) {
        return candidates.stream()
                .filter(season -> STATUS_IN_PROGRESS.equals(season.getStatus()))
                .min(yearIdDescending())
                .orElse(null);
    }

    /**
     * 从候选中取当前 UTC 年份，year DESC, id DESC。
     */
    private static Season firstCurrentYear(List<Season> candidates, int currentYear) {
        return candidates.stream()
                .filter(season -> Objects.equals(season.getYear(), currentYear))
                .min(yearIdDescending())
                .orElse(null);
    }

    /**
     * 从候选中取最近未来年份，year ASC, id ASC。
     */
    private static Season firstNearestFuture(List<Season> candidates, int currentYear) {
        return candidates.stream()
                .filter(season -> season.getYear() != null && season.getYear() > currentYear)
                .min(yearIdAscending())
                .orElse(null);
    }

    /**
     * year DESC, id DESC；空值排后，保证决胜稳定。
     */
    private static Comparator<Season> yearIdDescending() {
        return Comparator.comparing(Season::getYear, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Season::getId, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /**
     * year ASC, id ASC；空值排后。
     */
    private static Comparator<Season> yearIdAscending() {
        return Comparator.comparing(Season::getYear, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Season::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * 将赛季实体显式映射为公开 DTO，不暴露 createdAt/updatedAt。
     */
    private SeasonDto toSeasonDto(Season season) {
        SeasonDto dto = new SeasonDto();
        dto.setId(season.getId());
        dto.setYear(season.getYear());
        dto.setName(season.getName());
        dto.setStatus(season.getStatus());
        dto.setStartDate(season.getStartDate());
        dto.setEndDate(season.getEndDate());
        return dto;
    }
}

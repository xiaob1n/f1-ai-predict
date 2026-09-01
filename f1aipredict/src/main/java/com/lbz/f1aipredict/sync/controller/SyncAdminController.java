package com.lbz.f1aipredict.sync.controller;

import com.lbz.f1aipredict.sync.dto.RawPayloadDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordPageDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordQuery;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.service.FeedSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feed 同步管理端控制器。
 * <p>
 * 暴露手动触发赛程 / limits / 题目 / 当前轮次同步，以及同步记录分页与原始 JSON 查询。
 * 全部委托 {@link FeedSyncService}，返回 DTO 而非实体；本类不创建 WebClient。
 * <p>
 * v1 未将 springdoc-openapi 放入 classpath，因此不添加 {@code @Operation} 等 OpenAPI 注解
 * （加上也无法编译）。各端点以本类中文方法注释为文档来源。
 */
@RestController
@RequestMapping("/api/v1/admin/sync")
@RequiredArgsConstructor
public class SyncAdminController {

    /** Feed 同步服务，构造器注入 */
    private final FeedSyncService feedSyncService;

    /**
     * 手动触发赛程同步（POST /api/v1/admin/sync/schedule）。
     *
     * @return 赛程同步结果 DTO
     */
    @PostMapping("/schedule")
    public SyncResultDto syncSchedule() {
        return feedSyncService.syncSchedule();
    }

    /**
     * 仅拉取并留档 limits（POST /api/v1/admin/sync/limits）。
     * <p>
     * 不串行执行赛程 / 题目同步。
     *
     * @return limits 同步结果 DTO（含解析出的 gamedayId）
     */
    @PostMapping("/limits")
    public SyncResultDto syncLimits() {
        return feedSyncService.syncLimits();
    }

    /**
     * 手动触发指定比赛日题目同步（POST /api/v1/admin/sync/questions/{gamedayId}）。
     *
     * @param gamedayId 比赛日 ID
     * @return 题目同步结果 DTO
     */
    @PostMapping("/questions/{gamedayId}")
    public SyncResultDto syncQuestions(@PathVariable Integer gamedayId) {
        return feedSyncService.syncQuestions(gamedayId);
    }

    /**
     * 手动触发当前轮次串联同步（POST /api/v1/admin/sync/current）。
     *
     * @return 汇总结果 DTO（sourceType=CURRENT）
     */
    @PostMapping("/current")
    public SyncResultDto syncCurrent() {
        return feedSyncService.syncCurrent();
    }

    /**
     * 分页查询同步记录（GET /api/v1/admin/sync/records）。
     * <p>
     * page 0-based；size 默认 20、最小 1、最大 100。进入服务前先裁剪，避免 size=200 打到数据库。
     *
     * @param query 过滤与分页条件（sourceType / gamedayId / status / page / size）
     * @return 分页 DTO，不含实体
     */
    @GetMapping("/records")
    public SyncRecordPageDto pageRecords(@ModelAttribute SyncRecordQuery query) {
        query.clampPaging();
        return feedSyncService.pageRecords(query);
    }

    /**
     * 按主键读取原始 Feed JSON（GET /api/v1/admin/sync/raw-payloads/{payloadId}）。
     *
     * @param payloadId 留档主键
     * @return 原始响应 DTO
     */
    @GetMapping("/raw-payloads/{payloadId}")
    public RawPayloadDto getRawPayload(@PathVariable Long payloadId) {
        return feedSyncService.getRawPayload(payloadId);
    }
}

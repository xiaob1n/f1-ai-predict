package com.lbz.f1aipredict.sync.schedule;

import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.service.FeedSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每小时最新问题同步调度器。
 * <p>
 * 仅编排：每轮调用一次已有 {@link FeedSyncService#syncCurrent()}，
 * 不解析 gamedayId、不访问 Feed 客户端、不写数据库。
 * 由 {@code f1predict.sync.scheduler.enabled} 控制是否创建本 Bean；
 * 缺省视为开启，与生产 YAML 默认值一致。
 */
@Component
@ConditionalOnProperty(
        prefix = "f1predict.sync.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class HourlyQuestionSyncScheduler {

    /** 同步成功 */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /** 内容未变化而跳过写入 */
    private static final String STATUS_SKIPPED_UNCHANGED = "SKIPPED_UNCHANGED";

    /** 同步失败 */
    private static final String STATUS_FAILED = "FAILED";

    private static final Logger log = LoggerFactory.getLogger(HourlyQuestionSyncScheduler.class);

    /** 唯一依赖：复用现有同步编排接口 */
    private final FeedSyncService feedSyncService;

    /**
     * 构造器注入 {@link FeedSyncService}，便于单测用 mock 替换。
     *
     * @param feedSyncService 现有 Feed 同步服务
     */
    public HourlyQuestionSyncScheduler(FeedSyncService feedSyncService) {
        this.feedSyncService = feedSyncService;
    }

    /**
     * 定时拉取并幂等保存最新问题。
     * <p>
     * 使用 {@code initialDelayString}：启动后先等待配置的延迟（默认 PT1H），不会立刻执行。
     * 使用 {@code fixedDelayString}：上一轮（含阻塞式同步）完成后再等待配置间隔（默认 PT1H），
     * 单实例内不会与下一轮重叠。禁止使用 fixedRate。
     */
    @Scheduled(
            initialDelayString = "${f1predict.sync.scheduler.initial-delay:PT1H}",
            fixedDelayString = "${f1predict.sync.scheduler.fixed-delay:PT1H}")
    public void syncLatestQuestions() {
        try {
            // 每轮只调用一次 syncCurrent，由服务内部完成 limits → schedule → questions
            SyncResultDto result = feedSyncService.syncCurrent();
            logSyncResult(result);
        } catch (RuntimeException ex) {
            // 隔离运行时异常，避免打垮调度线程；异常对象放最后参数以打印堆栈
            log.error("每小时最新问题同步发生未处理运行时异常", ex);
        }
    }

    /**
     * 按 status 输出可辨识的参数化日志；不打印原始 Feed JSON。
     * DTO 失败说明字段为 {@code errorMessage}，没有独立 message getter。
     *
     * @param result {@link FeedSyncService#syncCurrent()} 的返回值，可能为 null
     */
    private void logSyncResult(SyncResultDto result) {
        if (result == null) {
            log.warn("每小时最新问题同步返回空结果");
            return;
        }
        String status = result.getStatus();
        if (status == null || status.isBlank()) {
            log.warn("每小时最新问题同步返回无法识别的状态: sourceType={}, status={}, gamedayId={}, errorMessage={}",
                    result.getSourceType(), status, result.getGamedayId(), result.getErrorMessage());
            return;
        }
        if (STATUS_SUCCESS.equals(status)) {
            log.info("每小时最新问题同步成功: sourceType={}, status={}, gamedayId={}, errorMessage={}",
                    result.getSourceType(), status, result.getGamedayId(), result.getErrorMessage());
            return;
        }
        if (STATUS_SKIPPED_UNCHANGED.equals(status)) {
            log.info("每小时最新问题同步跳过（内容未变化）: sourceType={}, status={}, gamedayId={}, errorMessage={}",
                    result.getSourceType(), status, result.getGamedayId(), result.getErrorMessage());
            return;
        }
        if (STATUS_FAILED.equals(status)) {
            log.error("每小时最新问题同步失败: sourceType={}, status={}, gamedayId={}, errorMessage={}",
                    result.getSourceType(), status, result.getGamedayId(), result.getErrorMessage());
            return;
        }
        log.warn("每小时最新问题同步返回无法识别的状态: sourceType={}, status={}, gamedayId={}, errorMessage={}",
                result.getSourceType(), status, result.getGamedayId(), result.getErrorMessage());
    }
}

package com.lbz.f1aipredict.prediction.schedule;

import com.lbz.f1aipredict.common.RequestId;
import com.lbz.f1aipredict.prediction.mapper.PredictionBatchMapper;
import com.lbz.f1aipredict.prediction.service.PredictionLockService;
import com.lbz.f1aipredict.prediction.service.PredictionLockService.LockResult;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预测批次截止自动锁定调度器，默认关闭，由 {@code f1predict.prediction.lock-scheduler.enabled=true} 开启。
 * <p>
 * 每轮按主键 keyset 取至多 batch-limit 个“未锁定且截止已过”的候选，逐个调用现有
 * {@link PredictionLockService#lockBatch}（数据库条件更新 CAS），锁定时间取毫秒精度服务器时间，
 * 由锁定服务保证严格晚于截止。单个批次失败只记录日志，不影响同轮其余批次；
 * 游标每轮前移，取不满一页时回到起点，失败批次不会长期占据第一页。
 * 游标只保存在本实例内存，多实例并发锁定由 CAS 保证只留下首次锁定时刻。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "f1predict.prediction.lock-scheduler", name = "enabled", havingValue = "true")
public class PredictionLockScheduler {

    /** 单轮候选上限的允许范围，防止误配置造成无界扫描。 */
    static final int MAX_BATCH_LIMIT = 500;

    private final PredictionBatchMapper batchMapper;
    private final PredictionLockService lockService;
    private final int batchLimit;
    /** 上一轮处理到的最大批次主键，0 表示从头开始。 */
    private final AtomicLong cursor = new AtomicLong();

    public PredictionLockScheduler(PredictionBatchMapper batchMapper, PredictionLockService lockService,
                                   @Value("${f1predict.prediction.lock-scheduler.batch-limit:50}") int batchLimit) {
        if (batchLimit < 1 || batchLimit > MAX_BATCH_LIMIT) {
            throw new IllegalArgumentException("lock-scheduler batch-limit must be between 1 and " + MAX_BATCH_LIMIT);
        }
        this.batchMapper = batchMapper;
        this.lockService = lockService;
        this.batchLimit = batchLimit;
    }

    /**
     * fixedDelay：上一轮结束后再等待间隔，单实例内轮次不重叠。运行时异常在本方法内隔离，不打垮调度线程。
     */
    @Scheduled(
            initialDelayString = "${f1predict.prediction.lock-scheduler.initial-delay:PT1M}",
            fixedDelayString = "${f1predict.prediction.lock-scheduler.fixed-delay:PT1M}")
    public void lockDueBatches() {
        long startedAt = System.currentTimeMillis();
        MDC.put(RequestId.MDC_KEY, UUID.randomUUID().toString());
        try {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            long after = cursor.get();
            List<Long> batchIds = batchMapper.selectLockCandidateIds(now, after, batchLimit);
            if (batchIds.isEmpty() && after > 0) {
                // 上一页恰好取满后已到末尾，本轮立即从头再取一次，避免空转一整轮。
                after = 0;
                batchIds = batchMapper.selectLockCandidateIds(now, after, batchLimit);
            }
            int locked = 0;
            int failed = 0;
            for (Long batchId : batchIds) {
                try {
                    // 每个批次单独取时钟，保持毫秒精度，锁定服务会再次校验严格晚于截止。
                    LockResult result = lockService.lockBatch(batchId, Instant.now().truncatedTo(ChronoUnit.MILLIS));
                    if (result.newlyLocked()) {
                        locked++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.error("定时锁定预测批次失败: batchId={}, errorType={}", batchId, ex.getClass().getSimpleName(), ex);
                }
            }
            cursor.set(batchIds.size() < batchLimit ? 0L : batchIds.getLast());
            if (!batchIds.isEmpty()) {
                log.info("定时锁定预测批次完成: candidateCount={}, newlyLocked={}, failed={}, durationMs={}",
                        batchIds.size(), locked, failed, elapsedMs(startedAt));
            }
        } catch (RuntimeException ex) {
            log.error("定时锁定预测批次发生未处理运行时异常: durationMs={}", elapsedMs(startedAt), ex);
        } finally {
            MDC.remove(RequestId.MDC_KEY);
        }
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }
}

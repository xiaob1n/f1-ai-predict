package com.lbz.f1aipredict.scoring.schedule;

import com.lbz.f1aipredict.common.RequestId;
import com.lbz.f1aipredict.prediction.mapper.PredictionBatchMapper;
import com.lbz.f1aipredict.scoring.service.AutoScoreResult;
import com.lbz.f1aipredict.scoring.service.ScoringService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自动首次评分调度器，默认关闭，由 {@code f1predict.scoring.auto-score-scheduler.enabled=true} 开启。
 * <p>
 * 候选 SQL 只用于缩小扫描范围；每个候选都调用 {@link ScoringService#tryAutoScoreBatch}，
 * 由其在持有批次行锁的事务内重新核验全部就绪条件。只做首次评分，已有总分不重算。
 * 等待答案的批次会持续留在候选集合中，因此按主键 keyset 轮转游标，避免它们长期占据第一页；
 * 单个批次失败只记录日志，不影响同轮其余批次。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "f1predict.scoring.auto-score-scheduler", name = "enabled", havingValue = "true")
public class AutoScoreScheduler {

    /** 单轮候选上限的允许范围，防止误配置造成无界扫描。 */
    static final int MAX_BATCH_LIMIT = 500;

    private final PredictionBatchMapper batchMapper;
    private final ScoringService scoringService;
    private final int batchLimit;
    /** 上一轮处理到的最大批次主键，0 表示从头开始。 */
    private final AtomicLong cursor = new AtomicLong();

    public AutoScoreScheduler(PredictionBatchMapper batchMapper, ScoringService scoringService,
                              @Value("${f1predict.scoring.auto-score-scheduler.batch-limit:20}") int batchLimit) {
        if (batchLimit < 1 || batchLimit > MAX_BATCH_LIMIT) {
            throw new IllegalArgumentException("auto-score-scheduler batch-limit must be between 1 and " + MAX_BATCH_LIMIT);
        }
        this.batchMapper = batchMapper;
        this.scoringService = scoringService;
        this.batchLimit = batchLimit;
    }

    /** fixedDelay 保证单实例内轮次不重叠；运行时异常在本方法内隔离。 */
    @Scheduled(
            initialDelayString = "${f1predict.scoring.auto-score-scheduler.initial-delay:PT5M}",
            fixedDelayString = "${f1predict.scoring.auto-score-scheduler.fixed-delay:PT5M}")
    public void autoScoreReadyBatches() {
        long startedAt = System.currentTimeMillis();
        MDC.put(RequestId.MDC_KEY, UUID.randomUUID().toString());
        try {
            long after = cursor.get();
            List<Long> batchIds = batchMapper.selectAutoScoreCandidateIds(after, batchLimit);
            if (batchIds.isEmpty() && after > 0) {
                after = 0;
                batchIds = batchMapper.selectAutoScoreCandidateIds(after, batchLimit);
            }
            Map<AutoScoreResult.Decision, Integer> decisions = new EnumMap<>(AutoScoreResult.Decision.class);
            int failed = 0;
            for (Long batchId : batchIds) {
                try {
                    AutoScoreResult result = scoringService.tryAutoScoreBatch(batchId);
                    decisions.merge(result.decision(), 1, Integer::sum);
                    log.debug("自动评分判定: batchId={}, decision={}", batchId, result.decision());
                } catch (RuntimeException ex) {
                    failed++;
                    log.error("自动评分批次失败: batchId={}, errorType={}", batchId, ex.getClass().getSimpleName(), ex);
                }
            }
            cursor.set(batchIds.size() < batchLimit ? 0L : batchIds.getLast());
            if (!batchIds.isEmpty()) {
                log.info("自动评分轮次完成: candidateCount={}, decisions={}, failed={}, durationMs={}",
                        batchIds.size(), decisions, failed, elapsedMs(startedAt));
            }
        } catch (RuntimeException ex) {
            log.error("自动评分发生未处理运行时异常: durationMs={}", elapsedMs(startedAt), ex);
        } finally {
            MDC.remove(RequestId.MDC_KEY);
        }
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }
}

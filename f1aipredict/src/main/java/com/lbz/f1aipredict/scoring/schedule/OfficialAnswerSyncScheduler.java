package com.lbz.f1aipredict.scoring.schedule;

import com.lbz.f1aipredict.common.RequestId;
import com.lbz.f1aipredict.prediction.mapper.PredictionBatchMapper;
import com.lbz.f1aipredict.scoring.service.OfficialAnswerService;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 官方答案定时同步调度器，默认关闭，由 {@code f1predict.scoring.answer-sync-scheduler.enabled=true} 开启。
 * <p>
 * 候选为拥有已锁定预测批次的比赛日，包含已评分批次，以便持续发现官方答案修订并由答案同步归档旧版本；
 * 本调度只同步答案，绝不触发评分或重算。未评分与历史修订各自按 gameday_id keyset 轮转，
 * 每轮优先把约 3/4 配额给未评分比赛日，历史修订至少保留一个配额；batch-limit=1 时四轮中的一轮优先修订。
 * 某组候选不足时另一组可借配额，每轮去重后的 HTTP 次数仍不超过 batch-limit。
 * 单个比赛日失败（Feed 返回 FAILED 或抛异常）不影响同轮其余比赛日，游标轮转避免失败比赛日长期占据第一页。
 * HTTP 拉取在 {@link OfficialAnswerService#syncAnswers} 内发生于事务之外。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "f1predict.scoring.answer-sync-scheduler", name = "enabled", havingValue = "true")
public class OfficialAnswerSyncScheduler {

    /** 单轮比赛日上限的允许范围；每个比赛日是一次外部 HTTP 拉取。 */
    static final int MAX_BATCH_LIMIT = 100;
    private static final String STATUS_FAILED = "FAILED";

    private final PredictionBatchMapper batchMapper;
    private final OfficialAnswerService answerService;
    private final int batchLimit;
    /** 两组独立游标；存在未评分批次的比赛日不与历史修订竞争同一条扫描队列。 */
    private final AtomicInteger pendingCursor = new AtomicInteger();
    private final AtomicInteger revisionCursor = new AtomicInteger();
    /** 单条配额时按三轮未评分、一轮修订轮转，避免任一组永久饥饿。 */
    private final AtomicInteger singleLimitRound = new AtomicInteger();

    public OfficialAnswerSyncScheduler(PredictionBatchMapper batchMapper, OfficialAnswerService answerService,
                                       @Value("${f1predict.scoring.answer-sync-scheduler.batch-limit:10}") int batchLimit) {
        if (batchLimit < 1 || batchLimit > MAX_BATCH_LIMIT) {
            throw new IllegalArgumentException("answer-sync-scheduler batch-limit must be between 1 and " + MAX_BATCH_LIMIT);
        }
        this.batchMapper = batchMapper;
        this.answerService = answerService;
        this.batchLimit = batchLimit;
    }

    /** fixedDelay 保证单实例内轮次不重叠；运行时异常在本方法内隔离。 */
    @Scheduled(
            initialDelayString = "${f1predict.scoring.answer-sync-scheduler.initial-delay:PT10M}",
            fixedDelayString = "${f1predict.scoring.answer-sync-scheduler.fixed-delay:PT30M}")
    public void syncPredictedGamedayAnswers() {
        long startedAt = System.currentTimeMillis();
        MDC.put(RequestId.MDC_KEY, UUID.randomUUID().toString());
        try {
            boolean pendingFirst = batchLimit > 1 || singleLimitRound.getAndUpdate(round -> (round + 1) % 4) != 3;
            int firstQuota = batchLimit == 1 ? 1 : batchLimit - Math.max(1, batchLimit / 4);
            Set<Integer> gamedayIds = new LinkedHashSet<>();
            gamedayIds.addAll(nextPage(pendingFirst, firstQuota, gamedayIds));
            gamedayIds.addAll(nextPage(!pendingFirst, batchLimit - gamedayIds.size(), gamedayIds));
            // 第二组候选不足时，优先组借用剩余配额；查询排除本轮已选比赛日，回到起点也不会重复。
            gamedayIds.addAll(nextPage(pendingFirst, batchLimit - gamedayIds.size(), gamedayIds));
            int failed = 0;
            for (Integer gamedayId : gamedayIds) {
                try {
                    SyncResultDto result = answerService.syncAnswers(gamedayId);
                    if (result == null || STATUS_FAILED.equals(result.getStatus())) {
                        failed++;
                        log.warn("定时官方答案同步失败: gamedayId={}, syncRecordId={}", gamedayId,
                                result == null ? null : result.getSyncRecordId());
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.error("定时官方答案同步异常: gamedayId={}, errorType={}", gamedayId,
                            ex.getClass().getSimpleName(), ex);
                }
            }
            if (!gamedayIds.isEmpty()) {
                log.info("定时官方答案同步完成: gamedayCount={}, failed={}, durationMs={}",
                        gamedayIds.size(), failed, elapsedMs(startedAt));
            }
        } catch (RuntimeException ex) {
            log.error("定时官方答案同步发生未处理运行时异常: durationMs={}", elapsedMs(startedAt), ex);
        } finally {
            MDC.remove(RequestId.MDC_KEY);
        }
    }

    /** 每组最多查询两页（末尾空页时回到起点），排除本轮已选比赛日，保证借配额后仍只调用一次 HTTP。 */
    private List<Integer> nextPage(boolean pending, int limit, Set<Integer> excluded) {
        if (limit == 0) {
            return List.of();
        }
        AtomicInteger cursor = pending ? pendingCursor : revisionCursor;
        int after = cursor.get();
        List<Integer> excludedIds = List.copyOf(excluded);
        List<Integer> ids = batchMapper.selectAnswerSyncGamedayIds(pending, after, limit, excludedIds);
        if (ids.isEmpty() && after > 0) {
            ids = batchMapper.selectAnswerSyncGamedayIds(pending, 0, limit, excludedIds);
        }
        cursor.set(ids.size() < limit ? 0 : ids.getLast());
        return ids;
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }
}

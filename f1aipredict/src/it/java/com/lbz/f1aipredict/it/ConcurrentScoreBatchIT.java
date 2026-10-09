package com.lbz.f1aipredict.it;

import com.lbz.f1aipredict.it.support.ItScenario.ReadyBatch;
import com.lbz.f1aipredict.scoring.entity.BatchTotalScore;
import com.lbz.f1aipredict.scoring.service.ScoringService;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * D6：多线程经真实 Spring 事务代理同时对同一已锁定批次调用 scoreBatch。
 * <p>
 * 断言每个线程都在时限内正常返回同一份总分，库中恰好一套明细与一条总分。
 * 超时与线程异常都会带原因直接失败，不会被吞掉。
 */
class ConcurrentScoreBatchIT extends AbstractDStageIT {

    private static final int THREADS = 8;
    private static final long PER_CALL_TIMEOUT_SECONDS = 60;

    @Autowired
    private ScoringService scoringService;

    @RepeatedTest(3)
    void concurrentScoreBatchLeavesExactlyOneScoreSet() throws InterruptedException {
        ReadyBatch ready = scenario.prepareLockedBatch(List.of(101, 102, 103), List.of(101, 102, 101));
        long batchId = ready.batchId();
        CountDownLatch allReady = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<BatchTotalScore>> futures = new ArrayList<>(THREADS);
        try {
            for (int index = 0; index < THREADS; index++) {
                futures.add(pool.submit(() -> {
                    allReady.countDown();
                    if (!start.await(PER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待起跑信号超时");
                    }
                    return scoringService.scoreBatch(batchId);
                }));
            }
            assertThat(allReady.await(PER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("全部线程应在时限内就位").isTrue();
            start.countDown();

            List<BatchTotalScore> totals = new ArrayList<>(THREADS);
            for (int index = 0; index < futures.size(); index++) {
                try {
                    totals.add(futures.get(index).get(PER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (TimeoutException timeout) {
                    fail("线程 " + index + " 在 " + PER_CALL_TIMEOUT_SECONDS + "s 内未完成 scoreBatch", timeout);
                } catch (ExecutionException error) {
                    fail("线程 " + index + " 的 scoreBatch 抛出异常: " + error.getCause(), error.getCause());
                }
            }

            Long totalRowId = fixtures.jdbc().queryForObject(
                    "SELECT id FROM batch_total_score WHERE batch_id = ?", Long.class, batchId);
            assertThat(totals).hasSize(THREADS).allSatisfy(total -> {
                assertThat(total.getId()).as("所有线程返回同一条总分").isEqualTo(totalRowId);
                assertThat(total.getTotalScore()).isEqualByComparingTo("2");
                assertThat(total.getMaxScore()).isEqualByComparingTo("3");
            });
            assertThat(fixtures.count("SELECT COUNT(*) FROM batch_total_score WHERE batch_id = ?", batchId))
                    .isEqualTo(1);
            assertThat(fixtures.count("SELECT COUNT(*) FROM scoring_detail WHERE batch_id = ?", batchId))
                    .isEqualTo(3);
            assertThat(fixtures.count("SELECT COUNT(DISTINCT result_id) FROM scoring_detail WHERE batch_id = ?",
                    batchId)).isEqualTo(3);
            assertThat(fixtures.count("SELECT COUNT(*) FROM scoring_detail_history WHERE batch_id = ?", batchId))
                    .isZero();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(PER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("评分线程池应能终止").isTrue();
        }
    }
}

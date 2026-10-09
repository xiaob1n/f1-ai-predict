package com.lbz.f1aipredict.it;

import com.lbz.f1aipredict.it.support.DStageItApplication;
import com.lbz.f1aipredict.it.support.ItEnvironment;
import com.lbz.f1aipredict.it.support.ItFixtures;
import com.lbz.f1aipredict.it.support.ItScenario;
import com.lbz.f1aipredict.prediction.inbound.PredictionOutcomePersistenceService.OutcomeApplyResult;
import com.lbz.f1aipredict.prediction.service.PredictionLockService;
import com.lbz.f1aipredict.scoring.entity.BatchTotalScore;
import com.lbz.f1aipredict.scoring.service.ScoringService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2：关闭并重建整个 Spring 上下文（连接池随之关闭）后，锁定时点、已受理结果与接收凭证保持不变，
 * 重复锁定与重复投递保持幂等，锁定后才到达的结果评分为 LATE，首次评分在再次重启后不被重算。
 * <p>
 * 每个阶段使用独立的 SpringApplication 上下文，而不是复用测试上下文缓存。
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class LockResultRestartIT {

    private static final String BATCH_STATE = """
            SELECT status, prediction_deadline, locked_at, lock_version
            FROM prediction_batch WHERE id = ?
            """;
    private static final String JOB_STATE = """
            SELECT id, prediction_job_id, status, completed_at FROM prediction_job WHERE batch_id = ? ORDER BY id
            """;
    private static final String RESULT_STATE = """
            SELECT r.id, r.job_id, r.question_snapshot_id, r.source_data_cutoff, r.generated_at
            FROM prediction_result r JOIN prediction_job j ON j.id = r.job_id WHERE j.batch_id = ? ORDER BY r.id
            """;
    private static final String RECEIPT_STATE = """
            SELECT r.job_id, r.message_id, r.outcome_type, r.payload_sha256, r.received_at
            FROM prediction_outcome_receipt r JOIN prediction_job j ON j.id = r.job_id
            WHERE j.batch_id = ? ORDER BY r.job_id
            """;
    private static final String ITEM_STATE = """
            SELECT i.result_id, i.option_id, i.position FROM prediction_result_item i
            JOIN prediction_result r ON r.id = i.result_id JOIN prediction_job j ON j.id = r.job_id
            WHERE j.batch_id = ? ORDER BY i.result_id, i.position
            """;
    private static final String SCORE_STATE = """
            SELECT d.id, d.result_id, d.score_status, d.score, d.max_score, d.detail_json, d.scored_at
            FROM scoring_detail d WHERE d.batch_id = ? ORDER BY d.id
            """;
    private static final String TOTAL_STATE = """
            SELECT id, total_score, max_score, accuracy_rate, type_stats_json, created_at
            FROM batch_total_score WHERE batch_id = ?
            """;

    @BeforeAll
    static void requireDedicatedDatabase() {
        ItEnvironment.requireDedicatedDatabase();
    }

    @Test
    void lockAndResultsSurviveFullContextRestartAndLateResultScoresLate() throws SQLException {
        long batchId;
        ItFixtures.SeededRound round;
        Instant lockedAt;
        Instant deadline;
        List<byte[]> onTimeBodies;
        byte[] lateBody;
        Map<String, List<Map<String, Object>>> beforeRestart;

        // 阶段一：创建批次、受理两条准时结果、截止后锁定，再整体关闭上下文。
        HikariDataSource firstPool;
        try (ConfigurableApplicationContext first = startContext()) {
            ItScenario scenario = ItScenario.from(first);
            ItFixtures fixtures = scenario.fixtures();
            round = fixtures.seedRound(3);
            batchId = scenario.createBatch(round).getBatchId();
            onTimeBodies = scenario.deliverResults(batchId, List.of(101, 102));
            PredictionLockService.LockResult locked = scenario.lockAfterOnTimeResults(batchId);
            lockedAt = locked.lockedAt();
            deadline = locked.predictionDeadline();
            assertThat(lockedAt).isAfter(deadline);
            // 第三题的结果消息在重启前按不可变 outbox 载荷构造，重启后才投递，模拟锁定后晚到。
            lateBody = fixtures.resultMessage(fixtures.outboxRows(batchId).get(2).payloadJson(), 101);
            beforeRestart = snapshot(fixtures, batchId);
            firstPool = first.getBean(DataSource.class).unwrap(HikariDataSource.class);
        }
        assertThat(firstPool.isClosed()).as("第一阶段上下文关闭后连接池必须已关闭").isTrue();

        // 阶段二：全新上下文，核对持久状态并验证幂等与晚到。
        Map<String, List<Map<String, Object>>> afterScoring;
        BatchTotalScore firstScore;
        try (ConfigurableApplicationContext second = startContext()) {
            ItScenario scenario = ItScenario.from(second);
            ItFixtures fixtures = scenario.fixtures();
            PredictionLockService lockService = second.getBean(PredictionLockService.class);

            assertThat(snapshot(fixtures, batchId)).as("重启后锁定、任务、结果与凭证不变").isEqualTo(beforeRestart);
            assertThat(fixtures.instant("SELECT locked_at FROM prediction_batch WHERE id = ?", batchId))
                    .isEqualTo(lockedAt);

            Instant laterLockTime = ItFixtures.awaitClockStrictlyAfter(lockedAt, Duration.ofSeconds(5));
            PredictionLockService.LockResult relocked = lockService.lockBatch(batchId, laterLockTime);
            assertThat(relocked.newlyLocked()).isFalse();
            assertThat(relocked.lockedAt()).isEqualTo(lockedAt);
            assertThat(fixtures.count("SELECT lock_version FROM prediction_batch WHERE id = ?", batchId))
                    .as("重复锁定不得推进版本").isEqualTo(1);

            assertThat(scenario.deliverResult(onTimeBodies.get(0))).isEqualTo(OutcomeApplyResult.DUPLICATE);
            assertThat(snapshot(fixtures, batchId)).as("重复投递不改变任何受理数据").isEqualTo(beforeRestart);

            assertThat(scenario.deliverResult(lateBody)).isEqualTo(OutcomeApplyResult.APPLIED);
            Instant lateReceivedAt = fixtures.instant("""
                    SELECT r.received_at FROM prediction_outcome_receipt r JOIN prediction_job j ON j.id = r.job_id
                    WHERE j.batch_id = ? ORDER BY r.received_at DESC LIMIT 1
                    """, batchId);
            assertThat(lateReceivedAt).as("晚到结果的接收时间严格晚于业务截止").isAfter(deadline);

            assertThat(scenario.publishAnswers(round, ItFixtures.answersInOrder(round, 101, 101, 101)).getStatus())
                    .isEqualTo("SUCCESS");
            firstScore = second.getBean(ScoringService.class).scoreBatch(batchId);
            assertThat(firstScore.getTotalScore()).isEqualByComparingTo("1");
            assertThat(firstScore.getMaxScore()).as("LATE 不进入分母").isEqualByComparingTo("2");
            List<Long> jobIds = fixtures.outboxRows(batchId).stream().map(ItFixtures.OutboxRow::jobId).toList();
            assertThat(scoreStatusOfJob(fixtures, jobIds.get(0))).isEqualTo("SCORED_CORRECT");
            assertThat(scoreStatusOfJob(fixtures, jobIds.get(1))).isEqualTo("SCORED_ZERO");
            assertThat(scoreStatusOfJob(fixtures, jobIds.get(2))).isEqualTo("LATE");
            afterScoring = snapshot(fixtures, batchId);
        }

        // 阶段三：再次重建上下文，首次评分保持不变，重复评分、锁定与投递均幂等。
        try (ConfigurableApplicationContext third = startContext()) {
            ItScenario scenario = ItScenario.from(third);
            ItFixtures fixtures = scenario.fixtures();
            BatchTotalScore again = third.getBean(ScoringService.class).scoreBatch(batchId);
            assertThat(again.getId()).isEqualTo(firstScore.getId());
            assertThat(again.getTotalScore()).isEqualByComparingTo(firstScore.getTotalScore());
            assertThat(scenario.deliverResult(lateBody)).isEqualTo(OutcomeApplyResult.DUPLICATE);
            PredictionLockService.LockResult relocked = third.getBean(PredictionLockService.class)
                    .lockBatch(batchId, ItFixtures.awaitClockStrictlyAfter(lockedAt, Duration.ofSeconds(5)));
            assertThat(relocked.lockedAt()).isEqualTo(lockedAt);
            assertThat(snapshot(fixtures, batchId)).as("再次重启后评分与受理数据完全不变").isEqualTo(afterScoring);
            assertThat(fixtures.count("SELECT COUNT(*) FROM scoring_detail_history WHERE batch_id = ?", batchId))
                    .isZero();
        }
    }

    private static ConfigurableApplicationContext startContext() {
        return new SpringApplicationBuilder(DStageItApplication.class)
                .web(WebApplicationType.NONE)
                .properties(DStageItApplication.CONFIG_NAME_PROPERTY)
                .run();
    }

    private static Map<String, List<Map<String, Object>>> snapshot(ItFixtures fixtures, long batchId) {
        return Map.of(
                "batch", fixtures.rows(BATCH_STATE, batchId),
                "jobs", fixtures.rows(JOB_STATE, batchId),
                "results", fixtures.rows(RESULT_STATE, batchId),
                "items", fixtures.rows(ITEM_STATE, batchId),
                "receipts", fixtures.rows(RECEIPT_STATE, batchId),
                "scores", fixtures.rows(SCORE_STATE, batchId),
                "totals", fixtures.rows(TOTAL_STATE, batchId));
    }

    private static String scoreStatusOfJob(ItFixtures fixtures, long jobId) {
        return fixtures.jdbc().queryForObject("""
                SELECT d.score_status FROM scoring_detail d JOIN prediction_result r ON r.id = d.result_id
                WHERE r.job_id = ?
                """, String.class, jobId);
    }
}

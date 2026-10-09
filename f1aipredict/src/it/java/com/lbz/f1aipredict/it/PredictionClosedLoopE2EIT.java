package com.lbz.f1aipredict.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.it.support.DStageItApplication;
import com.lbz.f1aipredict.it.support.ItFeedServer;
import com.lbz.f1aipredict.it.support.ItFixtures;
import com.lbz.f1aipredict.prediction.inbound.PredictionOutcomePersistenceService.OutcomeApplyResult;
import com.lbz.f1aipredict.prediction.schedule.PredictionLockScheduler;
import com.lbz.f1aipredict.scoring.schedule.AutoScoreScheduler;
import com.lbz.f1aipredict.scoring.schedule.OfficialAnswerSyncScheduler;
import com.lbz.f1aipredict.scoring.service.AutoScoreResult;
import com.lbz.f1aipredict.scoring.service.ScoringService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 侧替身闭环：真实创建批次与 outbox 请求 → 核对冻结题型 SINGLE → 按请求载荷构造 v2 结果，
 * 经真实解析器与受理事务入库（只绕过 Broker）→ 截止后由锁定调度锁定 → 答案同步调度经真实 Feed 客户端
 * 从本机替身 Feed 取答案 → 自动评分调度完成首次评分 → 再跑全部调度不重计。
 * <p>
 * 证据边界：本测试不经过 RabbitMQ、不运行 Python Worker、不调用任何真实模型或真实 Feed，
 * 结果消息由测试按契约构造；它只证明 Java 端在真实 MySQL 与事务下的闭环。
 * 每次验收必须新建带运行标记的专属 IT 库；本用例不依赖其他用例行，只对新建批次与比赛日做断言。
 * 调度器仍使用真实整库扫描，可能锁定或评分同次验收的其他就绪批次，并为未配置 Feed 的比赛日记录失败审计。
 * 调度方法由测试显式调用（上下文未开启 {@code @EnableScheduling}），以有界次数轮询直至本批次达到期望状态。
 */
@SpringBootTest(classes = {DStageItApplication.class, PredictionClosedLoopE2EIT.Schedulers.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                DStageItApplication.CONFIG_NAME_PROPERTY,
                "f1predict.prediction.lock-scheduler.enabled=true",
                "f1predict.prediction.lock-scheduler.batch-limit=500",
                "f1predict.scoring.answer-sync-scheduler.enabled=true",
                "f1predict.scoring.answer-sync-scheduler.batch-limit=100",
                "f1predict.scoring.auto-score-scheduler.enabled=true",
                "f1predict.scoring.auto-score-scheduler.batch-limit=500"
        })
class PredictionClosedLoopE2EIT extends AbstractDStageIT {

    /** 单个调度轮询的最多次数；候选按主键游标轮转，有界重复足以覆盖共享库中的其他候选。 */
    private static final int MAX_TICKS = 20;

    private static final ItFeedServer FEED = new ItFeedServer();

    private static final String DETAIL_COUNT = "SELECT COUNT(*) FROM scoring_detail WHERE batch_id = ?";
    private static final String TOTAL_COUNT = "SELECT COUNT(*) FROM batch_total_score WHERE batch_id = ?";

    /** 只导入调度器类本身；开关属性在上方开启，但上下文不开启 @EnableScheduling，不会自动触发。 */
    @TestConfiguration(proxyBeanMethods = false)
    @Import({PredictionLockScheduler.class, OfficialAnswerSyncScheduler.class, AutoScoreScheduler.class})
    static class Schedulers {
    }

    @Autowired
    private PredictionLockScheduler lockScheduler;

    @Autowired
    private OfficialAnswerSyncScheduler answerSyncScheduler;

    @Autowired
    private AutoScoreScheduler autoScoreScheduler;

    @Autowired
    private ScoringService scoringService;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void feedProperties(DynamicPropertyRegistry registry) {
        registry.add("f1predict.feed.base-url", FEED::baseUrl);
    }

    @AfterAll
    static void stopFeed() {
        FEED.close();
    }

    @Test
    void createConsumeLockSyncAnswersAndAutoScoreOnce() throws Exception {
        ItFixtures.SeededRound round = fixtures.seedRound(3);
        long batchId = scenario.createBatch(round).getBatchId();
        assertThat(fixtures.count(TOTAL_COUNT, batchId)).isZero();
        assertThat(fixtures.count(DETAIL_COUNT, batchId)).isZero();
        assertThat(fixtures.count("SELECT COUNT(*) FROM official_answer WHERE gameday_id = ?",
                round.gamedayId())).isZero();

        // 冻结请求：每题一条不可变 outbox，题型按冻结快照 ChoiceLimit=1 映射为 SINGLE。
        List<ItFixtures.OutboxRow> outbox = fixtures.outboxRows(batchId);
        assertThat(outbox).hasSize(3);
        for (ItFixtures.OutboxRow row : outbox) {
            JsonNode payload = objectMapper.readTree(row.payloadJson());
            assertThat(payload.path("schemaVersion").asText()).isEqualTo("2");
            assertThat(payload.path("question").path("questionType").asText()).isEqualTo("SINGLE");
            assertThat(payload.path("question").path("choiceLimit").asInt()).isEqualTo(1);
            assertThat(payload.path("batchId").asLong()).isEqualTo(batchId);
        }
        assertThat(fixtures.count("SELECT COUNT(*) FROM prediction_request_outbox o "
                + "JOIN prediction_job j ON j.prediction_job_id = o.prediction_job_id "
                + "WHERE j.batch_id = ? AND o.status = 'PENDING'", batchId)).isEqualTo(3);

        // 替身 Worker：逐任务构造 v2 结果，经解析器 + 受理事务入库；重投同一消息为幂等。
        List<byte[]> bodies = scenario.deliverResults(batchId, List.of(101, 102, 103));
        assertThat(scenario.deliverResult(bodies.get(0))).isEqualTo(OutcomeApplyResult.DUPLICATE);
        assertThat(fixtures.count("SELECT COUNT(*) FROM prediction_job WHERE batch_id = ? AND status = 'SUCCEEDED'",
                batchId)).isEqualTo(3);
        assertThat(fixtures.jdbc().queryForObject("SELECT status FROM prediction_batch WHERE id = ?",
                String.class, batchId)).isEqualTo("COMPLETED");

        // 截止快进到最后一条准时结果之后，再由锁定调度完成锁定。
        Instant deadline = fixtures.fastForwardDeadlineToLatestReceipt(batchId);
        ItFixtures.awaitClockStrictlyAfter(deadline, Duration.ofSeconds(5));
        tickUntil(lockScheduler::lockDueBatches,
                () -> fixtures.instant("SELECT locked_at FROM prediction_batch WHERE id = ?", batchId) != null,
                "锁定调度应锁定本批次", batchId, round.gamedayId());
        assertThat(fixtures.instant("SELECT locked_at FROM prediction_batch WHERE id = ?", batchId))
                .isAfter(deadline);

        // 答案尚未公布：自动评分调度不得写入任何评分。
        autoScoreScheduler.autoScoreReadyBatches();
        assertThat(fixtures.count(TOTAL_COUNT, batchId)).isZero();
        assertThat(scoringService.tryAutoScoreBatch(batchId).decision())
                .isEqualTo(AutoScoreResult.Decision.SKIPPED_ANSWERS_MISSING);
        assertThat(fixtures.count(DETAIL_COUNT, batchId)).isZero();

        // 替身 Feed 公布答案，答案同步调度经真实 Feed 客户端拉取并入库。
        int requestsBefore = FEED.requestCount();
        FEED.setQuestionsFeed(round.gamedayId(),
                fixtures.answerFeedJson(ItFixtures.answersInOrder(round, 101, 102, 101)));
        tickUntil(answerSyncScheduler::syncPredictedGamedayAnswers,
                () -> fixtures.count("SELECT COUNT(*) FROM official_answer WHERE gameday_id = ?",
                        round.gamedayId()) == 3,
                "答案同步调度应写入本比赛日全部答案", batchId, round.gamedayId());
        assertThat(FEED.requestCount()).isGreaterThan(requestsBefore);

        // 自动评分调度完成首次评分：101/102/103 对答案 101/102/101，得 2 分 / 满分 3。
        assertThat(fixtures.count(TOTAL_COUNT, batchId)).isZero();
        tickUntil(autoScoreScheduler::autoScoreReadyBatches,
                () -> fixtures.count(TOTAL_COUNT, batchId) == 1,
                "自动评分调度应完成本批次首次评分", batchId, round.gamedayId());
        Map<String, Object> total = fixtures.rows(
                "SELECT id, total_score, max_score, created_at FROM batch_total_score WHERE batch_id = ?", batchId)
                .getFirst();
        assertThat((BigDecimal) total.get("total_score")).isEqualByComparingTo("2");
        assertThat((BigDecimal) total.get("max_score")).isEqualByComparingTo("3");
        assertThat(fixtures.count(DETAIL_COUNT, batchId)).isEqualTo(3);
        assertThat(fixtures.count("SELECT COUNT(*) FROM scoring_detail WHERE batch_id = ? "
                + "AND question_type = 'SINGLE' AND score_status IN ('SCORED_CORRECT', 'SCORED_ZERO')", batchId))
                .isEqualTo(3);

        // 再跑全部调度：答案不变不归档，评分不重算，历史表无本批次行。
        List<Map<String, Object>> detailsAfterFirst = fixtures.rows(
                "SELECT id, result_id, score, score_status, detail_json FROM scoring_detail WHERE batch_id = ? "
                        + "ORDER BY id", batchId);
        lockScheduler.lockDueBatches();
        answerSyncScheduler.syncPredictedGamedayAnswers();
        autoScoreScheduler.autoScoreReadyBatches();
        AutoScoreResult rerun = scoringService.tryAutoScoreBatch(batchId);
        assertThat(rerun.decision()).isEqualTo(AutoScoreResult.Decision.ALREADY_SCORED);
        assertThat(rerun.total().getId()).isEqualTo(((Number) total.get("id")).longValue());
        assertThat(fixtures.rows("SELECT id, total_score, max_score, created_at FROM batch_total_score "
                + "WHERE batch_id = ?", batchId)).containsExactly(total);
        assertThat(fixtures.rows("SELECT id, result_id, score, score_status, detail_json FROM scoring_detail "
                + "WHERE batch_id = ? ORDER BY id", batchId)).isEqualTo(detailsAfterFirst);
        assertThat(fixtures.count("SELECT COUNT(*) FROM scoring_detail_history WHERE batch_id = ?", batchId))
                .isZero();
        assertThat(fixtures.count("SELECT COUNT(*) FROM batch_total_score_history WHERE batch_id = ?", batchId))
                .isZero();
        assertThat(fixtures.count("SELECT COUNT(*) FROM official_answer_history WHERE gameday_id = ?",
                round.gamedayId())).isZero();

        // 已评分后仍持续同步修订：第三题改为 103，若错误重算会变为 3/3；当前评分必须保持 2/3。
        FEED.setQuestionsFeed(round.gamedayId(),
                fixtures.answerFeedJson(ItFixtures.answersInOrder(round, 101, 102, 103)));
        tickUntil(answerSyncScheduler::syncPredictedGamedayAnswers,
                () -> fixtures.count("SELECT COUNT(*) FROM official_answer_history WHERE gameday_id = ?",
                        round.gamedayId()) == 1,
                "答案修订应被继续同步并归档", batchId, round.gamedayId());
        String revisedAnswer = fixtures.jdbc().queryForObject(
                "SELECT answer_content FROM official_answer WHERE question_id = ? AND gameday_id = ?",
                String.class, round.questions().get(2).questionId(), round.gamedayId());
        assertThat(objectMapper.readTree(revisedAnswer).get(0).path("Id").asInt()).isEqualTo(103);
        autoScoreScheduler.autoScoreReadyBatches();
        assertThat(scoringService.tryAutoScoreBatch(batchId).decision())
                .isEqualTo(AutoScoreResult.Decision.ALREADY_SCORED);
        assertThat(fixtures.rows("SELECT id, total_score, max_score, created_at FROM batch_total_score "
                + "WHERE batch_id = ?", batchId)).containsExactly(total);
        assertThat(fixtures.rows("SELECT id, result_id, score, score_status, detail_json FROM scoring_detail "
                + "WHERE batch_id = ? ORDER BY id", batchId)).isEqualTo(detailsAfterFirst);
        assertThat(fixtures.count("SELECT COUNT(*) FROM scoring_detail_history WHERE batch_id = ?", batchId))
                .isZero();
        assertThat(fixtures.count("SELECT COUNT(*) FROM batch_total_score_history WHERE batch_id = ?", batchId))
                .isZero();
    }

    /** 有界轮询；失败时输出本用例批次、任务与答案就绪摘要，不输出连接配置或载荷。 */
    private void tickUntil(Runnable tick, BooleanSupplier done, String expectation, long batchId, int gamedayId) {
        for (int attempt = 1; attempt <= MAX_TICKS; attempt++) {
            tick.run();
            if (done.getAsBoolean()) {
                return;
            }
        }
        throw new AssertionError(expectation + "（已调用 " + MAX_TICKS + " 次仍未满足）");
    }
}

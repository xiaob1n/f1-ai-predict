package com.lbz.f1aipredict.it;

import com.lbz.f1aipredict.it.support.ItScenario.ReadyBatch;
import com.lbz.f1aipredict.it.support.ScoringFaultInjector;
import com.lbz.f1aipredict.it.support.ScoringFaultInjector.InjectedScoringFault;
import com.lbz.f1aipredict.scoring.dto.AnswerUpdateRequest;
import com.lbz.f1aipredict.scoring.entity.BatchTotalScore;
import com.lbz.f1aipredict.scoring.service.OfficialAnswerService;
import com.lbz.f1aipredict.scoring.service.ScoringService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D7：真实 Spring 事务代理 + MySQL 下的评分故障注入。
 * <p>
 * 故障点在批次总分插入：此时同一事务已写入整批明细（重算时还已归档并删除旧评分）。
 * 断言事务内确实看到了半套数据，而回滚后库中不存在任何半套明细、总分或历史行。
 */
class ScoringTransactionRollbackIT extends AbstractDStageIT {

    private static final String DETAIL_COUNT = "SELECT COUNT(*) FROM scoring_detail WHERE batch_id = ?";
    private static final String TOTAL_COUNT = "SELECT COUNT(*) FROM batch_total_score WHERE batch_id = ?";
    private static final String DETAIL_HISTORY_COUNT =
            "SELECT COUNT(*) FROM scoring_detail_history WHERE batch_id = ?";
    private static final String TOTAL_HISTORY_COUNT =
            "SELECT COUNT(*) FROM batch_total_score_history WHERE batch_id = ?";
    private static final String DETAIL_ROWS = """
            SELECT id, result_id, question_id, question_type, score, max_score, score_status,
                   scoring_rule_version, detail_json, scored_at, created_at
            FROM scoring_detail WHERE batch_id = ? ORDER BY id
            """;
    private static final String TOTAL_ROWS = """
            SELECT id, round_id, total_score, max_score, accuracy_rate, type_stats_json, created_at, updated_at
            FROM batch_total_score WHERE batch_id = ?
            """;

    @Autowired
    private ScoringService scoringService;

    @Autowired
    private OfficialAnswerService answerService;

    @Autowired
    private ScoringFaultInjector faults;

    @AfterEach
    void disarm() {
        faults.disarm();
    }

    @Test
    void scoreBatchRollsBackInsertedDetailsWhenTotalInsertFails() {
        // 三题预测 101/102/103，答案均为 101：评分成功时应为 1 分 / 满分 3。
        ReadyBatch ready = scenario.prepareLockedBatch(List.of(101, 102, 103), List.of(101, 101, 101));
        long batchId = ready.batchId();
        AtomicBoolean insideTransaction = new AtomicBoolean();
        AtomicLong detailsInsideTransaction = new AtomicLong(-1);
        faults.armTotalInsertFailure(batchId, id -> {
            insideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            // 同一线程的 JdbcTemplate 复用事务连接，可读到本事务尚未提交的明细。
            detailsInsideTransaction.set(fixtures.count(DETAIL_COUNT, id));
        });

        assertThatThrownBy(() -> scoringService.scoreBatch(batchId)).isInstanceOf(InjectedScoringFault.class);
        faults.disarm();

        assertThat(insideTransaction).as("故障发生时应处于评分事务内").isTrue();
        assertThat(detailsInsideTransaction.get()).as("故障前整批明细已在事务内写入").isEqualTo(3);
        assertThat(fixtures.count(DETAIL_COUNT, batchId)).as("回滚后不得残留明细").isZero();
        assertThat(fixtures.count(TOTAL_COUNT, batchId)).as("回滚后不得残留总分").isZero();

        BatchTotalScore total = scoringService.scoreBatch(batchId);
        assertThat(total.getTotalScore()).isEqualByComparingTo("1");
        assertThat(total.getMaxScore()).isEqualByComparingTo("3");
        assertThat(fixtures.count(DETAIL_COUNT, batchId)).isEqualTo(3);
        assertThat(fixtures.count(TOTAL_COUNT, batchId)).isEqualTo(1);
        assertThat(fixtures.count("SELECT COUNT(*) FROM scoring_detail WHERE batch_id = ? AND score_status = ?",
                batchId, "SCORED_CORRECT")).isEqualTo(1);
        assertThat(fixtures.count("SELECT COUNT(*) FROM scoring_detail WHERE batch_id = ? AND score_status = ?",
                batchId, "SCORED_ZERO")).isEqualTo(2);
    }

    @Test
    void rescoreFailureRestoresPreviousScoresAndLeavesNoHistory() {
        ReadyBatch ready = scenario.prepareLockedBatch(List.of(101, 102, 103), List.of(101, 101, 101));
        long batchId = ready.batchId();
        BatchTotalScore first = scoringService.scoreBatch(batchId);
        assertThat(first.getTotalScore()).isEqualByComparingTo("1");
        List<Map<String, Object>> detailsBefore = fixtures.rows(DETAIL_ROWS, batchId);
        List<Map<String, Object>> totalBefore = fixtures.rows(TOTAL_ROWS, batchId);
        assertThat(detailsBefore).hasSize(3);
        assertThat(totalBefore).hasSize(1);

        // 修订第二题答案为 102：若重算成功，总分应变为 2。
        long secondQuestionId = ready.round().questions().get(1).questionId();
        AnswerUpdateRequest revision = new AnswerUpdateRequest();
        revision.setAnswerContent("[{\"Id\":102}]");
        answerService.updateAnswer(secondQuestionId, revision);

        AtomicLong archivedDetailsInside = new AtomicLong(-1);
        AtomicLong archivedTotalsInside = new AtomicLong(-1);
        AtomicLong currentDetailsInside = new AtomicLong(-1);
        AtomicLong currentTotalsInside = new AtomicLong(-1);
        faults.armTotalInsertFailure(batchId, id -> {
            archivedDetailsInside.set(fixtures.count(DETAIL_HISTORY_COUNT, id));
            archivedTotalsInside.set(fixtures.count(TOTAL_HISTORY_COUNT, id));
            currentDetailsInside.set(fixtures.count(DETAIL_COUNT, id));
            currentTotalsInside.set(fixtures.count(TOTAL_COUNT, id));
        });

        assertThatThrownBy(() -> scoringService.rescoreBatch(batchId, "IT_ROLLBACK_PROBE"))
                .isInstanceOf(InjectedScoringFault.class);
        faults.disarm();

        // 事务内：旧评分已归档并删除，新明细已写入，只差新总分。
        assertThat(archivedDetailsInside.get()).isEqualTo(3);
        assertThat(archivedTotalsInside.get()).isEqualTo(1);
        assertThat(currentDetailsInside.get()).isEqualTo(3);
        assertThat(currentTotalsInside.get()).isZero();
        // 回滚后：旧明细与旧总分逐列原样恢复，历史表没有任何本批次行。
        assertThat(fixtures.rows(DETAIL_ROWS, batchId)).isEqualTo(detailsBefore);
        assertThat(fixtures.rows(TOTAL_ROWS, batchId)).isEqualTo(totalBefore);
        assertThat(fixtures.count(DETAIL_HISTORY_COUNT, batchId)).isZero();
        assertThat(fixtures.count(TOTAL_HISTORY_COUNT, batchId)).isZero();

        BatchTotalScore rescored = scoringService.rescoreBatch(batchId, "IT_ANSWER_REVISION");
        assertThat(rescored.getTotalScore()).isEqualByComparingTo("2");
        assertThat(rescored.getMaxScore()).isEqualByComparingTo("3");
        assertThat(fixtures.count(DETAIL_HISTORY_COUNT, batchId)).isEqualTo(3);
        assertThat(fixtures.count(TOTAL_HISTORY_COUNT, batchId)).isEqualTo(1);
        assertThat(fixtures.count("SELECT COUNT(*) FROM batch_total_score_history WHERE batch_id = ? "
                + "AND revision_reason = ? AND total_score = 1", batchId, "IT_ANSWER_REVISION")).isEqualTo(1);
        assertThat(fixtures.count(DETAIL_COUNT, batchId)).isEqualTo(3);
        assertThat(fixtures.count(TOTAL_COUNT, batchId)).isEqualTo(1);
    }
}

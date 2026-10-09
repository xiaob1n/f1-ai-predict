package com.lbz.f1aipredict.it.support;

import com.lbz.f1aipredict.prediction.dto.CreatePredictionBatchRequest;
import com.lbz.f1aipredict.prediction.dto.PredictionBatchDto;
import com.lbz.f1aipredict.prediction.inbound.OutcomeDtos;
import com.lbz.f1aipredict.prediction.inbound.OutcomeMessageParser;
import com.lbz.f1aipredict.prediction.inbound.PredictionOutcomePersistenceService;
import com.lbz.f1aipredict.prediction.inbound.PredictionOutcomePersistenceService.OutcomeApplyResult;
import com.lbz.f1aipredict.prediction.inbound.PredictionOutcomeProperties;
import com.lbz.f1aipredict.prediction.service.PredictionBatchService;
import com.lbz.f1aipredict.prediction.service.PredictionLockService;
import com.lbz.f1aipredict.scoring.service.impl.OfficialAnswerTransactionExecutor;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通过真实 Spring Bean 驱动业务流程的场景助手：创建批次、经解析器与受理服务写入结果、
 * 截止后锁定、写入官方答案。所有写入都走生产代码的事务代理，只绕过消息 Broker 与 HTTP。
 */
public final class ItScenario {

    /** 与生产默认一致的 RESULT 消息上限，避免测试另设口径。 */
    private static final int RESULT_MAX_BYTES = new PredictionOutcomeProperties().getResultMaxMessageBytes();

    private final ItFixtures fixtures;
    private final PredictionBatchService batchService;
    private final OutcomeMessageParser parser;
    private final PredictionOutcomePersistenceService outcomeService;
    private final PredictionLockService lockService;
    private final OfficialAnswerTransactionExecutor answerExecutor;

    public ItScenario(ItFixtures fixtures, PredictionBatchService batchService, OutcomeMessageParser parser,
                      PredictionOutcomePersistenceService outcomeService, PredictionLockService lockService,
                      OfficialAnswerTransactionExecutor answerExecutor) {
        this.fixtures = Objects.requireNonNull(fixtures);
        this.batchService = Objects.requireNonNull(batchService);
        this.parser = Objects.requireNonNull(parser);
        this.outcomeService = Objects.requireNonNull(outcomeService);
        this.lockService = Objects.requireNonNull(lockService);
        this.answerExecutor = Objects.requireNonNull(answerExecutor);
    }

    /** 从任一 IT 上下文取得场景助手。 */
    public static ItScenario from(ApplicationContext context) {
        return context.getBean(ItScenario.class);
    }

    public ItFixtures fixtures() {
        return fixtures;
    }

    /**
     * 用真实批次服务为分站全部题目创建批次；数据截止为一小时前，业务截止为一小时后，
     * 保证随后立即受理的结果都准时。
     */
    public PredictionBatchDto createBatch(ItFixtures.SeededRound round) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CreatePredictionBatchRequest request = CreatePredictionBatchRequest.builder()
                .questionIds(round.questionIds())
                .dataCutoff(now.minus(Duration.ofHours(1)))
                .predictionDeadline(now.plus(Duration.ofHours(1)))
                .featureVersion("it-feature-v1")
                .modelVersion("it-model-v1")
                .promptVersion("it-prompt-v1")
                .build();
        return batchService.create(round.roundId(), request);
    }

    /** 模拟消息消费：先经真实解析器严格校验，再交给受理服务的事务方法。 */
    public OutcomeApplyResult deliverResult(byte[] body) {
        OutcomeDtos.Result result = parser.parseResult(body, RESULT_MAX_BYTES);
        return outcomeService.applyResult(result, OutcomeMessageParser.sha256(body));
    }

    /**
     * 为批次中前 {@code count} 个任务各投递一条选择 {@code optionIds[i]} 的结果，返回对应消息字节以便重投。
     */
    public List<byte[]> deliverResults(long batchId, List<Integer> optionIds) {
        List<ItFixtures.OutboxRow> rows = fixtures.outboxRows(batchId);
        if (optionIds.size() > rows.size()) {
            throw new IllegalArgumentException("结果数量超过任务数量");
        }
        List<byte[]> bodies = new ArrayList<>(optionIds.size());
        for (int index = 0; index < optionIds.size(); index++) {
            byte[] body = fixtures.resultMessage(rows.get(index).payloadJson(), optionIds.get(index));
            OutcomeApplyResult applied = deliverResult(body);
            if (applied != OutcomeApplyResult.APPLIED) {
                throw new AssertionError("首次投递应为 APPLIED，实际 " + applied);
            }
            bodies.add(body);
        }
        return bodies;
    }

    /**
     * 快进截止到已受理结果之后，等待服务端时钟越过截止，再以真实锁定服务锁定批次。
     *
     * @return 锁定结果
     */
    public PredictionLockService.LockResult lockAfterOnTimeResults(long batchId) {
        Instant deadline = fixtures.fastForwardDeadlineToLatestReceipt(batchId);
        Instant lockTime = ItFixtures.awaitClockStrictlyAfter(deadline, Duration.ofSeconds(5));
        PredictionLockService.LockResult locked = lockService.lockBatch(batchId, lockTime);
        if (!locked.newlyLocked()) {
            throw new AssertionError("批次应为首次锁定: " + batchId);
        }
        return locked;
    }

    /** 已锁定且答案齐备、尚未评分的批次。 */
    public record ReadyBatch(ItFixtures.SeededRound round, long batchId) {
    }

    /**
     * 准备一批可评分数据：每题一条准时结果（选项依次取 {@code predicted}），截止后锁定，
     * 再按题目顺序写入官方答案 {@code answers}。
     */
    public ReadyBatch prepareLockedBatch(List<Integer> predicted, List<Integer> answers) {
        if (predicted.size() != answers.size()) {
            throw new IllegalArgumentException("预测与答案数量必须一致");
        }
        ItFixtures.SeededRound round = fixtures.seedRound(predicted.size());
        long batchId = createBatch(round).getBatchId();
        deliverResults(batchId, predicted);
        lockAfterOnTimeResults(batchId);
        SyncResultDto sync = publishAnswers(round,
                ItFixtures.answersInOrder(round, answers.toArray(Integer[]::new)));
        if (!"SUCCESS".equals(sync.getStatus())) {
            throw new AssertionError("答案写入应成功，实际 " + sync.getStatus());
        }
        return new ReadyBatch(round, batchId);
    }

    /** 经真实答案事务组件写入官方答案（不经 HTTP），返回同步审计结果。 */
    public SyncResultDto publishAnswers(ItFixtures.SeededRound round, Map<Integer, Integer> answers) {
        long startedAt = System.currentTimeMillis();
        String sourceUrl = "it://answers/" + round.gamedayId();
        Long token = answerExecutor.beginAnswerSync(round.gamedayId(), sourceUrl, startedAt);
        return answerExecutor.persist(token, round.gamedayId(), sourceUrl,
                fixtures.answerFeedJson(answers), startedAt);
    }
}

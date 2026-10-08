package com.lbz.f1aipredict.scoring.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.prediction.entity.PredictionBatch;
import com.lbz.f1aipredict.prediction.entity.PredictionJob;
import com.lbz.f1aipredict.prediction.entity.PredictionOutcomeReceipt;
import com.lbz.f1aipredict.prediction.entity.PredictionResult;
import com.lbz.f1aipredict.prediction.entity.PredictionResultItem;
import com.lbz.f1aipredict.prediction.mapper.PredictionBatchMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionJobMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionOutcomeReceiptMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionResultItemMapper;
import com.lbz.f1aipredict.prediction.mapper.PredictionResultMapper;
import com.lbz.f1aipredict.prediction.service.PredictionLockService;
import com.lbz.f1aipredict.question.entity.Question;
import com.lbz.f1aipredict.question.entity.QuestionOption;
import com.lbz.f1aipredict.question.entity.QuestionSnapshot;
import com.lbz.f1aipredict.question.mapper.QuestionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionOptionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionSnapshotMapper;
import com.lbz.f1aipredict.scoring.ScoreStatus;
import com.lbz.f1aipredict.scoring.engine.ScoringEngine;
import com.lbz.f1aipredict.scoring.engine.ScoringEngine.ScoreOutcome;
import com.lbz.f1aipredict.scoring.entity.BatchTotalScore;
import com.lbz.f1aipredict.scoring.entity.OfficialAnswer;
import com.lbz.f1aipredict.scoring.entity.ScoringDetail;
import com.lbz.f1aipredict.scoring.mapper.BatchTotalScoreMapper;
import com.lbz.f1aipredict.scoring.mapper.OfficialAnswerMapper;
import com.lbz.f1aipredict.scoring.mapper.ScoringDetailMapper;
import com.lbz.f1aipredict.scoring.service.ScoringService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 批次评分编排：全部读取与写入在同一个数据库短事务内完成，没有任何外部 HTTP 调用。
 * <p>
 * 本阶段只启用 SINGLE 题型，{@code question_type = 'SINGLE'} 仅在同时满足以下条件时成立：
 * <ol>
 *   <li>任务关联快照中的 {@code Config.ChoiceLimit == 1}；</li>
 *   <li>官方答案恰有 1 个 {@code Id}；</li>
 *   <li>该 {@code Id} 存在于预测任务冻结快照（{@code prediction_job.question_snapshot_id}）的
 *       {@code question_option.option_id} 中。</li>
 * </ol>
 * 任一条件不满足，该行为 {@code question_type = 'UNKNOWN'}、状态 UNSCORED 且在 detail_json 写明原因，
 * 绝不当作 0 分；迟到、无答案、取消的行同样不属于已核实的单选评分，题型也记为 UNKNOWN。
 * 受理遵守批次→任务的锁顺序并在批次锁后取接收时间，使准时结果必在截止锁定提交前提交；
 * 锁定后取得批次锁的结果接收时间严格晚于截止，因此为 LATE 且仅作审计。
 * 同批次受理因此串行；该保证依赖单一可信且不回拨的服务端时钟，多实例须以 NTP 同步。
 * 显式假设如下：
 * <ul>
 *   <li>题型映射尚未落地，仅冻结快照的 {@code Config.ChoiceLimit == 1} 可判为 SINGLE，其他值为 UNKNOWN + UNSCORED，
 *       不据此推断其他题型；上述答案与冻结选项核验仍须通过。</li>
 *   <li>{@code prediction_outcome_receipt.received_at} 是结果首次接收时间；持有批次行锁后以该持久化接收时间判定迟到，
 *       不依赖结果行创建时间，避免数据库精度转换影响边界判断。</li>
 *   <li>{@code CANCELLED} 当前无产出方，该分支仅保留防御性处理，不代表取消流程已落地。</li>
 *   <li>detail_json 的 {@code answerTimingProof} 只陈述接收时间与答案时间的先后，不影响评分状态与得分，
 *       等级定义见 {@code docs/design/java-api-design.md}「6.0 时间边界与证明等级」：
 *       非 NULL 的 {@code published_at} 目前只可能来自人工修订，只算人工声明，不是系统证明；
 *       {@code created_at} 是本系统首次观察到<b>当前答案行</b>的时间，答案撤销会删除该行、重新公布会插入新行并重置它。
 *       它是答案同步事务在拉取 Feed、写入原文、取得行锁之后读取的 Java 时钟，晚于实际拉取时刻且差值没有上界；
 *       定时题目同步读取同一端点，可能更早已把含答案的原文写入 {@code feed_raw_payload}（source_type=QUESTIONS），
 *       因此 {@code BEFORE_SYSTEM_OBSERVATION_ONLY} 只说明早于答案同步路径写入当前答案行，不是系统任何组件首次拿到答案的时间。
 *       {@code created_at} 与 {@code received_at} 均由 Java {@code Instant.now()} 写入（同一时钟来源，
 *       多实例部署仍依赖各实例 NTP 同步）；但 {@code created_at} 以 DATETIME(3) 存储且写入前未截断，
 *       MySQL 舍入可能使其比这次时钟读数晚至多 0.5ms（余量只覆盖舍入，不覆盖拉取到读时钟之间的延迟），因此 {@code BEFORE_SYSTEM_OBSERVATION_ONLY}
 *       要求接收时间早于截断到毫秒后的观察时间再减 1ms，落在该精度带内的记为 UNKNOWN。</li>
 * </ul>
 */
@Slf4j
@Service
public class ScoringServiceImpl implements ScoringService {

    private static final String TYPE_SINGLE = "SINGLE";
    private static final String TYPE_UNKNOWN = "UNKNOWN";
    private static final String QUESTION_STATUS_CANCELLED = "CANCELLED";
    /** 与 sql/011 中 revision_reason 列宽一致。 */
    private static final int MAX_REASON_LENGTH = 64;
    /**
     * 系统观察时间比较的精度余量：official_answer.created_at 为 DATETIME(3) 且写入前未截断，
     * MySQL 舍入最多使其晚 0.5ms；received_at 为 DATETIME(6)，舍入最多 0.5µs。取 1ms 足以覆盖两者之和。
     */
    private static final Duration OBSERVATION_PRECISION_MARGIN = Duration.ofMillis(1);

    private final PredictionBatchMapper batchMapper;
    private final PredictionJobMapper jobMapper;
    private final PredictionResultMapper resultMapper;
    private final PredictionResultItemMapper resultItemMapper;
    private final PredictionOutcomeReceiptMapper receiptMapper;
    private final QuestionMapper questionMapper;
    private final QuestionSnapshotMapper snapshotMapper;
    private final QuestionOptionMapper optionMapper;
    private final OfficialAnswerMapper answerMapper;
    private final ScoringDetailMapper detailMapper;
    private final BatchTotalScoreMapper totalMapper;
    private final PredictionLockService lockService;
    private final ScoringEngine engine;
    private final ObjectMapper objectMapper;

    public ScoringServiceImpl(PredictionBatchMapper batchMapper, PredictionJobMapper jobMapper,
                              PredictionResultMapper resultMapper, PredictionResultItemMapper resultItemMapper,
                              PredictionOutcomeReceiptMapper receiptMapper, QuestionMapper questionMapper,
                              QuestionSnapshotMapper snapshotMapper, QuestionOptionMapper optionMapper,
                              OfficialAnswerMapper answerMapper,
                              ScoringDetailMapper detailMapper, BatchTotalScoreMapper totalMapper,
                              PredictionLockService lockService, ScoringEngine engine, ObjectMapper objectMapper) {
        this.batchMapper = Objects.requireNonNull(batchMapper, "batchMapper must not be null");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper must not be null");
        this.resultMapper = Objects.requireNonNull(resultMapper, "resultMapper must not be null");
        this.resultItemMapper = Objects.requireNonNull(resultItemMapper, "resultItemMapper must not be null");
        this.receiptMapper = Objects.requireNonNull(receiptMapper, "receiptMapper must not be null");
        this.questionMapper = Objects.requireNonNull(questionMapper, "questionMapper must not be null");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper must not be null");
        this.optionMapper = Objects.requireNonNull(optionMapper, "optionMapper must not be null");
        this.answerMapper = Objects.requireNonNull(answerMapper, "answerMapper must not be null");
        this.detailMapper = Objects.requireNonNull(detailMapper, "detailMapper must not be null");
        this.totalMapper = Objects.requireNonNull(totalMapper, "totalMapper must not be null");
        this.lockService = Objects.requireNonNull(lockService, "lockService must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 运行于 READ COMMITTED：先锁定批次行，再读取已提交的完整结果集合，
     * 与受理事务开始时持有的批次行锁互斥，也串行化并发评分，避免读到入库一半的结果或产生两套评分。
     * 任何一步失败整个事务回滚，不会留下只有明细没有总分的中间状态。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchTotalScore scoreBatch(Long batchId) {
        PredictionBatch batch = lockLockedBatch(batchId);
        BatchTotalScore existing = totalMapper.selectByBatchId(batchId);
        if (existing != null) {
            return existing;
        }
        ScoredBatch scored = buildScores(batch);
        persist(scored);
        log.info("批次评分完成: batchId={}, detailCount={}, totalScore={}, maxScore={}",
                batchId, scored.details().size(), scored.total().getTotalScore(), scored.total().getMaxScore());
        return scored.total();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchTotalScore rescoreBatch(Long batchId, String reason) {
        String revisionReason = requireReason(reason);
        PredictionBatch batch = lockLockedBatch(batchId);
        if (totalMapper.selectByBatchId(batchId) == null) {
            throw new InvalidRequestException("Prediction batch has not been scored: " + batchId);
        }
        // 先在内存中算好新一套，计算失败时不会动旧数据。
        ScoredBatch scored = buildScores(batch);
        Instant archivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        int archivedDetails = detailMapper.archiveByBatchId(batchId, archivedAt, revisionReason);
        if (detailMapper.deleteByBatchId(batchId) != archivedDetails) {
            throw new IllegalStateException("Scoring details archive/delete mismatch: batchId=" + batchId);
        }
        if (totalMapper.archiveByBatchId(batchId, archivedAt, revisionReason) != 1
                || totalMapper.deleteByBatchId(batchId) != 1) {
            throw new IllegalStateException("Batch total score archive/delete failed: batchId=" + batchId);
        }
        persist(scored);
        log.info("批次显式重算完成: batchId={}, reason={}, archivedDetails={}, totalScore={}, maxScore={}",
                batchId, revisionReason, archivedDetails, scored.total().getTotalScore(),
                scored.total().getMaxScore());
        return scored.total();
    }

    /** 锁定批次行并确认批次已锁定；未锁定的批次预测仍可能变化，不得结算。 */
    private PredictionBatch lockLockedBatch(Long batchId) {
        if (batchId == null || batchId <= 0) {
            throw new InvalidRequestException("batchId must be positive");
        }
        PredictionBatch batch = batchMapper.selectByIdForUpdate(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("Prediction batch not found: " + batchId);
        }
        if (batch.getLockedAt() == null) {
            throw new InvalidRequestException("Prediction batch is not locked: " + batchId);
        }
        return batch;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new InvalidRequestException("reason must not exceed " + MAX_REASON_LENGTH + " characters");
        }
        return trimmed;
    }

    /** 明细与总分一起写入；返回行数与预期不符即抛异常，由事务整体回滚。 */
    private void persist(ScoredBatch scored) {
        Long batchId = scored.total().getBatchId();
        if (detailMapper.insertBatch(scored.details()) != scored.details().size()) {
            throw new IllegalStateException("Scoring details insert count mismatch: batchId=" + batchId);
        }
        if (totalMapper.insert(scored.total()) != 1) {
            throw new IllegalStateException("Batch total score insert failed: batchId=" + batchId);
        }
    }

    /** 批量读取评分所需数据并在内存中算出整批明细与总分；评分循环内不再访问 Mapper。 */
    private ScoredBatch buildScores(PredictionBatch batch) {
        Long batchId = batch.getId();
        List<PredictionResult> results = resultMapper.selectByBatchId(batchId);
        if (results.isEmpty()) {
            throw new InvalidRequestException("Prediction batch has no results to score: " + batchId);
        }
        List<PredictionJob> jobs = jobMapper.selectAllByBatchId(batchId);
        Map<Long, PredictionJob> jobsById = jobs.stream()
                .collect(Collectors.toMap(PredictionJob::getId, Function.identity()));
        Set<Long> resultJobIds = results.stream().map(PredictionResult::getJobId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> questionIds = results.stream().map(PredictionResult::getQuestionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> snapshotIds = new LinkedHashSet<>();
        for (Long jobId : resultJobIds) {
            PredictionJob job = jobsById.get(jobId);
            if (job != null && job.getQuestionSnapshotId() != null) {
                snapshotIds.add(job.getQuestionSnapshotId());
            }
        }

        Inputs inputs = new Inputs(jobsById,
                questionMapper.selectByIds(questionIds).stream()
                        .collect(Collectors.toMap(Question::getId, Function.identity())),
                answerMapper.selectByQuestionIds(questionIds).stream()
                        .collect(Collectors.toMap(OfficialAnswer::getQuestionId, Function.identity())),
                groupSelectedOptions(results),
                receiptMapper.selectByJobIds(resultJobIds).stream()
                        .collect(Collectors.toMap(PredictionOutcomeReceipt::getJobId, Function.identity())),
                groupSnapshotOptions(snapshotIds), groupSnapshotChoiceLimits(snapshotIds));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<ScoringDetail> details = new ArrayList<>(results.size());
        for (PredictionResult result : results) {
            details.add(scoreResult(batch, result, inputs, now));
        }
        // 没有成功结果的任务（失败、死信、未完成）无法写入 scoring_detail，单独计数以保证分母清楚。
        long noResultCount = jobs.stream().filter(job -> !resultJobIds.contains(job.getId())).count();
        return new ScoredBatch(details, aggregate(batch, jobs.size(), noResultCount, details, now));
    }

    private Map<Long, List<Integer>> groupSelectedOptions(List<PredictionResult> results) {
        Map<Long, List<Integer>> optionsByResult = new HashMap<>();
        for (PredictionResultItem item : resultItemMapper.selectByResultIds(
                results.stream().map(PredictionResult::getId).toList())) {
            optionsByResult.computeIfAbsent(item.getResultId(), key -> new ArrayList<>()).add(item.getOptionId());
        }
        return optionsByResult;
    }

    private Map<Long, Integer> groupSnapshotChoiceLimits(Set<Long> snapshotIds) {
        Map<Long, Integer> choiceLimitsBySnapshot = new HashMap<>();
        List<QuestionSnapshot> snapshots = snapshotMapper.selectSnapshotByIds(snapshotIds);
        if (snapshots == null) {
            return choiceLimitsBySnapshot;
        }
        for (QuestionSnapshot snapshot : snapshots) {
            Integer choiceLimit = parseFrozenChoiceLimit(snapshot.getRawJson());
            if (choiceLimit != null) {
                choiceLimitsBySnapshot.put(snapshot.getId(), choiceLimit);
            }
        }
        return choiceLimitsBySnapshot;
    }

    private Integer parseFrozenChoiceLimit(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode config = root == null ? null : root.get("Config");
            JsonNode choiceLimit = config == null ? null : config.get("ChoiceLimit");
            return choiceLimit != null && choiceLimit.isIntegralNumber() ? choiceLimit.intValue() : null;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return null;
        }
    }

    private Map<Long, Set<Integer>> groupSnapshotOptions(Set<Long> snapshotIds) {
        Map<Long, Set<Integer>> optionsBySnapshot = new HashMap<>();
        for (QuestionOption option : optionMapper.selectBySnapshotIds(snapshotIds)) {
            if (option.getOptionId() != null) {
                optionsBySnapshot.computeIfAbsent(option.getSnapshotId(), key -> new HashSet<>())
                        .add(option.getOptionId());
            }
        }
        return optionsBySnapshot;
    }

    /**
     * 单个结果评分，状态优先级为 CANCELLED、接收凭证缺失、LATE、缺题、NO_ANSWER、非单选、规则评分。
     * 迟到先于答案判定：迟到结果永远无效，而无答案可能随官方答案公布变化。
     * 迟到判定委托 {@link PredictionLockService#isLate(Instant, Instant)}，规则只在锁定服务中定义一次；
     * 使用已持有行锁的批次截止与接收凭证 received_at，避免逐结果查询批次。
     * 接收凭证与结果时间同源，没有凭证无法证明可信来源，因此是 UNSCORED 而不是有效评分。
     */
    private ScoringDetail scoreResult(PredictionBatch batch, PredictionResult result, Inputs in, Instant now) {
        PredictionJob job = in.jobs().get(result.getJobId());
        Question question = in.questions().get(result.getQuestionId());
        OfficialAnswer answer = in.answers().get(result.getQuestionId());
        PredictionOutcomeReceipt receipt = in.receipts().get(result.getJobId());
        Instant receivedAt = receipt == null ? null : receipt.getReceivedAt();
        Long snapshotId = job == null ? null : job.getQuestionSnapshotId();
        Set<Integer> frozenOptions = snapshotId == null ? Set.of() : in.snapshotOptions().getOrDefault(snapshotId, Set.of());

        ScoreOutcome outcome;
        if (question != null && QUESTION_STATUS_CANCELLED.equalsIgnoreCase(question.getStatus())) {
            outcome = notCounted(ScoreStatus.CANCELLED, "QUESTION_CANCELLED");
        } else if (receivedAt == null) {
            outcome = notCounted(ScoreStatus.UNSCORED, "OUTCOME_RECEIPT_MISSING");
        } else if (lockService.isLate(batch.getPredictionDeadline(), receivedAt)) {
            outcome = notCounted(ScoreStatus.LATE, "RESULT_AFTER_DEADLINE");
        } else if (question == null) {
            outcome = notCounted(ScoreStatus.UNSCORED, "QUESTION_MISSING");
        } else if (answer == null) {
            outcome = notCounted(ScoreStatus.NO_ANSWER, "ANSWER_NOT_PUBLISHED");
        } else if (snapshotId == null || !in.choiceLimits().containsKey(snapshotId)) {
            outcome = notCounted(ScoreStatus.UNSCORED, "FROZEN_SNAPSHOT_CHOICE_LIMIT_MISSING");
        } else if (!Integer.valueOf(1).equals(in.choiceLimits().get(snapshotId))) {
            outcome = notCounted(ScoreStatus.UNSCORED, "QUESTION_NOT_SINGLE_CHOICE");
        } else if (frozenOptions.isEmpty()) {
            outcome = notCounted(ScoreStatus.UNSCORED, "FROZEN_SNAPSHOT_OPTIONS_MISSING");
        } else {
            // answer_content 是规范 JSON；仅在其为空时回退到 raw_json。
            String answerJson = answer.getAnswerContent() != null && !answer.getAnswerContent().isBlank()
                    ? answer.getAnswerContent() : answer.getRawJson();
            outcome = engine.score(in.selectedOptions().getOrDefault(result.getId(), List.of()),
                    answerJson, frozenOptions);
        }

        ScoringDetail detail = new ScoringDetail();
        detail.setBatchId(batch.getId());
        detail.setResultId(result.getId());
        detail.setQuestionId(result.getQuestionId());
        // 只有走完全部单选核验并计分的行才是 SINGLE，其余一律 UNKNOWN。
        detail.setQuestionType(outcome.status().isCounted() ? TYPE_SINGLE : TYPE_UNKNOWN);
        detail.setScore(outcome.score());
        detail.setMaxScore(outcome.maxScore());
        detail.setScoringRuleVersion(engine.ruleVersion());
        detail.setDetailJson(detailJson(batch, snapshotId, receivedAt, answer, outcome));
        // 只有计入总分的行才有评分时间，scored_at 为空即表示未评分。
        detail.setScoredAt(outcome.status().isCounted() ? now : null);
        detail.setCreatedAt(now);
        detail.setUpdatedAt(now);
        detail.setScoreStatus(outcome.status().name());
        return detail;
    }

    /**
     * 保存评分上下文，使逐题得分可以对照答案版本（content_hash）与接收时间复核。
     * <p>
     * 时间证明字段：答案存在时 {@code answerFirstObservedAt}（本系统首次观察到当前答案行的时间）与
     * {@code answerPublishedAt}（人工声明的发布时间）两个键必定存在，值缺失时写显式 JSON null，
     * 以区分“未知”与“未记录”；{@code answerTimingProof} 每行必写，规则见 {@link #answerTimingProof}。
     */
    private String detailJson(PredictionBatch batch, Long snapshotId, Instant receivedAt, OfficialAnswer answer,
                              ScoreOutcome outcome) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("scoringRuleVersion", engine.ruleVersion());
        node.put("status", outcome.status().name());
        if (outcome.reason() != null) {
            node.put("reason", outcome.reason());
        }
        if (outcome.predictedOptionId() != null) {
            node.put("predictedOptionId", outcome.predictedOptionId());
        }
        if (outcome.correctOptionId() != null) {
            node.put("correctOptionId", outcome.correctOptionId());
        }
        if (snapshotId != null) {
            node.put("questionSnapshotId", snapshotId);
        }
        if (answer != null) {
            node.put("officialAnswerId", answer.getId());
            if (answer.getContentHash() != null) {
                node.put("answerContentHash", answer.getContentHash());
            }
            putInstantOrNull(node, "answerFirstObservedAt", answer.getCreatedAt());
            putInstantOrNull(node, "answerPublishedAt", answer.getPublishedAt());
        }
        if (receivedAt != null) {
            node.put("receivedAt", receivedAt.toString());
        }
        if (batch.getPredictionDeadline() != null) {
            node.put("predictionDeadline", batch.getPredictionDeadline().toString());
        }
        node.put("answerTimingProof", answerTimingProof(receivedAt, answer).name());
        return node.toString();
    }

    /** 键总是写入；值为 null 时写显式 JSON null，而不是省略该键。 */
    private static void putInstantOrNull(ObjectNode node, String field, Instant value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    /**
     * 计算结果接收时间相对官方答案的时间证明等级，只做陈述，不参与评分。
     * <ol>
     *   <li>无答案、无接收时间，或发布时间与观察时间都为 NULL：{@code UNKNOWN}；</li>
     *   <li>{@code published_at} 非 NULL（目前只可能来自人工修订，只算人工声明）：
     *       接收早于它为 {@code BEFORE_DECLARED_PUBLICATION}，否则为 {@code NOT_BEFORE_DECLARED_PUBLICATION}；
     *       声明值按存储原值比较，不加精度余量；</li>
     *   <li>{@code published_at} 为 NULL 时退回本系统观察时间 {@code created_at}：
     *       接收不早于它为 {@code NOT_BEFORE_SYSTEM_OBSERVATION}；
     *       接收早于“截断到毫秒的观察时间减 {@link #OBSERVATION_PRECISION_MARGIN}”才是
     *       {@code BEFORE_SYSTEM_OBSERVATION_ONLY}，仅证明早于本系统观察，不证明早于官方发布；
     *       落在精度带内无法证明先后，记 {@code UNKNOWN}。</li>
     * </ol>
     * 精度余量的理由：created_at 由 Java {@code Instant.now()} 未截断写入 DATETIME(3)，MySQL 舍入可能进位到下一毫秒，
     * 使存储值晚于这次时钟读数至多 0.5ms（该读数本身晚于实际拉取 Feed 的时刻，差值没有上界，余量不覆盖这段延迟）；received_at 为 DATETIME(6)，舍入至多 0.5µs。
     * 两者均为 Java 时钟，同一实例内可比；多实例部署依赖各实例 NTP 同步。
     * 撤销后重新公布的答案是新行，created_at 随之重置，因此观察时间指“当前答案行”的首次观察。
     */
    private static AnswerTimingProof answerTimingProof(Instant receivedAt, OfficialAnswer answer) {
        if (answer == null || receivedAt == null) {
            return AnswerTimingProof.UNKNOWN;
        }
        Instant publishedAt = answer.getPublishedAt();
        if (publishedAt != null) {
            return receivedAt.isBefore(publishedAt)
                    ? AnswerTimingProof.BEFORE_DECLARED_PUBLICATION
                    : AnswerTimingProof.NOT_BEFORE_DECLARED_PUBLICATION;
        }
        Instant observedAt = answer.getCreatedAt();
        if (observedAt == null) {
            return AnswerTimingProof.UNKNOWN;
        }
        if (!receivedAt.isBefore(observedAt)) {
            return AnswerTimingProof.NOT_BEFORE_SYSTEM_OBSERVATION;
        }
        Instant provablyObservedAfter = observedAt.truncatedTo(ChronoUnit.MILLIS).minus(OBSERVATION_PRECISION_MARGIN);
        return receivedAt.isBefore(provablyObservedAfter)
                ? AnswerTimingProof.BEFORE_SYSTEM_OBSERVATION_ONLY
                : AnswerTimingProof.UNKNOWN;
    }

    /**
     * 汇总批次总分：只统计计入状态的明细；没有计分题时满分为 0、准确率为 null，
     * 与“全部答错”（满分大于 0、准确率为 0）区分。
     * 统计 JSON 同时给出题目总数、任务数、无结果数、明细数、有效评分数和各状态题数，
     * 任务数 = 明细数 + 无结果数，分母来源可逐项核对。
     */
    private BatchTotalScore aggregate(PredictionBatch batch, int jobCount, long noResultCount,
                                      List<ScoringDetail> details, Instant now) {
        Map<ScoreStatus, Integer> statusCounts = new EnumMap<>(ScoreStatus.class);
        for (ScoreStatus status : ScoreStatus.values()) {
            statusCounts.put(status, 0);
        }
        List<ScoringDetail> counted = new ArrayList<>();
        for (ScoringDetail detail : details) {
            ScoreStatus status = ScoreStatus.valueOf(detail.getScoreStatus());
            statusCounts.merge(status, 1, Integer::sum);
            if (status.isCounted()) {
                counted.add(detail);
            }
        }
        BigDecimal totalScore = sum(counted, ScoringDetail::getScore);
        BigDecimal maxScore = sum(counted, ScoringDetail::getMaxScore);

        ObjectNode stats = objectMapper.createObjectNode();
        stats.put("scoringRuleVersion", engine.ruleVersion());
        stats.put("questionCount", batch.getQuestionCount());
        stats.put("jobCount", jobCount);
        stats.put("noResultCount", noResultCount);
        stats.put("detailCount", details.size());
        stats.put("scoredQuestionCount", counted.size());
        ObjectNode statusNode = stats.putObject("statusCounts");
        statusCounts.forEach((status, count) -> statusNode.put(status.name(), count));
        ObjectNode typesNode = stats.putObject("types");
        Map<String, List<ScoringDetail>> byType = counted.stream().collect(
                Collectors.groupingBy(ScoringDetail::getQuestionType, LinkedHashMap::new, Collectors.toList()));
        byType.forEach((type, rows) -> {
            ObjectNode typeNode = typesNode.putObject(type);
            typeNode.put("score", sum(rows, ScoringDetail::getScore));
            typeNode.put("maxScore", sum(rows, ScoringDetail::getMaxScore));
            typeNode.put("scoredCount", rows.size());
        });

        BatchTotalScore total = new BatchTotalScore();
        total.setBatchId(batch.getId());
        total.setRoundId(batch.getRoundId());
        total.setTotalScore(totalScore);
        total.setMaxScore(maxScore);
        total.setAccuracyRate(maxScore.signum() > 0 ? totalScore.divide(maxScore, 4, RoundingMode.HALF_UP) : null);
        total.setTypeStatsJson(stats.toString());
        total.setCreatedAt(now);
        total.setUpdatedAt(now);
        return total;
    }

    private static BigDecimal sum(List<ScoringDetail> rows, Function<ScoringDetail, BigDecimal> getter) {
        return rows.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ScoreOutcome notCounted(ScoreStatus status, String reason) {
        return new ScoreOutcome(status, BigDecimal.ZERO, BigDecimal.ZERO, null, null, reason);
    }

    /** 评分循环需要的批量读取结果。 */
    private record Inputs(Map<Long, PredictionJob> jobs, Map<Long, Question> questions,
                          Map<Long, OfficialAnswer> answers, Map<Long, List<Integer>> selectedOptions,
                          Map<Long, PredictionOutcomeReceipt> receipts, Map<Long, Set<Integer>> snapshotOptions,
                          Map<Long, Integer> choiceLimits) {
    }

    /** 一整套待写入的明细与总分。 */
    private record ScoredBatch(List<ScoringDetail> details, BatchTotalScore total) {
    }

    /**
     * detail_json 中 answerTimingProof 的取值（D8）。
     * 名称刻意区分“人工声明的发布时间”与“本系统观察时间”，不存在暗示系统证明早于官方发布的等级。
     */
    private enum AnswerTimingProof {
        /** 缺少答案、接收时间或任何可比较的答案时间，或落在观察时间精度带内。 */
        UNKNOWN,
        /** 接收早于人工声明的发布时间。 */
        BEFORE_DECLARED_PUBLICATION,
        /** 接收不早于人工声明的发布时间。 */
        NOT_BEFORE_DECLARED_PUBLICATION,
        /** 发布时间未知，仅能证明接收早于本系统首次观察到当前答案行。 */
        BEFORE_SYSTEM_OBSERVATION_ONLY,
        /** 发布时间未知，接收不早于本系统首次观察到当前答案行。 */
        NOT_BEFORE_SYSTEM_OBSERVATION
    }
}

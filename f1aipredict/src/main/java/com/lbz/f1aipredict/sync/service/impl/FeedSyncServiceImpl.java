package com.lbz.f1aipredict.sync.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lbz.f1aipredict.common.ResourceNotFoundException;
import com.lbz.f1aipredict.question.entity.Question;
import com.lbz.f1aipredict.question.entity.QuestionOption;
import com.lbz.f1aipredict.question.entity.QuestionSnapshot;
import com.lbz.f1aipredict.question.mapper.QuestionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionOptionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionSnapshotMapper;
import com.lbz.f1aipredict.season.entity.MeetingSession;
import com.lbz.f1aipredict.season.entity.Round;
import com.lbz.f1aipredict.season.entity.Season;
import com.lbz.f1aipredict.season.mapper.MeetingSessionMapper;
import com.lbz.f1aipredict.season.mapper.RoundMapper;
import com.lbz.f1aipredict.season.mapper.SeasonMapper;
import com.lbz.f1aipredict.sync.FeedSyncException;
import com.lbz.f1aipredict.sync.client.F1PredictFeedClient;
import com.lbz.f1aipredict.sync.config.F1PredictFeedProperties;
import com.lbz.f1aipredict.sync.dto.RawPayloadDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordPageDto;
import com.lbz.f1aipredict.sync.dto.SyncRecordQuery;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.entity.FeedRawPayload;
import com.lbz.f1aipredict.sync.entity.SyncRecord;
import com.lbz.f1aipredict.sync.feed.LimitsFeedData;
import com.lbz.f1aipredict.sync.feed.LimitsFeedResponse;
import com.lbz.f1aipredict.sync.feed.LimitsFeedValue;
import com.lbz.f1aipredict.sync.feed.QuestionsFeedConfig;
import com.lbz.f1aipredict.sync.feed.QuestionsFeedData;
import com.lbz.f1aipredict.sync.feed.QuestionsFeedOption;
import com.lbz.f1aipredict.sync.feed.QuestionsFeedQuestion;
import com.lbz.f1aipredict.sync.feed.QuestionsFeedResponse;
import com.lbz.f1aipredict.sync.feed.QuestionsFeedValue;
import com.lbz.f1aipredict.sync.feed.RacedayFeedData;
import com.lbz.f1aipredict.sync.feed.RacedayFeedResponse;
import com.lbz.f1aipredict.sync.feed.RacedayFeedSession;
import com.lbz.f1aipredict.sync.service.FeedSyncService;
import com.lbz.f1aipredict.sync.store.SyncPersistenceStore;
import com.lbz.f1aipredict.sync.util.FeedSyncUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 赛程 Feed 同步实现：拉取 raceday JSON，按 SHA-256 幂等留档，
 * 并以 MeetingId（回退 MeetingNumber）把同一 Grand Prix 收成一个 round。
 */
@Service
public class FeedSyncServiceImpl implements FeedSyncService {

    /** 赛程数据源类型，写入 sync_record / feed_raw_payload */
    public static final String SOURCE_TYPE_SCHEDULE = "SCHEDULE";

    /** 题目数据源类型，写入 sync_record / feed_raw_payload */
    public static final String SOURCE_TYPE_QUESTIONS = "QUESTIONS";

    /** 限制 / 当前轮次数据源类型，写入 sync_record / feed_raw_payload */
    public static final String SOURCE_TYPE_LIMITS = "LIMITS";

    /** 当前轮次串联同步的汇总数据源类型（仅出现在返回 DTO，不单独写业务表） */
    public static final String SOURCE_TYPE_CURRENT = "CURRENT";

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_SKIPPED = "SKIPPED_UNCHANGED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String DEFAULT_SEASON_NAME_SUFFIX = " FIA Formula One World Championship";
    private static final String DEFAULT_SEASON_STATUS = "UPCOMING";
    private static final String DEFAULT_ROUND_STATUS = "SCHEDULED";
    private static final String DEFAULT_SESSION_STATUS = "SCHEDULED";
    private static final String SNAPSHOT_REASON_INITIAL = "INITIAL";
    private static final String SNAPSHOT_REASON_CHANGED = "CHANGED";

    private static final Logger log = LoggerFactory.getLogger(FeedSyncServiceImpl.class);

    private final F1PredictFeedClient feedClient;
    private final F1PredictFeedProperties properties;
    private final SyncPersistenceStore persistenceStore;
    private final SeasonMapper seasonMapper;
    private final RoundMapper roundMapper;
    private final MeetingSessionMapper meetingSessionMapper;
    private final QuestionMapper questionMapper;
    private final QuestionSnapshotMapper questionSnapshotMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final ObjectMapper objectMapper;

    /**
     * Spring 事务代理。syncCurrent 必须经此调用 syncSchedule / syncQuestions，
     * 否则 this.xxx() 自调用会绕过 {@code @Transactional}。
     * 测试 9 参构造器指向 this；CurrentTest spy 后再 bind 到 spy。
     */
    private FeedSyncService self;

    /**
     * 生产构造器：注入 {@code @Lazy} 自身代理，打破 impl ↔ 接口的循环依赖，
     * 让 syncCurrent 走代理以生效独立事务。禁止把整段 syncCurrent 包进一个大事务。
     */
    @Autowired
    public FeedSyncServiceImpl(F1PredictFeedClient feedClient,
                               F1PredictFeedProperties properties,
                               SyncPersistenceStore persistenceStore,
                               SeasonMapper seasonMapper,
                               RoundMapper roundMapper,
                               MeetingSessionMapper meetingSessionMapper,
                               QuestionMapper questionMapper,
                               QuestionSnapshotMapper questionSnapshotMapper,
                               QuestionOptionMapper questionOptionMapper,
                               @Lazy FeedSyncService self) {
        this(feedClient, properties, persistenceStore, seasonMapper, roundMapper,
                meetingSessionMapper, questionMapper, questionSnapshotMapper, questionOptionMapper);
        this.self = Objects.requireNonNull(self, "self must not be null");
    }

    /**
     * 测试友好构造器：现有 Mockito 用例仍 {@code new FeedSyncServiceImpl(...9 args)}。
     * self 指向 this；不经过 Spring 代理，单元测试不依赖事务切面。
     */
    public FeedSyncServiceImpl(F1PredictFeedClient feedClient,
                               F1PredictFeedProperties properties,
                               SyncPersistenceStore persistenceStore,
                               SeasonMapper seasonMapper,
                               RoundMapper roundMapper,
                               MeetingSessionMapper meetingSessionMapper,
                               QuestionMapper questionMapper,
                               QuestionSnapshotMapper questionSnapshotMapper,
                               QuestionOptionMapper questionOptionMapper) {
        this.feedClient = Objects.requireNonNull(feedClient, "feedClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.persistenceStore = Objects.requireNonNull(persistenceStore, "persistenceStore must not be null");
        this.seasonMapper = Objects.requireNonNull(seasonMapper, "seasonMapper must not be null");
        this.roundMapper = Objects.requireNonNull(roundMapper, "roundMapper must not be null");
        this.meetingSessionMapper = Objects.requireNonNull(meetingSessionMapper, "meetingSessionMapper must not be null");
        this.questionMapper = Objects.requireNonNull(questionMapper, "questionMapper must not be null");
        this.questionSnapshotMapper = Objects.requireNonNull(questionSnapshotMapper, "questionSnapshotMapper must not be null");
        this.questionOptionMapper = Objects.requireNonNull(questionOptionMapper, "questionOptionMapper must not be null");
        this.objectMapper = new ObjectMapper();
        this.self = this;
    }

    /**
     * 仅供单元测试：spy 包装后把 self 绑到 spy，否则 syncCurrent 仍打到未桩的真实实例。
     * 测试类不在 impl 包内，必须 public 才能调用。
     */
    public void setSelfForTests(FeedSyncService proxy) {
        this.self = Objects.requireNonNull(proxy, "proxy must not be null");
    }

    /**
     * 拉取赛程并幂等写入。客户端失败走 FAILED 且不碰业务表；
     * 成功路径的业务 upsert 与 SUCCESS 记录在同一事务中提交。
     */
    @Override
    @Transactional
    public SyncResultDto syncSchedule() {
        long startedAt = System.currentTimeMillis();
        String sourceUrl = buildSourceUrl();
        try {
            String rawJson = feedClient.fetchSchedule();
            return persistSchedule(rawJson, sourceUrl, startedAt);
        } catch (FeedSyncException ex) {
            log.error("赛程 Feed 拉取失败: {}", ex.getMessage());
            return writeFailed(SOURCE_TYPE_SCHEDULE, sourceUrl, null, null, ex.getMessage(), ex.getHttpStatus(), startedAt, null);
        }
    }

    /**
     * 计算哈希、留档、判重后按 Meeting 分组 upsert。
     */
    private SyncResultDto persistSchedule(String rawJson, String sourceUrl, long startedAt) {
        String json = rawJson == null ? "" : rawJson;
        String contentHash = FeedSyncUtils.sha256Hex(json);
        Long payloadId = persistenceStore.saveRawPayload(newPayload(SOURCE_TYPE_SCHEDULE, sourceUrl, contentHash, json, null));

        SyncRecord previous = persistenceStore.findLatestUnchanged(SOURCE_TYPE_SCHEDULE, contentHash);
        if (previous != null) {
            Long recordId = persistenceStore.saveSyncRecord(
                    newSyncRecord(SOURCE_TYPE_SCHEDULE, sourceUrl, contentHash, STATUS_SKIPPED, 200, null, startedAt, null));
            return result(SOURCE_TYPE_SCHEDULE, STATUS_SKIPPED, contentHash, recordId, payloadId, null);
        }

        try {
            int skippedSessions = upsertBusinessTables(json);
            String note = skippedSessions > 0 ? "skippedSessions=" + skippedSessions : null;
            Long recordId = persistenceStore.saveSyncRecord(
                    newSyncRecord(SOURCE_TYPE_SCHEDULE, sourceUrl, contentHash, STATUS_SUCCESS, 200, note, startedAt, null));
            return result(SOURCE_TYPE_SCHEDULE, STATUS_SUCCESS, contentHash, recordId, payloadId, note);
        } catch (JacksonException | IllegalArgumentException ex) {
            // 畸形 JSON：已留档，不写业务表，记 FAILED；其它运行时异常向外抛出以便事务回滚。
            log.error("赛程 JSON 解析失败: {}", ex.getMessage());
            return writeFailed(SOURCE_TYPE_SCHEDULE, sourceUrl, contentHash, payloadId, ex.getMessage(), 200, startedAt, null);
        }
    }

    /**
     * 拉取指定比赛日题目并幂等写入。客户端失败走 FAILED 且不碰业务表；
     * 成功路径的业务 upsert 与 SUCCESS 记录在同一事务中提交。
     */
    @Override
    @Transactional
    public SyncResultDto syncQuestions(Integer gamedayId) {
        long startedAt = System.currentTimeMillis();
        if (gamedayId == null) {
            throw new IllegalArgumentException("gamedayId must not be null");
        }
        String sourceUrl = buildQuestionsSourceUrl(gamedayId);
        try {
            String rawJson = feedClient.fetchQuestions(gamedayId);
            return persistQuestions(rawJson, gamedayId, sourceUrl, startedAt);
        } catch (FeedSyncException ex) {
            log.error("题目 Feed 拉取失败: gamedayId={}, error={}", gamedayId, ex.getMessage());
            return writeFailed(SOURCE_TYPE_QUESTIONS, sourceUrl, null, null, ex.getMessage(), ex.getHttpStatus(), startedAt, gamedayId);
        }
    }

    /**
     * 串行同步当前轮次：limits → schedule → questions。
     * limits 失败或缺少 gamedayId 时不调用后续步骤；schedule FAILED 后仍继续 questions。
     */
    @Override
    public SyncResultDto syncCurrent() {
        LimitsArchive limits = fetchAndArchiveLimits();
        if (!STATUS_SUCCESS.equals(limits.status())) {
            return currentResult(STATUS_FAILED, limits.contentHash(), limits.syncRecordId(), limits.payloadId(),
                    limits.errorMessage(), null, null, null);
        }

        Integer gamedayId = limits.gamedayId();
        // 经代理调用，保证 syncSchedule / syncQuestions 各自独立事务生效；
        // 赛程 FAILED 仍继续题目；HTTP 拉取不包进长事务。
        FeedSyncService target = self != null ? self : this;
        SyncResultDto scheduleResult = target.syncSchedule();
        SyncResultDto questionsResult = target.syncQuestions(gamedayId);
        String overall = bothChildrenOk(scheduleResult, questionsResult) ? STATUS_SUCCESS : STATUS_FAILED;
        String errorMessage = STATUS_FAILED.equals(overall) ? summarizeChildErrors(scheduleResult, questionsResult) : null;
        return currentResult(overall, limits.contentHash(), limits.syncRecordId(), limits.payloadId(),
                errorMessage, gamedayId, scheduleResult, questionsResult);
    }

    /**
     * 仅同步 limits：拉取、留档、解析 gamedayId，不跑赛程/题目。
     */
    @Override
    public SyncResultDto syncLimits() {
        LimitsArchive limits = fetchAndArchiveLimits();
        return SyncResultDto.builder()
                .sourceType(SOURCE_TYPE_LIMITS)
                .status(limits.status())
                .contentHash(limits.contentHash())
                .syncRecordId(limits.syncRecordId())
                .payloadId(limits.payloadId())
                .errorMessage(limits.errorMessage())
                .gamedayId(limits.gamedayId())
                .build();
    }

    /**
     * 分页查询同步记录：Store 持有 Mapper，本方法只做实体到 DTO 映射。
     */
    @Override
    public SyncRecordPageDto pageRecords(SyncRecordQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        query.clampPaging();
        Page<SyncRecord> page = persistenceStore.pageRecords(query);
        List<SyncRecordDto> items = new ArrayList<>();
        if (page.getRecords() != null) {
            for (SyncRecord record : page.getRecords()) {
                items.add(toRecordDto(record));
            }
        }
        return SyncRecordPageDto.builder()
                .items(items)
                .page(query.getPage())
                .size(query.getSize())
                .total(page.getTotal())
                .build();
    }

    /**
     * 按主键读取原始 JSON；缺失抛 ResourceNotFoundException 由全局处理器映射 404。
     */
    @Override
    public RawPayloadDto getRawPayload(Long payloadId) {
        Objects.requireNonNull(payloadId, "payloadId must not be null");
        FeedRawPayload payload = persistenceStore.findRawPayloadById(payloadId);
        if (payload == null) {
            throw new ResourceNotFoundException("Raw payload not found: " + payloadId);
        }
        return RawPayloadDto.builder()
                .payloadId(payload.getId())
                .sourceType(payload.getSourceType())
                .sourceUrl(payload.getSourceUrl())
                .gamedayId(payload.getGamedayId())
                .contentHash(payload.getContentHash())
                .rawJson(payload.getRawJson())
                .fetchedAt(payload.getFetchedAt())
                .build();
    }

    /**
     * 拉取 limits、留档并解析 gamedayId。HTTP 失败不留档；畸形 JSON / 缺 GamedayId 可留档后记 FAILED。
     * 供 {@link #syncLimits()} 与 {@link #syncCurrent()} 共用，避免两套复制逻辑。
     */
    private LimitsArchive fetchAndArchiveLimits() {
        long startedAt = System.currentTimeMillis();
        String limitsUrl = buildLimitsSourceUrl();
        String rawJson;
        try {
            rawJson = feedClient.fetchLimits();
        } catch (FeedSyncException ex) {
            // HTTP/网络失败：只记 LIMITS FAILED，禁止空白 hash 留档
            log.error("limits Feed 拉取失败: {}", ex.getMessage());
            SyncResultDto failed = writeFailed(
                    SOURCE_TYPE_LIMITS, limitsUrl, null, null, ex.getMessage(), ex.getHttpStatus(), startedAt, null);
            return new LimitsArchive(STATUS_FAILED, null, failed.getSyncRecordId(), null, ex.getMessage(), null);
        }

        String json = rawJson == null ? "" : rawJson;
        String contentHash = FeedSyncUtils.sha256Hex(json);
        Long payloadId = persistenceStore.saveRawPayload(
                newPayload(SOURCE_TYPE_LIMITS, limitsUrl, contentHash, json, null));

        Integer gamedayId;
        try {
            gamedayId = parseLimitsGamedayId(json);
        } catch (JacksonException | IllegalArgumentException ex) {
            log.error("limits JSON 解析失败: {}", ex.getMessage());
            SyncResultDto failed = writeFailed(
                    SOURCE_TYPE_LIMITS, limitsUrl, contentHash, payloadId, ex.getMessage(), 200, startedAt, null);
            return new LimitsArchive(STATUS_FAILED, contentHash, failed.getSyncRecordId(), payloadId, ex.getMessage(), null);
        }

        if (gamedayId == null) {
            String missing = "limits JSON missing GamedayId";
            log.error("limits 缺少 GamedayId");
            SyncResultDto failed = writeFailed(
                    SOURCE_TYPE_LIMITS, limitsUrl, contentHash, payloadId, missing, 200, startedAt, null);
            return new LimitsArchive(STATUS_FAILED, contentHash, failed.getSyncRecordId(), payloadId, missing, null);
        }

        Long recordId = persistenceStore.saveSyncRecord(
                newSyncRecord(SOURCE_TYPE_LIMITS, limitsUrl, contentHash, STATUS_SUCCESS, 200, null, startedAt, gamedayId));
        return new LimitsArchive(STATUS_SUCCESS, contentHash, recordId, payloadId, null, gamedayId);
    }

    /**
     * 实体转管理端 DTO，主键映射为 syncRecordId。
     */
    private static SyncRecordDto toRecordDto(SyncRecord record) {
        return SyncRecordDto.builder()
                .syncRecordId(record.getId())
                .sourceType(record.getSourceType())
                .sourceUrl(record.getSourceUrl())
                .gamedayId(record.getGamedayId())
                .contentHash(record.getContentHash())
                .status(record.getStatus())
                .httpStatus(record.getHttpStatus())
                .errorMessage(record.getErrorMessage())
                .durationMs(record.getDurationMs())
                .syncedAt(record.getSyncedAt())
                .build();
    }

    /**
     * 计算整包哈希、留档、判重后逐题 upsert。
     */
    private SyncResultDto persistQuestions(String rawJson, Integer gamedayId, String sourceUrl, long startedAt) {
        String json = rawJson == null ? "" : rawJson;
        String contentHash = FeedSyncUtils.sha256Hex(json);
        Long payloadId = persistenceStore.saveRawPayload(newPayload(SOURCE_TYPE_QUESTIONS, sourceUrl, contentHash, json, gamedayId));

        SyncRecord previous = persistenceStore.findLatestUnchanged(SOURCE_TYPE_QUESTIONS, contentHash);
        if (previous != null) {
            Long recordId = persistenceStore.saveSyncRecord(
                    newSyncRecord(SOURCE_TYPE_QUESTIONS, sourceUrl, contentHash, STATUS_SKIPPED, 200, null, startedAt, gamedayId));
            return result(SOURCE_TYPE_QUESTIONS, STATUS_SKIPPED, contentHash, recordId, payloadId, null);
        }

        try {
            int skippedQuestions = upsertQuestions(json, gamedayId);
            String note = skippedQuestions > 0 ? "skippedQuestions=" + skippedQuestions : null;
            Long recordId = persistenceStore.saveSyncRecord(
                    newSyncRecord(SOURCE_TYPE_QUESTIONS, sourceUrl, contentHash, STATUS_SUCCESS, 200, note, startedAt, gamedayId));
            return result(SOURCE_TYPE_QUESTIONS, STATUS_SUCCESS, contentHash, recordId, payloadId, note);
        } catch (JacksonException | IllegalArgumentException ex) {
            // 畸形 JSON：已留档，不写业务表，记 FAILED；其它运行时异常向外抛出以便事务回滚。
            log.error("题目 JSON 解析失败: gamedayId={}, error={}", gamedayId, ex.getMessage());
            return writeFailed(SOURCE_TYPE_QUESTIONS, sourceUrl, contentHash, payloadId, ex.getMessage(), 200, startedAt, gamedayId);
        }
    }

    /**
     * 解析题目 Feed 并逐题 upsert question / snapshot / option。
     * 若该 gameday 无 meeting_session，则全部题目被跳过，仍记 SUCCESS。
     *
     * @return 因缺少 round_id 等原因跳过的题目数
     */
    private int upsertQuestions(String json, Integer gamedayId) {
        Long roundId = resolveRoundId(gamedayId);
        if (roundId == null) {
            log.warn("gamedayId={} 无对应 meeting_session，跳过全部题目", gamedayId);
            List<QuestionsFeedQuestion> questions = extractQuestions(parseQuestionsResponse(json));
            return questions.size();
        }

        List<QuestionsFeedQuestion> questions = extractQuestions(parseQuestionsResponse(json));
        if (questions.isEmpty()) {
            log.info("题目 Feed Data.Value.Questions 为空，跳过业务 upsert: gamedayId={}", gamedayId);
            return 0;
        }

        int skipped = 0;
        for (QuestionsFeedQuestion question : questions) {
            if (question == null || question.getId() == null) {
                skipped++;
                log.warn("跳过缺少 Id 的题目: gamedayId={}", gamedayId);
                continue;
            }
            try {
                upsertSingleQuestion(question, gamedayId, roundId);
            } catch (Exception ex) {
                skipped++;
                log.warn("同步单题失败: gamedayId={}, sourceQuestionId={}, error={}",
                        gamedayId, question.getId(), ex.getMessage(), ex);
            }
        }
        return skipped;
    }

    /**
     * 解析题目 Feed 原始 JSON。
     */
    private QuestionsFeedResponse parseQuestionsResponse(String json) {
        return objectMapper.readValue(json, QuestionsFeedResponse.class);
    }

    /**
     * 从解析结果中提取 Questions 列表，任意层级缺失均返回空列表。
     */
    private static List<QuestionsFeedQuestion> extractQuestions(QuestionsFeedResponse response) {
        if (response == null || response.getData() == null) {
            return List.of();
        }
        QuestionsFeedData data = response.getData();
        if (data.getValue() == null) {
            return List.of();
        }
        QuestionsFeedValue value = data.getValue();
        if (value.getQuestions() == null) {
            return List.of();
        }
        return value.getQuestions();
    }

    /**
     * 通过 meeting_session.gameday_id 解析 round_id；无记录时返回 null。
     */
    private Long resolveRoundId(Integer gamedayId) {
        MeetingSession session = meetingSessionMapper.selectByGamedayId(gamedayId);
        return session == null ? null : session.getRoundId();
    }

    /**
     * 单题幂等 upsert：新题则 INITIAL 快照；内容未变则只刷新 last_synced_at；
     * 内容变化则新增 CHANGED 快照并更新当前状态。
     */
    private void upsertSingleQuestion(QuestionsFeedQuestion feedQuestion, Integer gamedayId, Long roundId) {
        String questionJson = objectMapper.writeValueAsString(feedQuestion);
        String contentHash = FeedSyncUtils.sha256Hex(questionJson);

        Question existing = questionMapper.selectByGamedayIdAndSourceQuestionId(gamedayId, feedQuestion.getId());
        Instant now = Instant.now();

        if (existing == null) {
            insertNewQuestion(feedQuestion, gamedayId, roundId, contentHash, questionJson, now);
            return;
        }

        // 防御性校验：确保查到的题目确实属于当前 gameday，避免误改其它比赛日数据
        if (existing.getGamedayId() == null || !existing.getGamedayId().equals(gamedayId)) {
            log.warn("查到的题目 gamedayId 不匹配，跳过更新: expected={}, actual={}",
                    gamedayId, existing.getGamedayId());
            return;
        }

        if (contentHash.equals(existing.getContentHash())) {
            existing.setLastSyncedAt(now);
            questionMapper.updateById(existing);
            return;
        }

        updateChangedQuestion(existing, feedQuestion, contentHash, questionJson, now);
    }

    /**
     * 插入新题： INITIAL 快照 + 选项，并回写 latest_snapshot_id。
     */
    private void insertNewQuestion(QuestionsFeedQuestion feedQuestion, Integer gamedayId, Long roundId,
                                   String contentHash, String questionJson, Instant now) {
        Question question = new Question();
        question.setRoundId(roundId);
        question.setGamedayId(gamedayId);
        question.setSourceQuestionId(feedQuestion.getId());
        question.setQuestionNo(feedQuestion.getNo());
        question.setQuestionText(feedQuestion.getText());
        question.setSubText(feedQuestion.getSubText());
        question.setOptionTemplateId(feedQuestion.getOptionTemplateId());
        question.setChoiceLimit(extractChoiceLimit(feedQuestion.getConfig()));
        question.setStatus(String.valueOf(feedQuestion.getStatus()));
        question.setContentHash(contentHash);
        question.setFirstSeenAt(now);
        question.setLastSyncedAt(now);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);
        questionMapper.insert(question);

        Long snapshotId = insertSnapshot(question.getId(), 1, SNAPSHOT_REASON_INITIAL, contentHash, questionJson, now);
        question.setLatestSnapshotId(snapshotId);
        questionMapper.updateById(question);

        insertOptions(snapshotId, feedQuestion.getOptions(), now);
    }

    /**
     * 内容变化时：新增 CHANGED 快照 + 选项，并更新题目当前状态。
     */
    private void updateChangedQuestion(Question existing, QuestionsFeedQuestion feedQuestion,
                                       String contentHash, String questionJson, Instant now) {
        Integer maxSnapshotNo = questionSnapshotMapper.selectMaxSnapshotNo(existing.getId());
        int nextSnapshotNo = maxSnapshotNo == null ? 1 : maxSnapshotNo + 1;

        Long snapshotId = insertSnapshot(existing.getId(), nextSnapshotNo, SNAPSHOT_REASON_CHANGED,
                contentHash, questionJson, now);

        existing.setQuestionText(feedQuestion.getText());
        existing.setSubText(feedQuestion.getSubText());
        existing.setOptionTemplateId(feedQuestion.getOptionTemplateId());
        existing.setChoiceLimit(extractChoiceLimit(feedQuestion.getConfig()));
        existing.setStatus(String.valueOf(feedQuestion.getStatus()));
        existing.setContentHash(contentHash);
        existing.setLatestSnapshotId(snapshotId);
        existing.setLastSyncedAt(now);
        existing.setUpdatedAt(now);
        questionMapper.updateById(existing);

        insertOptions(snapshotId, feedQuestion.getOptions(), now);
    }

    /**
     * 插入快照并返回主键。
     */
    private Long insertSnapshot(Long questionId, int snapshotNo, String reason,
                                String contentHash, String rawJson, Instant now) {
        QuestionSnapshot snapshot = new QuestionSnapshot();
        snapshot.setQuestionId(questionId);
        snapshot.setSnapshotNo(snapshotNo);
        snapshot.setContentHash(contentHash);
        snapshot.setRawJson(rawJson);
        snapshot.setSnapshotReason(reason);
        snapshot.setCreatedAt(now);
        questionSnapshotMapper.insert(snapshot);
        return snapshot.getId();
    }

    /**
     * 为指定快照批量插入选项，option_no 从 0 开始。
     */
    private void insertOptions(Long snapshotId, List<QuestionsFeedOption> feedOptions, Instant now) {
        if (feedOptions == null || feedOptions.isEmpty()) {
            return;
        }
        int optionNo = 0;
        for (QuestionsFeedOption feedOption : feedOptions) {
            if (feedOption == null) {
                optionNo++;
                continue;
            }
            QuestionOption option = new QuestionOption();
            option.setSnapshotId(snapshotId);
            option.setOptionNo(optionNo++);
            option.setOptionId(feedOption.getId());
            option.setOptionText(feedOption.getValue());
            option.setPoints(parsePoints(feedOption.getPoints()));
            option.setChance(parseChance(feedOption.getChance()));
            option.setIsAnswer(false);
            option.setCreatedAt(now);
            questionOptionMapper.insert(option);
        }
    }

    /**
     * 提取 ChoiceLimit；Config 缺失时返回 null。
     */
    private static Integer extractChoiceLimit(QuestionsFeedConfig config) {
        return config == null ? null : config.getChoiceLimit();
    }

    /**
     * 解析 Points 字符串为 Integer；空或无法解析时返回 null。
     */
    private static Integer parsePoints(String points) {
        if (points == null || points.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(points.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 解析 Chance 字符串为 BigDecimal（按源值存储，如 83 存为 83）；空或无法解析时返回 null。
     */
    private static BigDecimal parseChance(String chance) {
        if (chance == null || chance.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(chance.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 构造题目 Feed 源 URL：替换 questionsPath 中的 {gamedayId} 占位符。
     */
    private String buildQuestionsSourceUrl(Integer gamedayId) {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = properties.getQuestionsPath() == null ? "" : properties.getQuestionsPath();
        path = path.replace("{gamedayId}", String.valueOf(gamedayId));
        if (path.isEmpty()) {
            return base;
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    /**
     * 解析 Feed 并 upsert season / round / meeting_session。空 Value 视为成功、零写入。
     *
     * @return 因空白 session_key 等原因跳过的 Session 行数
     */
    private int upsertBusinessTables(String json) {
        RacedayFeedResponse response = objectMapper.readValue(json, RacedayFeedResponse.class);
        List<RacedayFeedSession> sessions = extractSessions(response);
        if (sessions.isEmpty()) {
            log.info("赛程 Feed Data.Value 为空，跳过业务 upsert");
            return 0;
        }

        Map<Integer, List<RacedayFeedSession>> byYear = new LinkedHashMap<>();
        int skipped = 0;
        for (RacedayFeedSession session : sessions) {
            Integer year = parseYear(session.getSeason());
            if (year == null) {
                skipped++;
                log.warn("跳过无法解析 Season 的 Session: raceId={}", session.getRaceId());
                continue;
            }
            byYear.computeIfAbsent(year, ignored -> new ArrayList<>()).add(session);
        }

        for (Map.Entry<Integer, List<RacedayFeedSession>> yearEntry : byYear.entrySet()) {
            Season season = upsertSeason(yearEntry.getKey());
            skipped += upsertRoundsAndSessions(season, yearEntry.getValue());
        }
        return skipped;
    }

    /**
     * 同一赛季内按 MeetingId（回退 MeetingNumber）分组，一场大奖赛只对应一个 round。
     */
    private int upsertRoundsAndSessions(Season season, List<RacedayFeedSession> sessions) {
        Map<String, List<RacedayFeedSession>> byMeeting = new LinkedHashMap<>();
        int skipped = 0;
        for (RacedayFeedSession session : sessions) {
            String groupKey = groupingKey(session);
            if (groupKey == null) {
                skipped++;
                log.warn("跳过缺少 MeetingId/MeetingNumber 的 Session: raceId={}", session.getRaceId());
                continue;
            }
            byMeeting.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(session);
        }

        for (List<RacedayFeedSession> group : byMeeting.values()) {
            RacedayFeedSession sample = group.get(0);
            if (sample.getMeetingNumber() == null) {
                skipped += group.size();
                log.warn("跳过缺少 MeetingNumber 的分站分组: meetingId={}", sample.getMeetingId());
                continue;
            }
            Round round = upsertRound(season.getId(), sample, group);
            for (RacedayFeedSession session : group) {
                if (!upsertSession(round.getId(), session)) {
                    skipped++;
                }
            }
        }
        return skipped;
    }

    /**
     * 按年份 upsert 赛季；名称缺失时使用默认世界锦标赛全称。
     */
    private Season upsertSeason(int year) {
        Instant now = Instant.now();
        String name = year + DEFAULT_SEASON_NAME_SUFFIX;
        Season existing = seasonMapper.selectByYear(year);
        if (existing == null) {
            Season created = new Season();
            created.setYear(year);
            created.setName(name);
            created.setStatus(DEFAULT_SEASON_STATUS);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            seasonMapper.insert(created);
            return created;
        }
        existing.setName(name);
        existing.setUpdatedAt(now);
        seasonMapper.updateById(existing);
        return existing;
    }

    /**
     * 按 (season_id, MeetingNumber) upsert 分站；起止日期取该组 Session 的 UTC 日期 min/max。
     */
    private Round upsertRound(Long seasonId, RacedayFeedSession sample, List<RacedayFeedSession> group) {
        Instant now = Instant.now();
        LocalDateRange range = computeUtcDateRange(group);
        Round existing = roundMapper.selectBySeasonIdAndRoundNumber(seasonId, sample.getMeetingNumber());
        if (existing == null) {
            Round created = new Round();
            created.setSeasonId(seasonId);
            created.setRoundNumber(sample.getMeetingNumber());
            applyRoundFields(created, sample, range);
            created.setStatus(DEFAULT_ROUND_STATUS);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            roundMapper.insert(created);
            return created;
        }
        applyRoundFields(existing, sample, range);
        existing.setUpdatedAt(now);
        roundMapper.updateById(existing);
        return existing;
    }

    /**
     * 按 session_key upsert；空白 FOMMEETINGSESSIONKEY 跳过该行，不把 0 当键。
     *
     * @return true 表示已写入或更新，false 表示跳过
     */
    private boolean upsertSession(Long roundId, RacedayFeedSession feedSession) {
        Integer sessionKey = parseSessionKey(feedSession.getFomMeetingSessionKey());
        if (sessionKey == null) {
            log.warn("跳过空白 FOMMEETINGSESSIONKEY 的 Session: raceId={}, meetingId={}",
                    feedSession.getRaceId(), feedSession.getMeetingId());
            return false;
        }
        Instant now = Instant.now();
        Instant startUtc = parseIsoInstant(feedSession.getSessionStartDateIso8601());
        Instant endUtc = parseIsoInstant(feedSession.getSessionEndDateIso8601());
        MeetingSession existing = meetingSessionMapper.selectBySessionKey(sessionKey);
        if (existing == null) {
            MeetingSession created = new MeetingSession();
            created.setRoundId(roundId);
            created.setMeetingKey(feedSession.getMeetingId());
            created.setSessionKey(sessionKey);
            created.setSessionName(feedSession.getSessionName());
            created.setSessionType(feedSession.getSessionType());
            created.setGamedayId(feedSession.getGamedayId());
            created.setStartDateUtc(startUtc);
            created.setEndDateUtc(endUtc);
            created.setStatus(DEFAULT_SESSION_STATUS);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            meetingSessionMapper.insert(created);
            return true;
        }
        existing.setRoundId(roundId);
        existing.setMeetingKey(feedSession.getMeetingId());
        existing.setSessionName(feedSession.getSessionName());
        existing.setSessionType(feedSession.getSessionType());
        existing.setGamedayId(feedSession.getGamedayId());
        existing.setStartDateUtc(startUtc);
        existing.setEndDateUtc(endUtc);
        existing.setUpdatedAt(now);
        meetingSessionMapper.updateById(existing);
        return true;
    }

    private static void applyRoundFields(Round round, RacedayFeedSession sample, LocalDateRange range) {
        round.setGrandPrixName(sample.getMeetingName());
        round.setOfficialName(sample.getMeetingOfficialName());
        round.setCircuitName(sample.getCircuitOfficialName());
        round.setCountry(sample.getCountryName());
        round.setLocality(sample.getMeetingLocation());
        round.setStartDate(range.startDate());
        round.setEndDate(range.endDate());
    }

    /**
     * 分组键：优先 MeetingId，缺失则回退 MeetingNumber。禁止使用 RaceId。
     */
    private static String groupingKey(RacedayFeedSession session) {
        if (session.getMeetingId() != null) {
            return "meetingId:" + session.getMeetingId();
        }
        if (session.getMeetingNumber() != null) {
            return "meetingNumber:" + session.getMeetingNumber();
        }
        return null;
    }

    private static List<RacedayFeedSession> extractSessions(RacedayFeedResponse response) {
        if (response == null || response.getData() == null) {
            return List.of();
        }
        RacedayFeedData data = response.getData();
        if (data.getValue() == null || data.getValue().isEmpty()) {
            return List.of();
        }
        return data.getValue();
    }

    private static Integer parseYear(String season) {
        if (season == null || season.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(season.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseSessionKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Feed 时间为带偏移的 ISO-8601，必须先 OffsetDateTime 再转 Instant（Instant.parse 不接受 +11:00）。
     */
    private static Instant parseIsoInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(iso.trim()).toInstant();
    }

    private static LocalDateRange computeUtcDateRange(List<RacedayFeedSession> group) {
        LocalDate min = null;
        LocalDate max = null;
        for (RacedayFeedSession session : group) {
            Instant start = parseIsoInstant(session.getSessionStartDateIso8601());
            Instant end = parseIsoInstant(session.getSessionEndDateIso8601());
            min = minDate(min, toUtcDate(start));
            max = maxDate(max, toUtcDate(end));
            max = maxDate(max, toUtcDate(start));
            min = minDate(min, toUtcDate(end));
        }
        return new LocalDateRange(min, max);
    }

    private static LocalDate toUtcDate(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static LocalDate minDate(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private static LocalDate maxDate(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private String buildSourceUrl() {
        return joinBaseAndPath(properties.getSchedulePath());
    }

    /**
     * 构造 limits Feed 源 URL：baseUrl + limitsPath。
     */
    private String buildLimitsSourceUrl() {
        return joinBaseAndPath(properties.getLimitsPath());
    }

    /**
     * 拼接 Feed 根地址与相对路径，去掉多余斜杠。
     */
    private String joinBaseAndPath(String pathValue) {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = pathValue == null ? "" : pathValue;
        if (path.isEmpty()) {
            return base;
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    /**
     * 从 limits JSON 解析当前 gamedayId。
     * 优先 Data.Value.GamedayId，再回退 Value / 根上的 CurrentGamedayId、currentGamedayId、gamedayId。
     * 禁止把 RaceId 当比赛日。
     */
    Integer parseLimitsGamedayId(String json) {
        LimitsFeedResponse response = objectMapper.readValue(json, LimitsFeedResponse.class);
        if (response == null) {
            return null;
        }
        LimitsFeedData data = response.getData();
        LimitsFeedValue value = data == null ? null : data.getValue();
        if (value != null) {
            Integer fromValue = firstNonNull(
                    value.getGamedayId(),
                    value.getCurrentGamedayIdPascal(),
                    value.getCurrentGamedayIdCamel(),
                    value.getGamedayIdCamel());
            if (fromValue != null) {
                return fromValue;
            }
        }
        return firstNonNull(
                response.getGamedayId(),
                response.getCurrentGamedayIdPascal(),
                response.getCurrentGamedayIdCamel(),
                response.getGamedayIdCamel());
    }

    private static Integer firstNonNull(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 子结果均为 SUCCESS 或 SKIPPED_UNCHANGED 才算整体成功。
     */
    private static boolean bothChildrenOk(SyncResultDto scheduleResult, SyncResultDto questionsResult) {
        return isOkStatus(scheduleResult) && isOkStatus(questionsResult);
    }

    private static boolean isOkStatus(SyncResultDto child) {
        if (child == null || child.getStatus() == null) {
            return false;
        }
        return STATUS_SUCCESS.equals(child.getStatus()) || STATUS_SKIPPED.equals(child.getStatus());
    }

    private static String summarizeChildErrors(SyncResultDto scheduleResult, SyncResultDto questionsResult) {
        StringBuilder builder = new StringBuilder();
        appendChildError(builder, SOURCE_TYPE_SCHEDULE, scheduleResult);
        appendChildError(builder, SOURCE_TYPE_QUESTIONS, questionsResult);
        return builder.isEmpty() ? "child sync FAILED" : builder.toString();
    }

    private static void appendChildError(StringBuilder builder, String label, SyncResultDto child) {
        if (isOkStatus(child)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("; ");
        }
        builder.append(label).append('=');
        if (child == null) {
            builder.append("missing");
            return;
        }
        String message = child.getErrorMessage();
        builder.append(child.getStatus());
        if (message != null && !message.isBlank()) {
            builder.append(':').append(message);
        }
    }

    private static SyncResultDto currentResult(String status, String contentHash, Long recordId, Long payloadId,
                                               String errorMessage, Integer gamedayId,
                                               SyncResultDto scheduleResult, SyncResultDto questionsResult) {
        return SyncResultDto.builder()
                .sourceType(SOURCE_TYPE_CURRENT)
                .status(status)
                .contentHash(contentHash)
                .syncRecordId(recordId)
                .payloadId(payloadId)
                .errorMessage(errorMessage)
                .gamedayId(gamedayId)
                .scheduleResult(scheduleResult)
                .questionsResult(questionsResult)
                .build();
    }

    private FeedRawPayload newPayload(String sourceType, String sourceUrl, String contentHash,
                                      String rawJson, Integer gamedayId) {
        FeedRawPayload payload = new FeedRawPayload();
        payload.setSourceType(sourceType);
        payload.setSourceUrl(sourceUrl);
        payload.setGamedayId(gamedayId);
        payload.setContentHash(contentHash);
        payload.setRawJson(rawJson);
        return payload;
    }

    private SyncRecord newSyncRecord(String sourceType, String sourceUrl, String contentHash, String status,
                                     Integer httpStatus, String errorMessage, long startedAt,
                                     Integer gamedayId) {
        SyncRecord record = new SyncRecord();
        record.setSourceType(sourceType);
        record.setSourceUrl(sourceUrl);
        record.setGamedayId(gamedayId);
        record.setContentHash(contentHash);
        record.setStatus(status);
        record.setHttpStatus(httpStatus);
        record.setErrorMessage(errorMessage);
        record.setDurationMs((int) Math.max(0, System.currentTimeMillis() - startedAt));
        return record;
    }

    private SyncResultDto writeFailed(String sourceType, String sourceUrl, String contentHash, Long payloadId,
                                      String errorMessage, Integer httpStatus, long startedAt,
                                      Integer gamedayId) {
        Long recordId = persistenceStore.saveSyncRecord(
                newSyncRecord(sourceType, sourceUrl, contentHash, STATUS_FAILED, httpStatus, errorMessage, startedAt, gamedayId));
        return result(sourceType, STATUS_FAILED, contentHash, recordId, payloadId, errorMessage);
    }

    private static SyncResultDto result(String sourceType, String status, String contentHash, Long recordId,
                                        Long payloadId, String errorMessage) {
        return SyncResultDto.builder()
                .sourceType(sourceType)
                .status(status)
                .contentHash(contentHash)
                .syncRecordId(recordId)
                .payloadId(payloadId)
                .errorMessage(errorMessage)
                .build();
    }

    private record LocalDateRange(LocalDate startDate, LocalDate endDate) {
    }

    /**
     * limits 拉取/留档中间结果，供 syncLimits 与 syncCurrent 共用。
     * HTTP 失败时 contentHash/payloadId/gamedayId 可空。
     */
    private record LimitsArchive(String status, String contentHash, Long syncRecordId,
                                 Long payloadId, String errorMessage, Integer gamedayId) {
    }
}

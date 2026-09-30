package com.lbz.f1aipredict.sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.question.entity.Question;
import com.lbz.f1aipredict.question.entity.QuestionOption;
import com.lbz.f1aipredict.question.entity.QuestionSnapshot;
import com.lbz.f1aipredict.question.mapper.QuestionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionOptionMapper;
import com.lbz.f1aipredict.question.mapper.QuestionSnapshotMapper;
import com.lbz.f1aipredict.season.entity.MeetingSession;
import com.lbz.f1aipredict.season.mapper.MeetingSessionMapper;
import com.lbz.f1aipredict.season.mapper.RoundMapper;
import com.lbz.f1aipredict.season.mapper.SeasonMapper;
import com.lbz.f1aipredict.sync.FeedSyncException;
import com.lbz.f1aipredict.sync.client.F1PredictFeedClient;
import com.lbz.f1aipredict.sync.config.F1PredictFeedProperties;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.entity.FeedRawPayload;
import com.lbz.f1aipredict.sync.entity.SyncRecord;
import com.lbz.f1aipredict.sync.feed.QuestionsFeedQuestion;
import com.lbz.f1aipredict.sync.feed.QuestionsFeedResponse;
import com.lbz.f1aipredict.sync.service.impl.FeedSyncServiceImpl;
import com.lbz.f1aipredict.sync.store.SyncPersistenceStore;
import com.lbz.f1aipredict.sync.util.FeedSyncUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * syncQuestions 单元测试：Mock 客户端 + Store + 题目相关 Mapper，禁止打真实 Feed。
 */
@ExtendWith(MockitoExtension.class)
class FeedSyncServiceQuestionsTest {

    private static final String SOURCE_URL =
            "https://f1predict.formula1.com/feeds/questions/42/questions_en.json";
    private static final Integer GAMEDAY_ID = 42;
    private static final Long ROUND_ID = 10L;
    /** Boot 3.5.16 / Jackson 2：本测试只解析 Feed 字符串字段，无需 JavaTimeModule。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private F1PredictFeedClient feedClient;

    @Mock
    private SyncPersistenceStore persistenceStore;

    @Mock
    private SeasonMapper seasonMapper;

    @Mock
    private RoundMapper roundMapper;

    @Mock
    private MeetingSessionMapper meetingSessionMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private QuestionSnapshotMapper questionSnapshotMapper;

    @Mock
    private QuestionOptionMapper questionOptionMapper;

    private FeedSyncService service;
    private F1PredictFeedProperties properties;

    private String sampleJson;
    private String sampleHash;

    @BeforeEach
    void setUp() throws IOException {
        sampleJson = readClasspath("/sync/questions-sample.json");
        sampleHash = FeedSyncUtils.sha256Hex(sampleJson);

        properties = new F1PredictFeedProperties();
        properties.setBaseUrl("https://f1predict.formula1.com");
        properties.setSchedulePath("/feeds/schedule/raceday_en.json");
        properties.setQuestionsPath("/feeds/questions/{gamedayId}/questions_en.json");
        properties.setLimitsPath("/feeds/limits/constraints.json");
        properties.setMixApiPath("/feeds/live/mixapi.json");
        properties.setWebConfigPath("/feeds/apps/web_config.json");
        service = new FeedSyncServiceImpl(
                feedClient, properties, persistenceStore,
                seasonMapper, roundMapper, meetingSessionMapper,
                questionMapper, questionSnapshotMapper, questionOptionMapper);
    }

    @Test
    @DisplayName("首次同步：2 道题、2 个 INITIAL 快照、选项入库，状态 SUCCESS，payload 与 gamedayId 正确")
    void firstSync_twoQuestions_insertsInitialSnapshotsAndOptions() throws Exception {
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(sampleJson);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged(eq("QUESTIONS"), eq(sampleHash))).thenReturn(null);
        stubMeetingSessionRound();
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(eq(GAMEDAY_ID), anyInt())).thenReturn(null);
        stubQuestionInsertIds(1001L, 1002L);
        stubSnapshotInsertIds(2001L, 2002L);

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getSourceType()).isEqualTo("QUESTIONS");
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getContentHash()).isEqualTo(sampleHash).hasSize(64);
        assertThat(result.getPayloadId()).isEqualTo(100L);
        assertThat(result.getSyncRecordId()).isEqualTo(200L);

        ArgumentCaptor<FeedRawPayload> payloadCaptor = ArgumentCaptor.forClass(FeedRawPayload.class);
        verify(persistenceStore, times(1)).saveRawPayload(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getSourceType()).isEqualTo("QUESTIONS");
        assertThat(payloadCaptor.getValue().getSourceUrl()).isEqualTo(SOURCE_URL);
        assertThat(payloadCaptor.getValue().getGamedayId()).isEqualTo(GAMEDAY_ID);
        assertThat(payloadCaptor.getValue().getContentHash()).isEqualTo(sampleHash);
        assertThat(payloadCaptor.getValue().getRawJson()).isEqualTo(sampleJson);

        ArgumentCaptor<SyncRecord> recordCaptor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(persistenceStore).saveSyncRecord(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(recordCaptor.getValue().getSourceType()).isEqualTo("QUESTIONS");
        assertThat(recordCaptor.getValue().getGamedayId()).isEqualTo(GAMEDAY_ID);
        assertThat(recordCaptor.getValue().getContentHash()).isEqualTo(sampleHash);

        ArgumentCaptor<Question> questionCaptor = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper, times(2)).insert(questionCaptor.capture());
        List<Question> insertedQuestions = questionCaptor.getAllValues();
        assertThat(insertedQuestions).extracting(Question::getSourceQuestionId).containsExactlyInAnyOrder(109, 110);
        assertThat(insertedQuestions).extracting(Question::getGamedayId).containsOnly(GAMEDAY_ID);
        assertThat(insertedQuestions).extracting(Question::getRoundId).containsOnly(ROUND_ID);
        // 样例中的数字 4 没有可信语义，默认保留原码且不会被 OPEN 门槛误接纳。
        assertThat(insertedQuestions).extracting(Question::getStatus).containsOnly("4");

        ArgumentCaptor<QuestionSnapshot> snapshotCaptor = ArgumentCaptor.forClass(QuestionSnapshot.class);
        verify(questionSnapshotMapper, times(2)).insert(snapshotCaptor.capture());
        List<QuestionSnapshot> insertedSnapshots = snapshotCaptor.getAllValues();
        assertThat(insertedSnapshots).extracting(QuestionSnapshot::getSnapshotNo).containsOnly(1);
        assertThat(insertedSnapshots).extracting(QuestionSnapshot::getSnapshotReason).containsOnly("INITIAL");
        assertThat(insertedSnapshots).extracting(QuestionSnapshot::getQuestionId).containsExactlyInAnyOrder(1001L, 1002L);

        // 第一题 3 个选项，第二题 2 个选项
        ArgumentCaptor<QuestionOption> optionCaptor = ArgumentCaptor.forClass(QuestionOption.class);
        verify(questionOptionMapper, times(5)).insert(optionCaptor.capture());
        assertThat(optionCaptor.getAllValues()).extracting(QuestionOption::getSnapshotId)
                .containsExactlyInAnyOrder(2001L, 2001L, 2001L, 2002L, 2002L);
        assertThat(optionCaptor.getAllValues()).extracting(QuestionOption::getIsAnswer).containsOnly(false);
        assertThat(optionCaptor.getAllValues()).extracting(QuestionOption::getOptionNo).containsExactlyInAnyOrder(0, 1, 2, 0, 1);
    }

    @Test
    @DisplayName("显式配置数字状态映射后才将题目写为 OPEN，仍留存原始 Feed")
    void configuredQuestionStatusMapping_opensOnlySelectedSourceCode() {
        properties.setQuestionStatusMapping(Map.of(4, "OPEN"));
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(sampleJson);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged(eq("QUESTIONS"), eq(sampleHash))).thenReturn(null);
        stubMeetingSessionRound();
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(eq(GAMEDAY_ID), anyInt())).thenReturn(null);
        stubQuestionInsertIds(1001L, 1002L);
        stubSnapshotInsertIds(2001L, 2002L);

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<Question> questions = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper, times(2)).insert(questions.capture());
        assertThat(questions.getAllValues()).extracting(Question::getStatus).containsOnly("OPEN");
        ArgumentCaptor<FeedRawPayload> payload = ArgumentCaptor.forClass(FeedRawPayload.class);
        verify(persistenceStore).saveRawPayload(payload.capture());
        assertThat(payload.getValue().getRawJson()).isEqualTo(sampleJson).contains("\"Status\": 4");
    }

    @Test
    @DisplayName("同一 Feed 仅配置状态映射变化：重同步当前状态，不新建历史快照")
    void unchangedFeed_newStatusMapping_reconcilesCurrentStatusWithoutNewSnapshot() throws Exception {
        properties.setQuestionStatusMapping(Map.of(4, "OPEN"));
        String json = singleQuestionJson();
        String hash = FeedSyncUtils.sha256Hex(json);
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(json);
        stubPayloadAndRecordIds();
        SyncRecord previous = new SyncRecord();
        previous.setId(1L);
        previous.setStatus("SUCCESS");
        previous.setContentHash(hash);
        when(persistenceStore.findLatestUnchanged(eq("QUESTIONS"), eq(hash))).thenReturn(previous);
        stubMeetingSessionRound();
        Question existing = questionEntity(1001L, GAMEDAY_ID, 109, questionHash(json, 0), 2001L);
        existing.setStatus("4");
        when(questionMapper.selectByGamedayId(GAMEDAY_ID)).thenReturn(List.of(existing));
        stubConditionalStatusUpdate(existing);

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(existing.getStatus()).isEqualTo("OPEN");
        assertThat(existing.getLatestSnapshotId()).isEqualTo(2001L);
        verify(questionMapper).updateStatusIfUnchanged(eq(1001L), eq(GAMEDAY_ID), eq(existing.getContentHash()),
                eq("4"), eq("OPEN"), any(Instant.class), eq(true));
        verify(questionMapper, never()).updateById(any(Question.class));
        verify(questionSnapshotMapper, never()).insert(any(QuestionSnapshot.class));
        verify(questionOptionMapper, never()).insert(any(QuestionOption.class));
        ArgumentCaptor<FeedRawPayload> payload = ArgumentCaptor.forClass(FeedRawPayload.class);
        verify(persistenceStore).saveRawPayload(payload.capture());
        assertThat(payload.getValue().getRawJson()).isEqualTo(json);

        // 撤销测试映射后，同一原始包也应把当前状态恢复为未识别数字，避免遗留 OPEN。
        properties.setQuestionStatusMapping(Map.of());
        SyncResultDto revoked = service.syncQuestions(GAMEDAY_ID);
        assertThat(revoked.getStatus()).isEqualTo("SUCCESS");
        assertThat(existing.getStatus()).isEqualTo("4");
        assertThat(existing.getLatestSnapshotId()).isEqualTo(2001L);
        verify(questionMapper).updateStatusIfUnchanged(eq(1001L), eq(GAMEDAY_ID), eq(existing.getContentHash()),
                eq("OPEN"), eq("4"), any(Instant.class), eq(true));
        verify(questionSnapshotMapper, never()).insert(any(QuestionSnapshot.class));
    }

    @Test
    @DisplayName("Feed 整包变化但单题未变：新增状态映射更新该题且不新增快照")
    void changedFeed_newStatusMapping_updatesUnchangedQuestionWithoutSnapshot() throws Exception {
        properties.setQuestionStatusMapping(Map.of(4, "OPEN"));
        String changedJson = sampleJson.replace("George Russell", "George Russell UPDATED");
        String changedHash = FeedSyncUtils.sha256Hex(changedJson);
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(changedJson);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged(eq("QUESTIONS"), eq(changedHash))).thenReturn(null);
        stubMeetingSessionRound();

        Question changedQuestion = questionEntity(1001L, GAMEDAY_ID, 109, questionHash(sampleJson, 0), 2001L);
        Question unchangedQuestion = questionEntity(1002L, GAMEDAY_ID, 110, questionHash(sampleJson, 1), 2002L);
        unchangedQuestion.setStatus("4");
        Instant previousUpdatedAt = Instant.parse("2026-01-01T00:00:00Z");
        unchangedQuestion.setUpdatedAt(previousUpdatedAt);
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(GAMEDAY_ID, 109)).thenReturn(changedQuestion);
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(GAMEDAY_ID, 110)).thenReturn(unchangedQuestion);
        stubConditionalStatusUpdate(unchangedQuestion);
        when(questionSnapshotMapper.selectMaxSnapshotNo(1001L)).thenReturn(1);
        when(questionSnapshotMapper.insert(any(QuestionSnapshot.class))).thenAnswer(invocation -> {
            QuestionSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(2003L);
            return 1;
        });

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(unchangedQuestion.getStatus()).isEqualTo("OPEN");
        assertThat(unchangedQuestion.getLastSyncedAt()).isNotNull();
        assertThat(unchangedQuestion.getUpdatedAt()).isAfter(previousUpdatedAt);
        assertThat(unchangedQuestion.getLatestSnapshotId()).isEqualTo(2002L);
        verify(questionSnapshotMapper, times(1)).insert(any(QuestionSnapshot.class));
        verify(questionOptionMapper, times(3)).insert(any(QuestionOption.class));
        verify(questionMapper).updateStatusIfUnchanged(eq(1002L), eq(GAMEDAY_ID),
                eq(unchangedQuestion.getContentHash()), any(), eq(unchangedQuestion.getStatus()),
                any(Instant.class), eq(true));
        verify(questionMapper, never()).updateById(unchangedQuestion);
    }

    @Test
    @DisplayName("Feed 整包变化且撤销状态映射：单题状态恢复原码，不新增快照")
    void changedFeed_revokedStatusMapping_restoresUnchangedQuestionWithoutSnapshot() throws Exception {
        properties.setQuestionStatusMapping(Map.of());
        String changedJson = sampleJson.replace("George Russell", "George Russell UPDATED");
        String changedHash = FeedSyncUtils.sha256Hex(changedJson);
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(changedJson);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged(eq("QUESTIONS"), eq(changedHash))).thenReturn(null);
        stubMeetingSessionRound();

        Question changedQuestion = questionEntity(1001L, GAMEDAY_ID, 109, questionHash(sampleJson, 0), 2001L);
        Question unchangedQuestion = questionEntity(1002L, GAMEDAY_ID, 110, questionHash(sampleJson, 1), 2002L);
        unchangedQuestion.setStatus("OPEN");
        Instant previousUpdatedAt = Instant.parse("2026-01-01T00:00:00Z");
        unchangedQuestion.setUpdatedAt(previousUpdatedAt);
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(GAMEDAY_ID, 109)).thenReturn(changedQuestion);
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(GAMEDAY_ID, 110)).thenReturn(unchangedQuestion);
        stubConditionalStatusUpdate(unchangedQuestion);
        when(questionSnapshotMapper.selectMaxSnapshotNo(1001L)).thenReturn(1);
        when(questionSnapshotMapper.insert(any(QuestionSnapshot.class))).thenAnswer(invocation -> {
            QuestionSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(2003L);
            return 1;
        });

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(unchangedQuestion.getStatus()).isEqualTo("4");
        assertThat(unchangedQuestion.getLastSyncedAt()).isNotNull();
        assertThat(unchangedQuestion.getUpdatedAt()).isAfter(previousUpdatedAt);
        assertThat(unchangedQuestion.getLatestSnapshotId()).isEqualTo(2002L);
        verify(questionSnapshotMapper, times(1)).insert(any(QuestionSnapshot.class));
        verify(questionOptionMapper, times(3)).insert(any(QuestionOption.class));
        verify(questionMapper).updateStatusIfUnchanged(eq(1002L), eq(GAMEDAY_ID),
                eq(unchangedQuestion.getContentHash()), any(), eq(unchangedQuestion.getStatus()),
                any(Instant.class), eq(true));
        verify(questionMapper, never()).updateById(unchangedQuestion);
    }

    @Test
    @DisplayName("相同 JSON 再次同步：SKIPPED_UNCHANGED，零快照零选项写入")
    void secondSync_sameHash_skipsAllBusinessWrites() {
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(sampleJson);
        stubPayloadAndRecordIds();
        SyncRecord previous = new SyncRecord();
        previous.setId(1L);
        previous.setStatus("SUCCESS");
        previous.setContentHash(sampleHash);
        when(persistenceStore.findLatestUnchanged(eq("QUESTIONS"), eq(sampleHash))).thenReturn(previous);

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SKIPPED_UNCHANGED");
        assertThat(result.getContentHash()).isEqualTo(sampleHash);
        assertThat(result.getSourceType()).isEqualTo("QUESTIONS");
        verify(persistenceStore, times(1)).saveRawPayload(any(FeedRawPayload.class));
        ArgumentCaptor<SyncRecord> recordCaptor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(persistenceStore).saveSyncRecord(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getStatus()).isEqualTo("SKIPPED_UNCHANGED");
        assertThat(recordCaptor.getValue().getGamedayId()).isEqualTo(GAMEDAY_ID);

        verify(questionMapper, never()).insert(any(Question.class));
        verify(questionMapper, never()).updateById(any(Question.class));
        verify(questionSnapshotMapper, never()).insert(any(QuestionSnapshot.class));
        verify(questionOptionMapper, never()).insert(any(QuestionOption.class));
        verify(meetingSessionMapper).selectByGamedayId(GAMEDAY_ID);
    }

    @Test
    @DisplayName("改变一个选项 Value：该题生成 snapshot_no=2 CHANGED，另一题无新快照")
    void thirdSync_changedOptionValue_createsChangedSnapshotForOneQuestionOnly() throws Exception {
        properties.setQuestionStatusMapping(Map.of(4, "OPEN"));
        String changedJson = sampleJson.replace("George Russell", "George Russell UPDATED");
        String changedHash = FeedSyncUtils.sha256Hex(changedJson);
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(changedJson);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged(eq("QUESTIONS"), eq(changedHash))).thenReturn(null);
        stubMeetingSessionRound();

        String hashQ1 = questionHash(sampleJson, 0);
        String hashQ2 = questionHash(sampleJson, 1);

        Question existingQ1 = questionEntity(1001L, GAMEDAY_ID, 109, hashQ1, 2001L);
        Question existingQ2 = questionEntity(1002L, GAMEDAY_ID, 110, hashQ2, 2002L);
        existingQ2.setStatus("OPEN");
        Instant previousUpdatedAt = Instant.parse("2026-01-01T00:00:00Z");
        existingQ2.setUpdatedAt(previousUpdatedAt);
        stubConditionalStatusUpdate(existingQ2);
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(GAMEDAY_ID, 109)).thenReturn(existingQ1);
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(GAMEDAY_ID, 110)).thenReturn(existingQ2);
        when(questionSnapshotMapper.selectMaxSnapshotNo(1001L)).thenReturn(1);
        when(questionSnapshotMapper.insert(any(QuestionSnapshot.class))).thenAnswer(invocation -> {
            QuestionSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(2003L);
            return 1;
        });

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getContentHash()).isEqualTo(changedHash);

        // 只有第一题变化：新增一个 CHANGED 快照
        ArgumentCaptor<QuestionSnapshot> snapshotCaptor = ArgumentCaptor.forClass(QuestionSnapshot.class);
        verify(questionSnapshotMapper, times(1)).insert(snapshotCaptor.capture());
        QuestionSnapshot changedSnapshot = snapshotCaptor.getValue();
        assertThat(changedSnapshot.getQuestionId()).isEqualTo(1001L);
        assertThat(changedSnapshot.getSnapshotNo()).isEqualTo(2);
        assertThat(changedSnapshot.getSnapshotReason()).isEqualTo("CHANGED");

        // 第一题更新 latest_snapshot_id 与内容
        ArgumentCaptor<Question> questionCaptor = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper).updateById(questionCaptor.capture());
        Question updatedQ1 = questionCaptor.getValue();
        assertThat(updatedQ1.getLatestSnapshotId()).isEqualTo(2003L);
        assertThat(updatedQ1.getQuestionText()).isEqualTo("Who will finish in the top 3?");
        // 内容变化后也必须走相同映射；配置不影响仅刷新 last_synced_at 的未变题。
        assertThat(updatedQ1.getStatus()).isEqualTo("OPEN");

        // 第二题无新快照，仅刷新 last_synced_at
        verify(questionMapper).updateStatusIfUnchanged(eq(1002L), eq(GAMEDAY_ID), eq(hashQ2),
                eq("OPEN"), eq("OPEN"), any(Instant.class), eq(false));
        assertThat(existingQ2.getLatestSnapshotId()).isEqualTo(2002L);
        assertThat(existingQ2.getUpdatedAt()).isEqualTo(previousUpdatedAt);
        assertThat(existingQ2.getLastSyncedAt()).isNotNull();

        // 只有第一题的新快照下插入 3 个选项
        verify(questionOptionMapper, times(3)).insert(any(QuestionOption.class));
    }

    @Test
    @DisplayName("客户端 HTTP 500：记 FAILED，不留档、不插题目")
    void clientHttp500_writesFailedRecordWithoutPayloadOrQuestions() {
        when(feedClient.fetchQuestions(GAMEDAY_ID))
                .thenThrow(new FeedSyncException("Feed request failed with HTTP 500", 500));
        when(persistenceStore.saveSyncRecord(any(SyncRecord.class))).thenAnswer(invocation -> {
            SyncRecord record = invocation.getArgument(0);
            record.setId(500L);
            return 500L;
        });

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getContentHash()).isNull();
        assertThat(result.getPayloadId()).isNull();
        assertThat(result.getSyncRecordId()).isEqualTo(500L);
        assertThat(result.getErrorMessage()).contains("500");
        assertThat(result.getSourceType()).isEqualTo("QUESTIONS");

        verify(persistenceStore, never()).saveRawPayload(any());
        verify(persistenceStore, never()).findLatestUnchanged(anyString(), anyString());
        verify(questionMapper, never()).insert(any(Question.class));
        verify(questionMapper, never()).updateById(any(Question.class));
        verify(questionSnapshotMapper, never()).insert(any(QuestionSnapshot.class));
        verify(questionOptionMapper, never()).insert(any(QuestionOption.class));
    }

    @Test
    @DisplayName("空 Questions 数组：SUCCESS，不插题目")
    void emptyQuestionsArray_successWithoutInserts() {
        String emptyJson = "{\"Data\":{\"Value\":{\"Questions\":[]}}}";
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(emptyJson);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged(eq("QUESTIONS"), any())).thenReturn(null);
        stubMeetingSessionRound();

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getSourceType()).isEqualTo("QUESTIONS");
        verify(questionMapper, never()).insert(any(Question.class));
        verify(questionMapper, never()).updateById(any(Question.class));
        verify(questionSnapshotMapper, never()).insert(any(QuestionSnapshot.class));
        verify(questionOptionMapper, never()).insert(any(QuestionOption.class));
    }

    @Test
    @DisplayName("查到的题目 gamedayId 不匹配：不更新该题目，仍可正常插入新题")
    void mismatchedGamedayQuestion_neverUpdateStray() {
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(sampleJson);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged(eq("QUESTIONS"), eq(sampleHash))).thenReturn(null);
        stubMeetingSessionRound();

        // 109 命中一条 gamedayId 不同的“流浪”题目；110 未命中
        Question stray = questionEntity(999L, 999, 109, "oldHash", 2000L);
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(GAMEDAY_ID, 109)).thenReturn(stray);
        when(questionMapper.selectByGamedayIdAndSourceQuestionId(GAMEDAY_ID, 110)).thenReturn(null);
        stubQuestionInsertIds(1001L, 1002L);
        stubSnapshotInsertIds(2001L, 2002L);

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        // 流浪题目绝不被 updateById
        verify(questionMapper, never()).updateById(argThat((Question q) -> q != null && Long.valueOf(999L).equals(q.getId())));
        // 110 正常插入并回写 latest_snapshot_id（会触发 updateById）
        verify(questionMapper, times(1)).insert(any(Question.class));
    }

    @Test
    @DisplayName("缺分站首次跳过后：相同 Feed 在分站出现时补建全部题目，第三轮无重复")
    void sameFeed_roundAppears_backfillsInitialQuestionsThenSkips() {
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(sampleJson);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged("QUESTIONS", sampleHash))
                .thenReturn(null, new SyncRecord());
        MeetingSession session = new MeetingSession();
        session.setRoundId(ROUND_ID);
        when(meetingSessionMapper.selectByGamedayId(GAMEDAY_ID)).thenReturn(null, session);
        Map<Integer, Question> rows = new LinkedHashMap<>();
        when(questionMapper.selectByGamedayId(GAMEDAY_ID))
                .thenAnswer(invocation -> new ArrayList<>(rows.values()));
        lenient().when(questionMapper.insert(any(Question.class))).thenAnswer(invocation -> {
            Question row = invocation.getArgument(0);
            row.setId(row.getSourceQuestionId() == 109 ? 1001L : 1002L);
            rows.put(row.getSourceQuestionId(), row);
            return 1;
        });
        lenient().when(questionSnapshotMapper.insert(any(QuestionSnapshot.class)))
                .thenAnswer(invocation -> {
                    QuestionSnapshot snapshot = invocation.getArgument(0);
                    snapshot.setId(snapshot.getQuestionId() == 1001L ? 2001L : 2002L);
                    return 1;
                });

        SyncResultDto first = service.syncQuestions(GAMEDAY_ID);
        SyncResultDto second = service.syncQuestions(GAMEDAY_ID);
        SyncResultDto third = service.syncQuestions(GAMEDAY_ID);

        assertThat(first.getStatus()).isEqualTo("SUCCESS");
        assertThat(first.getErrorMessage()).isEqualTo("skippedQuestions=2");
        assertThat(second.getStatus()).isEqualTo("SUCCESS");
        assertThat(second.getErrorMessage()).isNull();
        assertThat(third.getStatus()).isEqualTo("SKIPPED_UNCHANGED");
        assertThat(rows).containsOnlyKeys(109, 110);
        assertThat(rows.values()).extracting(Question::getLatestSnapshotId).containsExactly(2001L, 2002L);
        verify(questionMapper, times(2)).insert(any(Question.class));
        verify(questionMapper, never()).selectByGamedayIdAndSourceQuestionId(anyInt(), anyInt());
        ArgumentCaptor<QuestionSnapshot> snapshots = ArgumentCaptor.forClass(QuestionSnapshot.class);
        verify(questionSnapshotMapper, times(2)).insert(snapshots.capture());
        assertThat(snapshots.getAllValues()).extracting(QuestionSnapshot::getSnapshotReason).containsOnly("INITIAL");
        assertThat(snapshots.getAllValues()).extracting(QuestionSnapshot::getSnapshotNo).containsOnly(1);
        verify(questionOptionMapper, times(5)).insert(any(QuestionOption.class));
    }

    @Test
    @DisplayName("相同旧包仅补缺题，重复来源 ID 只处理一次，已有更新内容及快照不回退")
    void sameFeed_partialMissingAndDuplicateIds_preservesNewerExistingQuestion() throws Exception {
        properties.setQuestionStatusMapping(Map.of(4, "OPEN"));
        QuestionsFeedResponse response = OBJECT_MAPPER.readValue(sampleJson, QuestionsFeedResponse.class);
        List<QuestionsFeedQuestion> sources = response.getData().getValue().getQuestions();
        response.getData().getValue().setQuestions(List.of(sources.get(0), sources.get(1), sources.get(1)));
        String json = OBJECT_MAPPER.writeValueAsString(response);
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(json);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged("QUESTIONS", FeedSyncUtils.sha256Hex(json))).thenReturn(new SyncRecord());
        stubMeetingSessionRound();
        Question newer = questionEntity(1001L, GAMEDAY_ID, 109, "newer-content-hash", 3001L);
        newer.setQuestionText("newer text");
        newer.setStatus("CLOSED");
        Instant newerAt = Instant.parse("2026-09-30T01:00:00Z");
        newer.setUpdatedAt(newerAt);
        newer.setLastSyncedAt(newerAt);
        List<Question> rows = new ArrayList<>(List.of(newer));
        when(questionMapper.selectByGamedayId(GAMEDAY_ID)).thenAnswer(invocation -> List.copyOf(rows));
        when(questionMapper.insert(any(Question.class))).thenAnswer(invocation -> {
            Question inserted = invocation.getArgument(0);
            inserted.setId(1002L);
            rows.add(inserted);
            return 1;
        });
        when(questionSnapshotMapper.insert(any(QuestionSnapshot.class))).thenAnswer(invocation -> {
            QuestionSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(2002L);
            return 1;
        });

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);
        SyncResultDto repeated = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(repeated.getStatus()).isEqualTo("SKIPPED_UNCHANGED");
        assertThat(newer.getQuestionText()).isEqualTo("newer text");
        assertThat(newer.getContentHash()).isEqualTo("newer-content-hash");
        assertThat(newer.getLatestSnapshotId()).isEqualTo(3001L);
        assertThat(newer.getStatus()).isEqualTo("CLOSED");
        assertThat(newer.getUpdatedAt()).isEqualTo(newerAt);
        assertThat(newer.getLastSyncedAt()).isEqualTo(newerAt);
        verify(questionMapper).insert(any(Question.class));
        verify(questionMapper, never()).selectByGamedayIdAndSourceQuestionId(anyInt(), anyInt());
        verify(questionMapper, never()).updateStatusIfUnchanged(any(), anyInt(), any(), any(), any(), any(), anyBoolean());
        ArgumentCaptor<QuestionSnapshot> snapshot = ArgumentCaptor.forClass(QuestionSnapshot.class);
        verify(questionSnapshotMapper).insert(snapshot.capture());
        assertThat(snapshot.getValue().getQuestionId()).isEqualTo(1002L);
        assertThat(snapshot.getValue().getSnapshotNo()).isEqualTo(1);
        assertThat(snapshot.getValue().getSnapshotReason()).isEqualTo("INITIAL");
        verify(questionOptionMapper, times(2)).insert(any(QuestionOption.class));
    }

    @ParameterizedTest
    @CsvSource({"false, true", "false, false", "true, true", "true, false"})
    @DisplayName("同比赛日旧分站题占据来源唯一键：保持整行原样，只补真正缺题，第三轮幂等")
    void sameFeed_questionInOldRound_skipsExistingIdentityAndBackfillsOnlyMissing(
            boolean hasMissingQuestion, boolean sameQuestionContent) throws Exception {
        properties.setQuestionStatusMapping(Map.of(4, "OPEN"));
        String json = hasMissingQuestion ? sampleJson : singleQuestionJson();
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(json);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged("QUESTIONS", FeedSyncUtils.sha256Hex(json)))
                .thenReturn(new SyncRecord());
        stubMeetingSessionRound();
        String oldHash = sameQuestionContent ? questionHash(json, 0) : "newer-content-hash";
        Question oldRoundQuestion = questionEntity(1001L, GAMEDAY_ID, 109, oldHash, 3001L);
        oldRoundQuestion.setRoundId(9L);
        oldRoundQuestion.setQuestionText("old round committed text");
        oldRoundQuestion.setStatus("4");
        Instant oldTime = Instant.parse("2026-09-30T01:00:00Z");
        oldRoundQuestion.setFirstSeenAt(oldTime);
        oldRoundQuestion.setCreatedAt(oldTime);
        oldRoundQuestion.setUpdatedAt(oldTime);
        oldRoundQuestion.setLastSyncedAt(oldTime);
        Map<Integer, Question> rows = new LinkedHashMap<>();
        rows.put(109, oldRoundQuestion);
        // 持久化身份查询必须包含旧分站题；否则下面的唯一键模拟会拒绝误插。
        when(questionMapper.selectByGamedayId(GAMEDAY_ID))
                .thenAnswer(invocation -> List.copyOf(rows.values()));
        lenient().when(questionMapper.insert(any(Question.class))).thenAnswer(invocation -> {
            Question row = invocation.getArgument(0);
            if (rows.containsKey(row.getSourceQuestionId())) {
                throw new DuplicateKeyException("existing gameday/source identity in another round");
            }
            row.setId(1002L);
            rows.put(row.getSourceQuestionId(), row);
            return 1;
        });
        if (hasMissingQuestion) {
            lenient().when(questionSnapshotMapper.insert(any(QuestionSnapshot.class))).thenAnswer(invocation -> {
                QuestionSnapshot snapshot = invocation.getArgument(0);
                snapshot.setId(2002L);
                return 1;
            });
        }

        SyncResultDto first = service.syncQuestions(GAMEDAY_ID);
        SyncResultDto second = service.syncQuestions(GAMEDAY_ID);
        SyncResultDto third = service.syncQuestions(GAMEDAY_ID);

        assertThat(first.getStatus()).isEqualTo(hasMissingQuestion ? "SUCCESS" : "SKIPPED_UNCHANGED");
        assertThat(second.getStatus()).isEqualTo("SKIPPED_UNCHANGED");
        assertThat(third.getStatus()).isEqualTo("SKIPPED_UNCHANGED");
        assertThat(oldRoundQuestion.getRoundId()).isEqualTo(9L);
        assertThat(oldRoundQuestion.getQuestionText()).isEqualTo("old round committed text");
        assertThat(oldRoundQuestion.getContentHash()).isEqualTo(oldHash);
        assertThat(oldRoundQuestion.getLatestSnapshotId()).isEqualTo(3001L);
        assertThat(oldRoundQuestion.getStatus()).isEqualTo("4");
        assertThat(oldRoundQuestion.getFirstSeenAt()).isEqualTo(oldTime);
        assertThat(oldRoundQuestion.getCreatedAt()).isEqualTo(oldTime);
        assertThat(oldRoundQuestion.getUpdatedAt()).isEqualTo(oldTime);
        assertThat(oldRoundQuestion.getLastSyncedAt()).isEqualTo(oldTime);
        verify(questionMapper, times(3)).selectByGamedayId(GAMEDAY_ID);
        verify(questionMapper, never()).selectByRound(any(), any(), any());
        verify(questionMapper, never()).updateById(oldRoundQuestion);
        verify(questionMapper, never()).updateStatusIfUnchanged(any(), anyInt(), any(), any(), any(), any(), anyBoolean());
        verify(questionMapper, never()).selectByGamedayIdAndSourceQuestionId(anyInt(), anyInt());
        if (hasMissingQuestion) {
            verify(questionMapper).insert(any(Question.class));
            assertThat(rows).containsOnlyKeys(109, 110);
            assertThat(rows.get(110).getRoundId()).isEqualTo(ROUND_ID);
            assertThat(rows.get(110).getLatestSnapshotId()).isEqualTo(2002L);
            ArgumentCaptor<QuestionSnapshot> snapshot = ArgumentCaptor.forClass(QuestionSnapshot.class);
            verify(questionSnapshotMapper).insert(snapshot.capture());
            assertThat(snapshot.getValue().getQuestionId()).isEqualTo(1002L);
            assertThat(snapshot.getValue().getSnapshotNo()).isEqualTo(1);
            assertThat(snapshot.getValue().getSnapshotReason()).isEqualTo("INITIAL");
            verify(questionOptionMapper, times(2)).insert(any(QuestionOption.class));
        } else {
            verify(questionMapper, never()).insert(any(Question.class));
            verify(questionSnapshotMapper, never()).insert(any(QuestionSnapshot.class));
            verify(questionOptionMapper, never()).insert(any(QuestionOption.class));
        }
    }

    @Test
    @DisplayName("相同 Feed 重试仍无分站：保持跳过兼容状态并记录缺题说明")
    void sameFeed_roundStillMissing_keepsSkippedStatusAndNote() {
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(sampleJson);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged("QUESTIONS", sampleHash)).thenReturn(new SyncRecord());

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo("SKIPPED_UNCHANGED");
        assertThat(result.getErrorMessage()).isEqualTo("skippedQuestions=2");
        ArgumentCaptor<SyncRecord> record = ArgumentCaptor.forClass(SyncRecord.class);
        verify(persistenceStore).saveSyncRecord(record.capture());
        assertThat(record.getValue().getErrorMessage()).isEqualTo("skippedQuestions=2");
        verify(questionMapper, never()).insert(any(Question.class));
        verify(questionSnapshotMapper, never()).insert(any(QuestionSnapshot.class));
        verify(questionOptionMapper, never()).insert(any(QuestionOption.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"question", "snapshot", "option"})
    @DisplayName("同包补建的唯一键冲突、快照及选项失败必须上抛，不记成功也不覆盖已有题目")
    void sameFeed_backfillPersistenceFailure_propagatesWithoutChangedOverwrite(String failingTable) {
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(sampleJson);
        when(persistenceStore.saveRawPayload(any(FeedRawPayload.class))).thenReturn(100L);
        when(persistenceStore.findLatestUnchanged("QUESTIONS", sampleHash)).thenReturn(new SyncRecord());
        stubMeetingSessionRound();
        RuntimeException failure = "question".equals(failingTable)
                ? new DuplicateKeyException("concurrent question insert")
                : new DataIntegrityViolationException("failed " + failingTable + " insert");
        if ("question".equals(failingTable)) {
            when(questionMapper.insert(any(Question.class))).thenThrow(failure);
        } else {
            stubQuestionInsertIds(1001L, 1002L);
            if ("snapshot".equals(failingTable)) {
                when(questionSnapshotMapper.insert(any(QuestionSnapshot.class))).thenThrow(failure);
            } else {
                stubSnapshotInsertIds(2001L, 2002L);
                when(questionOptionMapper.insert(any(QuestionOption.class))).thenThrow(failure);
            }
        }

        assertThatThrownBy(() -> service.syncQuestions(GAMEDAY_ID)).isSameAs(failure);

        verify(persistenceStore, never()).saveSyncRecord(any(SyncRecord.class));
        verify(questionMapper, never()).selectByGamedayIdAndSourceQuestionId(anyInt(), anyInt());
        verify(questionSnapshotMapper, never()).selectMaxSnapshotNo(any());
        if ("question".equals(failingTable)) {
            verify(questionMapper, never()).updateById(any(Question.class));
            verify(questionSnapshotMapper, never()).insert(any(QuestionSnapshot.class));
        }
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    @DisplayName("读取后并发提交新内容或新状态：状态-only 写入不得覆盖新行，零行不计状态修正")
    void concurrentQuestionUpdate_statusOnlyWritePreservesCommittedRow(boolean sameFeed, boolean newContent)
            throws Exception {
        properties.setQuestionStatusMapping(Map.of(4, "OPEN"));
        String json = singleQuestionJson();
        String hash = questionHash(json, 0);
        when(feedClient.fetchQuestions(GAMEDAY_ID)).thenReturn(json);
        stubPayloadAndRecordIds();
        when(persistenceStore.findLatestUnchanged("QUESTIONS", FeedSyncUtils.sha256Hex(json)))
                .thenReturn(sameFeed ? new SyncRecord() : null);
        stubMeetingSessionRound();
        Question stale = questionEntity(1001L, GAMEDAY_ID, 109, hash, 2001L);
        stale.setStatus("4");
        stale.setQuestionText("stale text");
        stale.setUpdatedAt(Instant.parse("2026-09-30T00:00:00Z"));
        stale.setLastSyncedAt(stale.getUpdatedAt());
        Question committed = questionEntity(1001L, GAMEDAY_ID, 109, hash, 2001L);
        Instant committedAt = Instant.parse("2026-09-30T01:00:00Z");
        // 精确模拟先返回旧读视图、随后另一事务已提交，再执行本轮 UPDATE 的交错顺序。
        Answer<Question> readThenCommit = invocation -> {
            committed.setContentHash(newContent ? "newer-content-hash" : hash);
            committed.setLatestSnapshotId(newContent ? 3001L : 2001L);
            committed.setQuestionText("newer committed text");
            committed.setStatus("CLOSED");
            committed.setUpdatedAt(committedAt);
            committed.setLastSyncedAt(committedAt);
            return stale;
        };
        if (sameFeed) {
            when(questionMapper.selectByGamedayId(GAMEDAY_ID)).thenAnswer(invocation ->
                    List.of(readThenCommit.answer(invocation)));
        } else {
            when(questionMapper.selectByGamedayIdAndSourceQuestionId(GAMEDAY_ID, 109)).thenAnswer(readThenCommit);
        }
        // 旧的全实体 UPDATE 会实际回退新行；条件 UPDATE 则由读到的哈希与旧状态判断为零行。
        lenient().when(questionMapper.updateById(any(Question.class))).thenAnswer(invocation -> {
            Question row = invocation.getArgument(0);
            committed.setContentHash(row.getContentHash());
            committed.setLatestSnapshotId(row.getLatestSnapshotId());
            committed.setQuestionText(row.getQuestionText());
            committed.setStatus(row.getStatus());
            committed.setUpdatedAt(row.getUpdatedAt());
            committed.setLastSyncedAt(row.getLastSyncedAt());
            return 1;
        });
        lenient().when(questionMapper.updateStatusIfUnchanged(eq(1001L), eq(GAMEDAY_ID),
                eq(hash), eq("4"), eq("OPEN"), any(Instant.class), eq(true))).thenAnswer(invocation -> {
            assertThat(committed.getContentHash().equals(hash) && committed.getStatus().equals("4")).isFalse();
            return 0;
        });

        SyncResultDto result = service.syncQuestions(GAMEDAY_ID);

        assertThat(result.getStatus()).isEqualTo(sameFeed ? "SKIPPED_UNCHANGED" : "SUCCESS");
        assertThat(committed.getContentHash()).isEqualTo(newContent ? "newer-content-hash" : hash);
        assertThat(committed.getLatestSnapshotId()).isEqualTo(newContent ? 3001L : 2001L);
        assertThat(committed.getQuestionText()).isEqualTo("newer committed text");
        assertThat(committed.getStatus()).isEqualTo("CLOSED");
        assertThat(committed.getUpdatedAt()).isEqualTo(committedAt);
        assertThat(committed.getLastSyncedAt()).isEqualTo(committedAt);
        verify(questionMapper).updateStatusIfUnchanged(eq(1001L), eq(GAMEDAY_ID), eq(hash), eq("4"),
                eq("OPEN"), any(Instant.class), eq(true));
        verify(questionMapper, never()).updateById(any(Question.class));
        verify(questionSnapshotMapper, never()).insert(any(QuestionSnapshot.class));
        verify(questionOptionMapper, never()).insert(any(QuestionOption.class));
    }

    /**
     * 在 Mapper 边界模拟窄字段条件写入，保持现有状态映射测试的持久化行为断言。
     */
    private void stubConditionalStatusUpdate(Question row) {
        when(questionMapper.updateStatusIfUnchanged(eq(row.getId()), eq(row.getGamedayId()),
                any(), any(), any(), any(Instant.class), anyBoolean())).thenAnswer(invocation -> {
            if (!Objects.equals(row.getContentHash(), invocation.getArgument(2))
                    || !Objects.equals(row.getStatus(), invocation.getArgument(3))) {
                return 0;
            }
            Instant now = invocation.getArgument(5);
            if ((boolean) invocation.getArgument(6)) {
                row.setStatus(invocation.getArgument(4));
                row.setUpdatedAt(now);
            }
            row.setLastSyncedAt(now);
            return 1;
        });
    }

    private String singleQuestionJson() throws Exception {
        QuestionsFeedResponse response = OBJECT_MAPPER.readValue(sampleJson, QuestionsFeedResponse.class);
        response.getData().getValue().setQuestions(List.of(response.getData().getValue().getQuestions().getFirst()));
        return OBJECT_MAPPER.writeValueAsString(response);
    }

    private void stubPayloadAndRecordIds() {
        when(persistenceStore.saveRawPayload(any(FeedRawPayload.class))).thenReturn(100L);
        when(persistenceStore.saveSyncRecord(any(SyncRecord.class))).thenAnswer(invocation -> {
            SyncRecord record = invocation.getArgument(0);
            record.setId(200L);
            return 200L;
        });
    }

    private void stubMeetingSessionRound() {
        MeetingSession session = new MeetingSession();
        session.setId(1L);
        session.setRoundId(ROUND_ID);
        session.setGamedayId(GAMEDAY_ID);
        when(meetingSessionMapper.selectByGamedayId(GAMEDAY_ID)).thenReturn(session);
    }

    private void stubQuestionInsertIds(Long firstId, Long secondId) {
        when(questionMapper.insert(any(Question.class))).thenAnswer(invocation -> {
            Question question = invocation.getArgument(0);
            question.setId(question.getSourceQuestionId() == 109 ? firstId : secondId);
            return 1;
        });
    }

    private void stubSnapshotInsertIds(Long firstId, Long secondId) {
        when(questionSnapshotMapper.insert(any(QuestionSnapshot.class))).thenAnswer(invocation -> {
            QuestionSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(snapshot.getQuestionId() == 1001L ? firstId : secondId);
            return 1;
        });
    }

    private static Question questionEntity(Long id, Integer gamedayId, Integer sourceQuestionId,
                                           String contentHash, Long latestSnapshotId) {
        Question question = new Question();
        question.setId(id);
        question.setGamedayId(gamedayId);
        question.setSourceQuestionId(sourceQuestionId);
        question.setRoundId(ROUND_ID);
        question.setContentHash(contentHash);
        question.setLatestSnapshotId(latestSnapshotId);
        return question;
    }

    private static String questionHash(String json, int index) throws Exception {
        QuestionsFeedResponse response = OBJECT_MAPPER.readValue(json, QuestionsFeedResponse.class);
        QuestionsFeedQuestion question = response.getData().getValue().getQuestions().get(index);
        return FeedSyncUtils.sha256Hex(OBJECT_MAPPER.writeValueAsString(question));
    }

    private static String readClasspath(String path) throws IOException {
        try (InputStream input = FeedSyncServiceQuestionsTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(input, "missing classpath resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

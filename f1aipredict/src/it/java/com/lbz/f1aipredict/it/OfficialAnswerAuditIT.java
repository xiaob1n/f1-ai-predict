package com.lbz.f1aipredict.it;

import com.lbz.f1aipredict.it.support.ItFeedServer;
import com.lbz.f1aipredict.it.support.ItFixtures;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.scoring.dto.AnswerUpdateRequest;
import com.lbz.f1aipredict.scoring.service.OfficialAnswerService;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.util.FeedSyncUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4：官方答案经真实 Feed 客户端（指向本机替身 Feed）同步时的发布、重复、修订、撤销、重发与人工修订审计。
 * <p>
 * 每一步核对当前态与历史表：修订前的完整当前态被原样归档，撤销删除当前态并留档，
 * 重发插入新行并重置首次观察时间，重复同步不产生任何新版本；同批次另一题全程不受影响。
 */
class OfficialAnswerAuditIT extends AbstractDStageIT {

    private static final ItFeedServer FEED = new ItFeedServer();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CURRENT = """
            SELECT id, content_hash, raw_json, published_at, synced_at, created_at, updated_at
            FROM official_answer WHERE question_id = ?
            """;
    private static final String HISTORY = """
            SELECT content_hash, raw_json, published_at, synced_at, created_at, updated_at,
                   archived_at, revision_reason
            FROM official_answer_history WHERE question_id = ? ORDER BY id
            """;

    @Autowired
    private OfficialAnswerService answerService;

    @DynamicPropertySource
    static void feedProperties(DynamicPropertyRegistry registry) {
        registry.add("f1predict.feed.base-url", FEED::baseUrl);
    }

    @AfterAll
    static void stopFeed() {
        FEED.close();
    }

    @Test
    void answerLifecycleIsFullyAudited() {
        ItFixtures.SeededRound round = fixtures.seedRound(2);
        int gamedayId = round.gamedayId();
        long q1 = round.questions().get(0).questionId();
        long q2 = round.questions().get(1).questionId();
        int s1 = round.questions().get(0).sourceQuestionId();
        int s2 = round.questions().get(1).sourceQuestionId();
        String hash101 = FeedSyncUtils.sha256Hex("[{\"Id\":101}]");
        String hash102 = FeedSyncUtils.sha256Hex("[{\"Id\":102}]");
        String hash103 = FeedSyncUtils.sha256Hex("[{\"Id\":103}]");
        int requestsBefore = FEED.requestCount();

        // 1. 首次发布：两题各插入当前态，无历史；Feed 不提供发布时间，published_at 保持 NULL。
        String publishFeed = feed(s1, 101, s2, 103);
        String publishHash = FeedSyncUtils.sha256Hex(publishFeed);
        FEED.setQuestionsFeed(gamedayId, publishFeed);
        SyncResultDto firstSync = answerService.syncAnswers(gamedayId);
        assertThat(firstSync.getStatus()).isEqualTo("SUCCESS");
        Map<String, Object> published = single(CURRENT, q1);
        assertThat(published.get("content_hash")).isEqualTo(hash101);
        assertThat(published.get("published_at")).isNull();
        Map<String, Object> q2Current = single(CURRENT, q2);
        assertThat(q2Current.get("content_hash")).isEqualTo(hash103);
        assertThat(history(q1)).isEmpty();

        // 2. 重复同步：内容哈希未变，当前态逐列不变，不产生历史，审计记为 SKIPPED_UNCHANGED。
        // 原文按 content_hash 全局去重，允许复用其他比赛日或其他数据源的旧行，因此不按本次 gameday/source_type 计数。
        SyncResultDto repeatedSync = answerService.syncAnswers(gamedayId);
        assertThat(repeatedSync.getStatus()).isEqualTo("SKIPPED_UNCHANGED");
        assertThat(single(CURRENT, q1)).isEqualTo(published);
        assertThat(history(q1)).isEmpty();
        assertPublishPayloadShared(publishFeed, publishHash, gamedayId, firstSync, repeatedSync);

        // 3. Feed 修订：旧当前态整行归档为 FEED，当前态同一行更新，首次观察时间保持不变。
        FEED.setQuestionsFeed(gamedayId, feed(s1, 102, s2, 103));
        assertThat(answerService.syncAnswers(gamedayId).getStatus()).isEqualTo("SUCCESS");
        Map<String, Object> revised = single(CURRENT, q1);
        assertThat(revised.get("id")).isEqualTo(published.get("id"));
        assertThat(revised.get("content_hash")).isEqualTo(hash102);
        assertThat(revised.get("created_at")).isEqualTo(published.get("created_at"));
        assertThat(instant(revised, "synced_at")).isAfterOrEqualTo(instant(published, "synced_at"));
        List<Map<String, Object>> afterRevision = history(q1);
        assertThat(afterRevision).hasSize(1);
        assertArchivedCopy(afterRevision.get(0), published, "FEED");

        // 4. Feed 撤销：当前态删除，删除前状态归档为 FEED_REVOKED。
        FEED.setQuestionsFeed(gamedayId, feed(s1, null, s2, 103));
        assertThat(answerService.syncAnswers(gamedayId).getStatus()).isEqualTo("SUCCESS");
        assertThat(fixtures.rows(CURRENT, q1)).isEmpty();
        List<Map<String, Object>> afterRevoke = history(q1);
        assertThat(afterRevoke).hasSize(2);
        assertArchivedCopy(afterRevoke.get(1), revised, "FEED_REVOKED");

        // 5. 重新发布：插入新行（新主键），首次观察时间重置为撤销之后，不追加历史。
        FEED.setQuestionsFeed(gamedayId, publishFeed);
        assertThat(answerService.syncAnswers(gamedayId).getStatus()).isEqualTo("SUCCESS");
        Map<String, Object> republished = single(CURRENT, q1);
        assertThat(republished.get("id")).isNotEqualTo(published.get("id"));
        assertThat(republished.get("content_hash")).isEqualTo(hash101);
        assertThat(republished.get("published_at")).isNull();
        assertThat(instant(republished, "created_at"))
                .isAfterOrEqualTo(instant(afterRevoke.get(1), "archived_at").truncatedTo(ChronoUnit.MILLIS));
        assertThat(history(q1)).isEqualTo(afterRevoke);

        // 6. 人工修订：归档为 MANUAL，显式发布时间写入当前态，首次观察时间不变。
        Instant declaredPublication = Instant.parse("2026-10-01T12:00:00Z");
        AnswerUpdateRequest manual = new AnswerUpdateRequest();
        manual.setAnswerContent("[{\"Id\":103}]");
        manual.setPublishedAt(declaredPublication);
        answerService.updateAnswer(q1, manual);
        Map<String, Object> manuallyRevised = single(CURRENT, q1);
        assertThat(manuallyRevised.get("id")).isEqualTo(republished.get("id"));
        assertThat(manuallyRevised.get("content_hash")).isEqualTo(hash103);
        assertThat(instant(manuallyRevised, "published_at")).isEqualTo(declaredPublication);
        assertThat(manuallyRevised.get("created_at")).isEqualTo(republished.get("created_at"));
        List<Map<String, Object>> afterManual = history(q1);
        assertThat(afterManual).hasSize(3);
        assertArchivedCopy(afterManual.get(2), republished, "MANUAL");

        // 同批次第二题全程未变化，也没有任何历史；全部同步确实经过替身 Feed 的 HTTP 请求。
        assertThat(single(CURRENT, q2)).isEqualTo(q2Current);
        assertThat(history(q2)).isEmpty();
        assertThat(FEED.requestCount() - requestsBefore).isEqualTo(5);
        assertThat(fixtures.count("SELECT COUNT(*) FROM sync_record WHERE source_type = 'ANSWERS' AND gameday_id = ?",
                gamedayId)).as("每次同步都留一条审计").isEqualTo(5);
    }

    /**
     * 相同发布正文在全库只对应一行原文。sync_record 没有 payload 外键，本次审计记录用内容哈希指向该行。
     * raw_json 是 JSON 列，按 JSON 值比较，不改写发布正文。
     */
    private void assertPublishPayloadShared(String publishFeed, String publishHash, int gamedayId,
                                            SyncResultDto firstSync, SyncResultDto repeatedSync) {
        List<Map<String, Object>> payloads = fixtures.rows(
                "SELECT id, raw_json FROM feed_raw_payload WHERE content_hash = ?", publishHash);
        assertThat(payloads).as("相同原文按全局 content_hash 只留档一行").hasSize(1);
        long payloadId = ((Number) payloads.getFirst().get("id")).longValue();
        assertThat(firstSync.getPayloadId()).isEqualTo(payloadId);
        assertThat(repeatedSync.getPayloadId()).isEqualTo(payloadId);
        assertThat(json(payloads.getFirst().get("raw_json"))).isEqualTo(json(publishFeed));
        for (SyncResultDto sync : List.of(firstSync, repeatedSync)) {
            List<Map<String, Object>> linked = fixtures.rows("""
                    SELECT p.id
                    FROM sync_record r
                    JOIN feed_raw_payload p ON p.content_hash = r.content_hash
                    WHERE r.id = ? AND r.source_type = 'ANSWERS' AND r.gameday_id = ?
                    """, sync.getSyncRecordId(), gamedayId);
            assertThat(linked).as("本次审计记录 %s 指向同一原文", sync.getSyncRecordId()).hasSize(1);
            assertThat(((Number) linked.getFirst().get("id")).longValue()).isEqualTo(payloadId);
        }
    }

    /** JSON 列往返不保证字节相同；只比较 JSON 值。 */
    private static JsonNode json(Object raw) {
        String text = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(raw);
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException ex) {
            throw new AssertionError("原文不是可比较的 JSON", ex);
        }
    }

    private String feed(int firstSourceId, Integer firstAnswer, int secondSourceId, Integer secondAnswer) {
        Map<Integer, Integer> answers = new LinkedHashMap<>();
        answers.put(firstSourceId, firstAnswer);
        answers.put(secondSourceId, secondAnswer);
        return fixtures.answerFeedJson(answers);
    }

    private Map<String, Object> single(String sql, long questionId) {
        List<Map<String, Object>> rows = fixtures.rows(sql, questionId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, Object>> history(long questionId) {
        return fixtures.rows(HISTORY, questionId);
    }

    /** 历史行必须是归档前当前态的逐列副本，并带上归档原因。 */
    private static void assertArchivedCopy(Map<String, Object> archived, Map<String, Object> previous, String reason) {
        for (String column : List.of("content_hash", "raw_json", "published_at", "synced_at", "created_at",
                "updated_at")) {
            assertThat(archived.get(column)).as("历史列 %s", column).isEqualTo(previous.get(column));
        }
        assertThat(archived.get("revision_reason")).isEqualTo(reason);
        assertThat(instant(archived, "archived_at")).isAfterOrEqualTo(instant(previous, "synced_at"));
    }

    private static Instant instant(Map<String, Object> row, String column) {
        Instant value = ItFixtures.toInstant(row.get(column));
        assertThat(value).as("列 %s 不应为 NULL", column).isNotNull();
        return value;
    }
}

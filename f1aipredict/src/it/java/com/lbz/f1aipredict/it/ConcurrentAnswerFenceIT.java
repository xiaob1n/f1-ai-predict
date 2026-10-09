package com.lbz.f1aipredict.it;

import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.it.support.ItFixtures;
import com.lbz.f1aipredict.scoring.service.OfficialAnswerService;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.util.FeedSyncUtils;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过有界屏障验证较早请求的慢响应不能覆盖后发请求的成功答案。 */
@Execution(ExecutionMode.SAME_THREAD)
class ConcurrentAnswerFenceIT extends AbstractDStageIT {

    private static final MockWebServer FEED = startFeed();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static volatile int secondResponseCode = 200;
    private static volatile CountDownLatch firstRequestEntered = new CountDownLatch(1);
    private static volatile CountDownLatch releaseFirstRequest = new CountDownLatch(1);
    private static volatile String firstBody;
    private static volatile String secondBody;

    @Autowired
    private OfficialAnswerService answerService;

    @DynamicPropertySource
    static void feedProperties(DynamicPropertyRegistry registry) {
        registry.add("f1predict.feed.base-url", () -> FEED.url("/").toString());
    }

    @AfterAll
    static void stopFeed() throws IOException {
        FEED.shutdown();
    }

    @Test
    void answerSyncRejectsOwnershipChangedByCommittedScheduleMigration() {
        ItFixtures.SeededRound gameday = fixtures.seedRound(1);
        ItFixtures.SeededRound otherRound = fixtures.seedRound(1);
        fixtures.jdbc().update("UPDATE meeting_session SET round_id = ? WHERE gameday_id = ?",
                otherRound.roundId(), gameday.gamedayId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> answerService.syncAnswers(gameday.gamedayId()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Gameday question or session round ownership is inconsistent");
        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void lateOlderSuccessCannotOverwriteNewerSuccess() throws Exception {
        ItFixtures.SeededRound round = fixtures.seedRound(1);
        int gamedayId = round.gamedayId();
        long questionId = round.questions().getFirst().questionId();
        int sourceQuestionId = round.questions().getFirst().sourceQuestionId();
        firstBody = fixtures.answerFeedJson(Map.of(sourceQuestionId, 101));
        secondBody = fixtures.answerFeedJson(Map.of(sourceQuestionId, 202));
        secondResponseCode = 200;
        firstRequestEntered = new CountDownLatch(1);
        releaseFirstRequest = new CountDownLatch(1);
        REQUESTS.set(0);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SyncResultDto> older = pool.submit(() -> answerService.syncAnswers(gamedayId));
            assertThat(firstRequestEntered.await(10, TimeUnit.SECONDS))
                    .as("较早请求必须先到达 HTTP 屏障")
                    .isTrue();

            SyncResultDto newer = answerService.syncAnswers(gamedayId);
            assertThat(newer.getStatus()).isEqualTo("SUCCESS");

            releaseFirstRequest.countDown();
            SyncResultDto stale = older.get(20, TimeUnit.SECONDS);
            assertThat(stale.getStatus()).isEqualTo("SKIPPED_SUPERSEDED");

            var answerRows = fixtures.rows(
                    "SELECT content_hash, answer_content FROM official_answer WHERE question_id = ?", questionId);
            assertThat(answerRows).hasSize(1);
            assertThat(answerRows.getFirst().get("content_hash"))
                    .isEqualTo(FeedSyncUtils.sha256Hex("[{\"Id\":202}]"));
            assertThat(REQUESTS.get()).isEqualTo(2);
        } finally {
            releaseFirstRequest.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void lateOlderSuccessCannotOverwriteAfterNewerRequestFailed() throws Exception {
        ItFixtures.SeededRound round = fixtures.seedRound(1);
        int gamedayId = round.gamedayId();
        long questionId = round.questions().getFirst().questionId();
        int sourceQuestionId = round.questions().getFirst().sourceQuestionId();
        firstBody = fixtures.answerFeedJson(Map.of(sourceQuestionId, 101));
        secondBody = "";
        secondResponseCode = 503;
        firstRequestEntered = new CountDownLatch(1);
        releaseFirstRequest = new CountDownLatch(1);
        REQUESTS.set(0);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SyncResultDto> older = pool.submit(() -> answerService.syncAnswers(gamedayId));
            assertThat(firstRequestEntered.await(10, TimeUnit.SECONDS)).isTrue();

            SyncResultDto failedNewer = answerService.syncAnswers(gamedayId);
            assertThat(failedNewer.getStatus()).isEqualTo("FAILED");

            releaseFirstRequest.countDown();
            assertThat(older.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo("SKIPPED_SUPERSEDED");
            assertThat(fixtures.rows("SELECT id FROM official_answer WHERE question_id = ?", questionId)).isEmpty();
            assertThat(fixtures.count("SELECT COUNT(*) FROM sync_record WHERE source_type = 'ANSWERS' "
                    + "AND gameday_id = ? AND status = 'FAILED'", gamedayId)).isEqualTo(1);
        } finally {
            releaseFirstRequest.countDown();
            pool.shutdownNow();
        }
    }

    private static MockWebServer startFeed() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                int number = REQUESTS.incrementAndGet();
                if (number == 1) {
                    firstRequestEntered.countDown();
                    if (!releaseFirstRequest.await(10, TimeUnit.SECONDS)) {
                        return new MockResponse().setResponseCode(504);
                    }
                    return json(firstBody);
                }
                return secondResponseCode == 200 ? json(secondBody)
                        : new MockResponse().setResponseCode(secondResponseCode);
            }
        });
        try {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
        } catch (IOException error) {
            throw new IllegalStateException("测试 Feed 服务启动失败", error);
        }
        return server;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}

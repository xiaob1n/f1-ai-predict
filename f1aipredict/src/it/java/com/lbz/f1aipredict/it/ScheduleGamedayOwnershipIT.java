package com.lbz.f1aipredict.it;

import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.it.support.ItFixtures;
import com.lbz.f1aipredict.season.mapper.MeetingSessionMapper;
import com.lbz.f1aipredict.sync.dto.SyncResultDto;
import com.lbz.f1aipredict.sync.service.FeedSyncService;
import com.lbz.f1aipredict.sync.service.impl.FeedSyncServiceImpl;
import com.lbz.f1aipredict.sync.service.impl.FeedSyncTransactionExecutor;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证赛程新 Session 的比赛日归属及空比赛日并发写入边界。 */
@Execution(ExecutionMode.SAME_THREAD)
@Import({FeedSyncServiceImpl.class, FeedSyncTransactionExecutor.class,
        ScheduleGamedayOwnershipIT.RangeReadProbeConfiguration.class})
class ScheduleGamedayOwnershipIT extends AbstractDStageIT {

    private static final MockWebServer FEED = startFeed();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final RangeReadProbe PROBE = new RangeReadProbe();
    private static volatile LinkedBlockingQueue<String> scheduleBodies = new LinkedBlockingQueue<>();
    private static volatile CountDownLatch requestBarrier = new CountDownLatch(1);

    @Autowired
    private FeedSyncService feedSyncService;

    /** 并发用例的事件时间线，仅在断言失败时随诊断一起输出。 */
    private final ConcurrentLinkedQueue<String> timeline = new ConcurrentLinkedQueue<>();
    private volatile long timelineOrigin = System.nanoTime();

    @DynamicPropertySource
    static void feedProperties(DynamicPropertyRegistry registry) {
        registry.add("f1predict.feed.base-url", () -> FEED.url("/").toString());
    }

    @AfterAll
    static void stopFeed() throws IOException {
        FEED.shutdown();
    }

    @Test
    void newSessionCannotJoinGamedayOwnedByAnotherRound() {
        ItFixtures.SeededRound existingRound = fixtures.seedRound(1);
        ItFixtures.SeededRound targetRound = fixtures.seedRound(1);
        int newSessionKey = nextSessionKey();
        int targetYear = seasonYear(targetRound);
        stageResponses(scheduleJson(targetYear, existingRound.gamedayId(), newSessionKey, 900001, 1));

        assertThatThrownBy(feedSyncService::syncSchedule)
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Target gameday ownership changed; retry synchronization");
        assertThat(fixtures.count("SELECT COUNT(*) FROM meeting_session WHERE session_key = ?", newSessionKey))
                .isZero();
    }

    @Test
    void concurrentFirstSessionsCannotCommitOneGamedayToDifferentRounds() throws Exception {
        ItFixtures.SeededRound firstRound = fixtures.seedRound(1);
        ItFixtures.SeededRound secondRound = fixtures.seedRound(1);
        int gamedayId = nextGamedayId();
        int firstSessionKey = nextSessionKey();
        int secondSessionKey = firstSessionKey + 1;
        stageResponses(
                scheduleJson(seasonYear(firstRound), gamedayId, firstSessionKey, 900011, 1),
                scheduleJson(seasonYear(secondRound), gamedayId, secondSessionKey, 900012, 1));
        synchronizeFirstRangeReads(gamedayId);
        timelineOrigin = System.nanoTime();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SyncOutcome> first = pool.submit(this::syncAndCapture);
            Future<SyncOutcome> second = pool.submit(this::syncAndCapture);
            List<SyncOutcome> outcomes;
            try {
                outcomes = List.of(first.get(40, TimeUnit.SECONDS), second.get(40, TimeUnit.SECONDS));
            } catch (TimeoutException timeout) {
                throw new AssertionError("并发赛程同步在 40 秒内未结束\n" + timelineText(), timeout);
            }

            List<SyncOutcome> successful = outcomes.stream()
                    .filter(outcome -> outcome.result() != null
                            && "SUCCESS".equals(outcome.result().getStatus()))
                    .toList();
            List<SyncOutcome> rejected = outcomes.stream().filter(outcome -> outcome.error() != null).toList();
            long sessionRows = fixtures.count("SELECT COUNT(*) FROM meeting_session WHERE gameday_id = ?", gamedayId);
            long distinctRounds = fixtures.count(
                    "SELECT COUNT(DISTINCT round_id) FROM meeting_session WHERE gameday_id = ?", gamedayId);
            long candidateRows = fixtures.count("SELECT COUNT(*) FROM meeting_session WHERE gameday_id = ? "
                    + "AND session_key IN (?, ?)", gamedayId, firstSessionKey, secondSessionKey);
            String diagnostics = diagnostics(outcomes, gamedayId, firstRound, secondRound);

            // 软断言一次性输出全部失败项和双方诊断；各项断言本身不放宽。
            SoftAssertions softly = new SoftAssertions();
            // 安全不变量：任何情况下都不允许两方都成功，或同一比赛日落到不同分站。
            softly.assertThat(successful.size()).as("[安全] 两方不得都成功提交同一比赛日%n%s", diagnostics)
                    .isLessThanOrEqualTo(1);
            softly.assertThat(distinctRounds).as("[安全] 同一比赛日最多归属一个分站%n%s", diagnostics)
                    .isLessThanOrEqualTo(1);
            // 精确结果：恰好一方成功、另一方以 InvalidRequestException 被拒绝，Feed 恰好被拉取两次。
            softly.assertThat(REQUESTS.get()).as("[精确] Feed 请求数%n%s", diagnostics).isEqualTo(2);
            softly.assertThat(successful).as("[精确] 恰好一方成功%n%s", diagnostics).hasSize(1);
            softly.assertThat(rejected).as("[精确] 恰好一方被拒绝%n%s", diagnostics).hasSize(1);
            if (!rejected.isEmpty()) {
                softly.assertThat(rejected.getFirst().error()).as("[精确] 被拒绝方异常类型%n%s", diagnostics)
                        .isInstanceOf(InvalidRequestException.class);
            }
            softly.assertThat(sessionRows).as("[精确] 比赛日只保留一行 Session%n%s", diagnostics).isEqualTo(1);
            softly.assertThat(distinctRounds).as("[精确] 比赛日恰好归属一个分站%n%s", diagnostics).isEqualTo(1);
            softly.assertThat(candidateRows).as("[精确] 两个候选 Session 只落库一个%n%s", diagnostics).isEqualTo(1);
            softly.assertAll();
        } finally {
            pool.shutdownNow();
        }
    }

    private SyncOutcome syncAndCapture() {
        String thread = Thread.currentThread().getName();
        try {
            SyncResultDto result = feedSyncService.syncSchedule();
            mark("sync-returned status=" + result.getStatus());
            return new SyncOutcome(thread, result, null);
        } catch (RuntimeException | Error error) {
            mark("sync-threw " + error.getClass().getSimpleName());
            return new SyncOutcome(thread, null, error);
        }
    }

    /**
     * 启用比赛日范围读屏障：前两次真实 FOR UPDATE 范围读返回后（即双方都已持有范围锁）互相等待，
     * 之后才放行各自的 INSERT。拦截发生在真实 Mapper 代理外层，SQL 照常下发，查询结果与异常原样透传。
     */
    private void synchronizeFirstRangeReads(int gamedayId) {
        PROBE.arm(gamedayId, this::mark);
    }

    /** 探针是静态共享的，每个用例结束后必须撤销，避免影响其它用例。 */
    @AfterEach
    void disarmRangeReadProbe() {
        PROBE.disarm();
    }

    /** 记录相对测试起点的事件顺序，只含线程名与固定事件文本。 */
    private void mark(String event) {
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timelineOrigin);
        timeline.add("+" + millis + "ms " + Thread.currentThread().getName() + " " + event);
    }

    private String timelineText() {
        return String.join("\n    ", timeline);
    }

    /**
     * 汇总双方结果、数据库落库情况和事件顺序，供失败时直接定位根因。
     * 异常只输出类名链、SQLState/厂商码和本项目栈帧，不输出异常原文，避免带出 SQL 或连接信息。
     */
    private String diagnostics(List<SyncOutcome> outcomes, int gamedayId, ItFixtures.SeededRound firstRound,
                               ItFixtures.SeededRound secondRound) {
        StringBuilder text = new StringBuilder("并发赛程同步诊断:");
        String[] labels = {"first", "second"};
        for (int index = 0; index < outcomes.size(); index++) {
            SyncOutcome outcome = outcomes.get(index);
            text.append("\n  [").append(labels[index]).append("] thread=").append(outcome.thread());
            if (outcome.result() != null) {
                text.append(" dto.status=").append(outcome.result().getStatus())
                        .append(" syncRecordId=").append(outcome.result().getSyncRecordId())
                        .append(" dto.error=").append(abbreviate(outcome.result().getErrorMessage()));
            } else {
                text.append(" error=").append(describe(outcome.error()));
            }
        }
        List<String> rows = fixtures.jdbc().query(
                "SELECT session_key, round_id FROM meeting_session WHERE gameday_id = ? ORDER BY id",
                (rs, rowNum) -> "sessionKey=" + rs.getInt(1) + "->round=" + rs.getLong(2), gamedayId);
        text.append("\n  db.meeting_session[gameday=").append(gamedayId).append("]=").append(rows)
                .append(" firstRound=").append(firstRound.roundId())
                .append(" secondRound=").append(secondRound.roundId())
                .append(" feedRequests=").append(REQUESTS.get());
        text.append("\n  server: ").append(serverSettings());
        text.append("\n  timeline:\n    ").append(timelineText());
        return text.toString();
    }

    /** 读取与锁等待相关的全局变量（任何账号都可读），用于判断阻塞是否来自锁等待超时。 */
    private String serverSettings() {
        try {
            return fixtures.jdbc().queryForObject(
                    "SELECT CONCAT('lockWaitTimeoutSec=', @@global.innodb_lock_wait_timeout, "
                            + "' deadlockDetect=', @@global.innodb_deadlock_detect, "
                            + "' isolation=', @@global.transaction_isolation, "
                            + "' rollbackOnTimeout=', @@global.innodb_rollback_on_timeout)", String.class);
        } catch (RuntimeException error) {
            return "unavailable(" + error.getClass().getSimpleName() + ")";
        }
    }

    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 6; depth++) {
            if (depth > 0) {
                text.append(" <- ");
            }
            text.append(cursor.getClass().getSimpleName());
            if (cursor instanceof InvalidRequestException) {
                // 该异常的 message 是项目内固定的内部描述，可安全输出。
                text.append('(').append(cursor.getMessage()).append(')');
            }
            if (cursor instanceof SQLException sql) {
                text.append("[sqlState=").append(sql.getSQLState()).append(",vendorCode=")
                        .append(sql.getErrorCode());
                String serverMessage = serverReportedMessage(sql);
                if (serverMessage != null) {
                    text.append(",serverMessage=").append(serverMessage);
                }
                text.append(']');
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        List<String> frames = Arrays.stream(error.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.lbz.f1aipredict.")
                        && !frame.getClassName().startsWith("com.lbz.f1aipredict.it."))
                .limit(5)
                .map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1)
                        + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                .toList();
        return text + " at " + frames;
    }

    /**
     * 仅当 SQLState 属于服务端语义错误（数据/完整性约束/事务回滚/语法/通用服务端错误）时输出其消息；
     * 连接、认证类错误（08、28 等）可能带出地址或账号，一律不输出。
     */
    private static String serverReportedMessage(SQLException sql) {
        String state = sql.getSQLState();
        String message = sql.getMessage();
        if (state == null || message == null) {
            return null;
        }
        boolean serverSide = state.startsWith("22") || state.startsWith("23") || state.startsWith("40")
                || state.startsWith("42") || state.startsWith("HY");
        String lower = message.toLowerCase(Locale.ROOT);
        if (!serverSide || lower.contains("jdbc:") || lower.contains("password") || lower.contains("access denied")) {
            return null;
        }
        return abbreviate(message.replaceAll("\\s+", " ").trim());
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "null";
        }
        return message.length() <= 120 ? message : message.substring(0, 120) + "...";
    }

    private void stageResponses(String... bodies) {
        LinkedBlockingQueue<String> queued = new LinkedBlockingQueue<>();
        for (String body : bodies) {
            queued.add(body);
        }
        scheduleBodies = queued;
        requestBarrier = new CountDownLatch(bodies.length);
        REQUESTS.set(0);
    }

    private int nextGamedayId() {
        Integer next = fixtures.jdbc().queryForObject(
                "SELECT COALESCE(MAX(gameday_id), 700000) + 1 FROM meeting_session", Integer.class);
        if (next == null) {
            throw new IllegalStateException("无法为赛程集成测试分配比赛日 ID");
        }
        return next;
    }

    private int nextSessionKey() {
        Integer next = fixtures.jdbc().queryForObject(
                "SELECT COALESCE(MAX(session_key), 7000000) + 1 FROM meeting_session", Integer.class);
        if (next == null) {
            throw new IllegalStateException("无法为赛程集成测试分配 Session key");
        }
        return next;
    }

    private int seasonYear(ItFixtures.SeededRound round) {
        Integer year = fixtures.jdbc().queryForObject(
                "SELECT year FROM season WHERE id = ?", Integer.class, round.seasonId());
        if (year == null) {
            throw new IllegalStateException("赛季夹具缺少 year");
        }
        return year;
    }

    private static String scheduleJson(int year, int gamedayId, int sessionKey, int meetingId, int meetingNumber) {
        return """
                {"Data":{"Value":[{"RaceId":%d,"GamedayId":%d,"Season":"%d",
                "FOMMEETINGSESSIONKEY":"%d","SessionName":"Race","SessionType":"Race",
                "SessionStartDateISO8601":"2026-03-07T15:00:00Z","SessionEndDateISO8601":"2026-03-07T17:00:00Z",
                "MeetingId":%d,"MeetingNumber":%d,"MeetingName":"IT Grand Prix",
                "MeetingOfficialName":"IT Grand Prix %d","MeetingLocation":"IT Circuit",
                "CountryName":"IT Country","CircuitOfficialName":"IT Circuit","IsBonusRound":0}]}}
                """.formatted(sessionKey, gamedayId, year, sessionKey, meetingId, meetingNumber, meetingNumber);
    }

    private static MockWebServer startFeed() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                REQUESTS.incrementAndGet();
                String body = scheduleBodies.poll();
                if (body == null) {
                    return new MockResponse().setResponseCode(500);
                }
                requestBarrier.countDown();
                if (!requestBarrier.await(20, TimeUnit.SECONDS)) {
                    return new MockResponse().setResponseCode(504);
                }
                return new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body);
            }
        });
        try {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
        } catch (IOException error) {
            throw new IllegalStateException("测试 Feed 服务启动失败", error);
        }
        return server;
    }

    private record SyncOutcome(String thread, SyncResultDto result, Throwable error) {
    }

    /** 仅本 IT 使用的 Mapper 外包装：把真实 MyBatis Mapper 代理包一层拦截器，不替换也不缓存任何查询结果。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class RangeReadProbeConfiguration {

        @Bean
        static BeanPostProcessor meetingSessionRangeReadProbe() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof MeetingSessionMapper) || !"meetingSessionMapper".equals(beanName)) {
                        return bean;
                    }
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.setInterfaces(MeetingSessionMapper.class);
                    factory.setProxyTargetClass(false);
                    MethodInterceptor interceptor = PROBE::around;
                    factory.addAdvice(interceptor);
                    return factory.getProxy(MeetingSessionMapper.class.getClassLoader());
                }
            };
        }
    }

    /**
     * 比赛日范围读屏障。未启用或调用不匹配时直接转发；启用后只对目标比赛日的
     * selectAllByGamedayIdForUpdate 生效：先 proceed() 执行真实 SQL，返回后再在前两次调用上互相等待，
     * 并记录范围读/INSERT 的线程时间线（仅线程名、固定事件文本与异常类名）。
     */
    private static final class RangeReadProbe {

        private static final String RANGE_READ = "selectAllByGamedayIdForUpdate";
        private static final String INSERT = "insert";

        private volatile Integer gamedayId;
        private volatile AtomicInteger rangeReads = new AtomicInteger();
        private volatile CountDownLatch bothTransactionsReady = new CountDownLatch(2);
        private volatile Consumer<String> marker = event -> {
        };

        private void arm(int targetGamedayId, Consumer<String> eventMarker) {
            rangeReads = new AtomicInteger();
            bothTransactionsReady = new CountDownLatch(2);
            marker = eventMarker;
            gamedayId = targetGamedayId;
        }

        private void disarm() {
            gamedayId = null;
            marker = event -> {
            };
        }

        private Object around(MethodInvocation invocation) throws Throwable {
            Integer armed = gamedayId;
            String methodName = invocation.getMethod().getName();
            if (armed == null) {
                return invocation.proceed();
            }
            if (INSERT.equals(methodName)) {
                // 记录 INSERT 的发起、返回与失败，用于判断范围锁之后的写入是否互相等待或被死锁回滚。
                return recordInsert(invocation);
            }
            Object[] arguments = invocation.getArguments();
            if (!RANGE_READ.equals(methodName) || arguments.length != 1 || !armed.equals(arguments[0])) {
                return invocation.proceed();
            }
            Consumer<String> mark = marker;
            AtomicInteger reads = rangeReads;
            CountDownLatch ready = bothTransactionsReady;
            mark.accept("range-read-request gameday=" + armed);
            Object sessions;
            try {
                sessions = invocation.proceed();
            } catch (Throwable error) {
                mark.accept("range-read threw " + error.getClass().getSimpleName());
                throw error;
            }
            int readNo = reads.getAndIncrement();
            String rows = sessions instanceof List<?> list ? String.valueOf(list.size()) : "?";
            if (readNo >= 2) {
                mark.accept("range-read#" + readNo + " rows=" + rows);
                return sessions;
            }
            mark.accept("range-read#" + readNo + " rows=" + rows + " waiting-barrier");
            ready.countDown();
            try {
                if (!ready.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError("两个赛程事务未同时到达比赛日当前读");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待赛程事务屏障时被中断", interrupted);
            }
            mark.accept("range-read#" + readNo + " barrier-released");
            return sessions;
        }

        private Object recordInsert(MethodInvocation invocation) throws Throwable {
            Consumer<String> mark = marker;
            mark.accept("session-insert-request");
            try {
                Object result = invocation.proceed();
                mark.accept("session-insert-returned");
                return result;
            } catch (Throwable error) {
                mark.accept("session-insert threw " + error.getClass().getSimpleName());
                throw error;
            }
        }
    }
}

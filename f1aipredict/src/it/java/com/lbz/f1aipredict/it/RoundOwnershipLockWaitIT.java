package com.lbz.f1aipredict.it;

import com.lbz.f1aipredict.common.InvalidRequestException;
import com.lbz.f1aipredict.it.support.ItFixtures;
import com.lbz.f1aipredict.scoring.service.OfficialAnswerService;
import com.lbz.f1aipredict.season.mapper.MeetingSessionMapper;
import com.lbz.f1aipredict.season.mapper.RoundMapper;
import okhttp3.mockwebserver.MockWebServer;
import org.assertj.core.api.SoftAssertions;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证旧 round 锁等待结束后，答案登记事务会按当前 Session 归属复核。 */
@Execution(ExecutionMode.SAME_THREAD)
@Import(RoundOwnershipLockWaitIT.RoundMapperProbeConfiguration.class)
class RoundOwnershipLockWaitIT extends AbstractDStageIT {

    private static final MockWebServer FEED = startFeed();
    private static final RoundMapperProbe PROBE = new RoundMapperProbe();
    private static final Duration LOCK_WAIT_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private OfficialAnswerService answerService;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void feedProperties(DynamicPropertyRegistry registry) {
        registry.add("f1predict.feed.base-url", () -> FEED.url("/").toString());
    }

    @AfterAll
    static void stopFeed() throws IOException {
        FEED.shutdown();
    }

    @Test
    void answerSyncRejectsOwnershipCommittedWhileWaitingForOldRoundLock() throws Exception {
        ItFixtures.SeededRound oldRound = fixtures.seedRound(1);
        ItFixtures.SeededRound newRound = fixtures.seedRound(1);
        PROBE.reset();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Connection lockHolder = null;
        try {
            lockHolder = dataSource.getConnection();
            lockHolder.setAutoCommit(false);
            lockOldRound(lockHolder, oldRound.roundId());

            Future<?> sync = pool.submit(() -> answerService.syncAnswers(oldRound.gamedayId()));
            assertThat(PROBE.beforeRoundLock.await(10, TimeUnit.SECONDS))
                    .as("服务已完成普通 Session 预读并进入 round 锁 Mapper")
                    .isTrue();
            long syncConnectionId = PROBE.syncConnectionId.get();
            assertThat(syncConnectionId).isPositive();
            assertThat(List.copyOf(PROBE.statementFlow))
                    .as("普通 Session 查询必须先于 SELECT FOR UPDATE，并已返回建立一致性读快照")
                    .containsSubsequence("session-read-complete", "round-lock-enter");

            awaitRoundLockQueryActive(syncConnectionId, sync);
            updateSessionOwnership(lockHolder, oldRound.gamedayId(), newRound.roundId());
            lockHolder.commit();

            assertThat(fixtures.count("SELECT COUNT(*) FROM meeting_session WHERE gameday_id = ? AND round_id = ?",
                    oldRound.gamedayId(), newRound.roundId())).isEqualTo(1);
            ExecutionException syncFailure = null;
            try {
                sync.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException error) {
                syncFailure = error;
            }
            long runningRecords = fixtures.count("SELECT COUNT(*) FROM sync_record "
                    + "WHERE source_type = 'ANSWERS' AND gameday_id = ? AND status = 'RUNNING'",
                    oldRound.gamedayId());
            int feedRequests = FEED.getRequestCount();
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(syncFailure)
                    .as("锁等待后的归属变化必须在登记与 HTTP 前被拒绝")
                    .isNotNull()
                    .hasRootCauseInstanceOf(InvalidRequestException.class);
            softly.assertThat(runningRecords).isZero();
            softly.assertThat(feedRequests).isZero();
            softly.assertAll();
        } finally {
            try {
                if (lockHolder != null) {
                    try {
                        lockHolder.rollback();
                    } finally {
                        lockHolder.close();
                    }
                }
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS))
                        .as("答案同步线程应在释放旧 round 锁后结束")
                        .isTrue();
            }
        }
    }

    private void lockOldRound(Connection connection, long roundId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM round WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, roundId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("旧 round 锚点必须存在").isTrue();
                assertThat(rows.getLong(1)).isEqualTo(roundId);
            }
        }
    }

    private void updateSessionOwnership(Connection connection, int gamedayId, long roundId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE meeting_session SET round_id = ? WHERE gameday_id = ?")) {
            statement.setLong(1, roundId);
            statement.setInt(2, gamedayId);
            assertThat(statement.executeUpdate()).as("本测试只迁移夹具创建的 Session").isEqualTo(1);
        }
    }

    /**
     * 不把 Java 屏障当作数据库锁等待证明：旧 round 排他锁仍由本测试持有时，
     * 轮询同账号可见的 SHOW FULL PROCESSLIST，要求目标主键 FOR UPDATE 查询在服务端当前状态持续至少一秒。
     * 该 SQL 只读取一个已存在主键；在该锁持有期间，它不能完成，只能等待该行锁。
     */
    private void awaitRoundLockQueryActive(long connectionId, Future<?> sync) throws InterruptedException {
        long deadline = System.nanoTime() + LOCK_WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (sync.isDone()) {
                throw new AssertionError("答案同步在旧 round 锁释放前已结束，未观察到锁等待");
            }
            if (isRoundLockQueryWaiting(connectionId)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("在 " + LOCK_WAIT_TIMEOUT + " 内未观察到 B 连接在服务端持续执行 round FOR UPDATE");
    }

    private boolean isRoundLockQueryWaiting(long connectionId) {
        return fixtures.jdbc().query("SHOW FULL PROCESSLIST", rows -> {
            while (rows.next()) {
                if (rows.getLong("Id") != connectionId
                        || !"Query".equalsIgnoreCase(rows.getString("Command"))
                        || rows.getLong("Time") < 1) {
                    continue;
                }
                String info = rows.getString("Info");
                if (info == null) {
                    continue;
                }
                String normalized = info.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
                if (normalized.startsWith("SELECT * FROM ROUND WHERE ID = ")
                        && normalized.endsWith("FOR UPDATE")) {
                    return true;
                }
            }
            return false;
        });
    }

    private static MockWebServer startFeed() {
        MockWebServer server = new MockWebServer();
        try {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
        } catch (IOException error) {
            throw new IllegalStateException("测试 Feed 服务启动失败", error);
        }
        return server;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RoundMapperProbeConfiguration {

        /** 包装真实 Mapper 代理；通知发生在真实 FOR UPDATE SQL 转发之前。 */
        @Bean
        static BeanPostProcessor roundMapperWaitProbe(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    boolean roundMapper = bean instanceof RoundMapper && "roundMapper".equals(beanName);
                    boolean sessionMapper = bean instanceof MeetingSessionMapper
                            && "meetingSessionMapper".equals(beanName);
                    if (!roundMapper && !sessionMapper) {
                        return bean;
                    }
                    Class<?> mapperInterface = roundMapper ? RoundMapper.class : MeetingSessionMapper.class;
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.setInterfaces(mapperInterface);
                    factory.setProxyTargetClass(false);
                    MethodInterceptor interceptor = invocation -> {
                        String methodName = invocation.getMethod().getName();
                        if (sessionMapper && "selectAllByGamedayId".equals(methodName)) {
                            Object result = invocation.proceed();
                            if (PROBE.beforeRoundLock.getCount() > 0) {
                                PROBE.statementFlow.add("session-read-complete");
                            }
                            return result;
                        }
                        if (roundMapper && "selectByIdForUpdate".equals(methodName)) {
                            Long connectionId = jdbcTemplateProvider.getObject()
                                    .queryForObject("SELECT CONNECTION_ID()", Long.class);
                            PROBE.syncConnectionId.set(connectionId == null ? -1 : connectionId);
                            PROBE.statementFlow.add("round-lock-enter");
                            PROBE.beforeRoundLock.countDown();
                        }
                        return invocation.proceed();
                    };
                    factory.addAdvice(interceptor);
                    return factory.getProxy(mapperInterface.getClassLoader());
                }
            };
        }
    }

    private static final class RoundMapperProbe {

        private volatile CountDownLatch beforeRoundLock = new CountDownLatch(1);
        private final AtomicLong syncConnectionId = new AtomicLong(-1);
        private final ConcurrentLinkedQueue<String> statementFlow = new ConcurrentLinkedQueue<>();

        private void reset() {
            syncConnectionId.set(-1);
            statementFlow.clear();
            beforeRoundLock = new CountDownLatch(1);
        }
    }
}

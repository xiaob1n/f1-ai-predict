package com.lbz.f1aipredict.it.support;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbz.f1aipredict.config.MybatisPlusConfig;
import com.lbz.f1aipredict.config.TimeConfig;
import com.lbz.f1aipredict.prediction.inbound.OutcomeMessageParser;
import com.lbz.f1aipredict.prediction.inbound.PredictionOutcomePersistenceService;
import com.lbz.f1aipredict.prediction.service.PredictionBatchService;
import com.lbz.f1aipredict.prediction.service.PredictionLockService;
import com.lbz.f1aipredict.prediction.service.impl.PredictionBatchServiceImpl;
import com.lbz.f1aipredict.prediction.service.impl.PredictionBatchTransactionExecutor;
import com.lbz.f1aipredict.prediction.service.impl.PredictionLockServiceImpl;
import com.lbz.f1aipredict.prediction.service.impl.PredictionRequestSerializer;
import com.lbz.f1aipredict.prediction.service.impl.PredictionRequestSnapshotResolver;
import com.lbz.f1aipredict.question.service.impl.PredictionQuestionReadServiceImpl;
import com.lbz.f1aipredict.scoring.engine.impl.SingleChoiceScoringStrategy;
import com.lbz.f1aipredict.scoring.service.impl.OfficialAnswerServiceImpl;
import com.lbz.f1aipredict.scoring.service.impl.OfficialAnswerTransactionExecutor;
import com.lbz.f1aipredict.scoring.service.impl.ScoringServiceImpl;
import com.lbz.f1aipredict.season.service.impl.MeetingSessionServiceImpl;
import com.lbz.f1aipredict.season.service.impl.RoundServiceImpl;
import com.lbz.f1aipredict.sync.client.F1PredictFeedClient;
import com.lbz.f1aipredict.sync.config.F1PredictFeedProperties;
import com.lbz.f1aipredict.sync.store.SyncPersistenceStore;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * D 阶段集成测试的精简 Spring 上下文。
 * <p>
 * 不使用 {@code @EnableAutoConfiguration} 与包扫描：只显式导入真实数据源、事务、JdbcTemplate、Jackson
 * 与 MyBatis-Plus 自动配置，以及被测的生产 Service/组件。因此 Nacos、RabbitMQ、outbox 发布器、
 * 消息监听、Web 层与任何 {@code @Scheduled} 调度都不会被装配；也未开启 {@code @EnableScheduling}，
 * 调度入口只能由测试显式调用。Feed 客户端是真实实现，但根地址只指向回环占位或 MockWebServer。
 * 测试启动时以 {@code spring.config.name=d-stage-it} 只加载 IT 配置，不读取 application.yaml。
 */
@SpringBootConfiguration
@ImportAutoConfiguration({
        PropertyPlaceholderAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
@EnableConfigurationProperties(F1PredictFeedProperties.class)
@Import({
        MybatisPlusConfig.class,
        TimeConfig.class,
        RoundServiceImpl.class,
        MeetingSessionServiceImpl.class,
        PredictionQuestionReadServiceImpl.class,
        PredictionBatchServiceImpl.class,
        PredictionBatchTransactionExecutor.class,
        PredictionRequestSnapshotResolver.class,
        PredictionRequestSerializer.class,
        PredictionLockServiceImpl.class,
        OutcomeMessageParser.class,
        PredictionOutcomePersistenceService.class,
        SingleChoiceScoringStrategy.class,
        ScoringServiceImpl.class,
        OfficialAnswerTransactionExecutor.class,
        OfficialAnswerServiceImpl.class,
        SyncPersistenceStore.class,
        F1PredictFeedClient.class
})
public class DStageItApplication {

    /** 测试专用属性：只加载 d-stage-it.yaml，不读取 application.yaml，也不触发 Nacos 导入。 */
    public static final String CONFIG_NAME_PROPERTY = "spring.config.name=d-stage-it";

    /** 故障注入后处理器须为静态 Bean，确保在 Mapper Bean 创建前注册。 */
    @Bean
    static ScoringFaultInjector scoringFaultInjector() {
        return new ScoringFaultInjector();
    }

    @Bean
    ItDatabaseGuard itDatabaseGuard(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        return new ItDatabaseGuard(dataSource, jdbcTemplate);
    }

    /** 依赖守护 Bean，保证任何夹具写入之前已完成专属库核验。 */
    @Bean
    ItFixtures itFixtures(ItDatabaseGuard guard, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new ItFixtures(jdbcTemplate, objectMapper);
    }

    @Bean
    ItScenario itScenario(ItFixtures fixtures, PredictionBatchService batchService, OutcomeMessageParser parser,
                          PredictionOutcomePersistenceService outcomeService, PredictionLockService lockService,
                          OfficialAnswerTransactionExecutor answerExecutor) {
        return new ItScenario(fixtures, batchService, parser, outcomeService, lockService, answerExecutor);
    }
}

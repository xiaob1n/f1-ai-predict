package com.lbz.f1aipredict;

import com.lbz.f1aipredict.sync.client.F1PredictFeedClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 冒烟：能拉起无数据源的应用上下文。
 * <p>
 * {@link MockitoBean} 替换真实 {@link F1PredictFeedClient}，避免构造 WebClient 或访问官方 Feed。
 * 延迟初始化避免 {@code @MapperScan} 在无 DataSource 时急切创建 Mapper。
 */
@SpringBootTest(
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "spring.main.lazy-initialization=true"
    },
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
// 激活 test profile 以加载 application-test.yaml，使 scheduler 默认关闭，避免其急切创建触发缺失 DataSource 的构造失败
@ActiveProfiles("test")
class F1aipredictApplicationTests {

    /** 替换生产 Feed 客户端，禁止打真实 f1predict.formula1.com */
    @MockitoBean
    private F1PredictFeedClient f1PredictFeedClient;

    @Autowired
    private Environment environment;

    /** 测试环境仅加载本地配置；数据库连接和 Nacos 服务均不参与上下文启动。 */
    @Test
    void contextLoads() {
        assertEquals("false", environment.getProperty("spring.cloud.nacos.config.enabled"));
        assertNull(environment.getProperty("spring.config.import"));
        assertNull(environment.getProperty("spring.datasource.url"));
        assertNull(environment.getProperty("f1predict.feed.base-url"));
    }

}

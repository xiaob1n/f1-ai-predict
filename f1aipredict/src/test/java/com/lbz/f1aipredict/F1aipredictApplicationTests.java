package com.lbz.f1aipredict;

import com.lbz.f1aipredict.sync.client.F1PredictFeedClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
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
class F1aipredictApplicationTests {

    /** 替换生产 Feed 客户端，禁止打真实 f1predict.formula1.com */
    @MockitoBean
    private F1PredictFeedClient f1PredictFeedClient;

    @Test
    void contextLoads() {
    }

}

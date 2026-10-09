package com.lbz.f1aipredict.it;

import com.lbz.f1aipredict.it.support.DStageItApplication;
import com.lbz.f1aipredict.it.support.ItEnvironment;
import com.lbz.f1aipredict.it.support.ItFixtures;
import com.lbz.f1aipredict.it.support.ItScenario;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

/**
 * 共享精简上下文的 MySQL 集成测试基类。
 * <p>
 * 必须显式指定 {@link DStageItApplication}，否则 Spring Boot 会向上找到生产启动类并装配全量组件。
 * 连接变量在上下文加载前校验，缺失直接失败；每个测试只读写自己插入的行。
 */
@SpringBootTest(classes = DStageItApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = DStageItApplication.CONFIG_NAME_PROPERTY)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
abstract class AbstractDStageIT {

    @Autowired
    protected ItScenario scenario;

    @Autowired
    protected ItFixtures fixtures;

    @BeforeAll
    static void requireDedicatedDatabase() {
        ItEnvironment.requireDedicatedDatabase();
    }
}

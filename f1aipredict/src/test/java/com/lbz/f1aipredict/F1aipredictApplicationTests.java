package com.lbz.f1aipredict;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class F1aipredictApplicationTests {

    @Test
    void contextLoads() {
    }

}

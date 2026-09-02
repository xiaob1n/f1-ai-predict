package com.lbz.f1aipredict;

import com.lbz.f1aipredict.sync.config.F1PredictFeedProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(F1PredictFeedProperties.class)
// 启用 Spring 定时任务调度，供每小时问题同步任务使用
@EnableScheduling
public class F1aipredictApplication {

    public static void main(String[] args) {
        SpringApplication.run(F1aipredictApplication.class, args);
    }

}

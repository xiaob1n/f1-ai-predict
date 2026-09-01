package com.lbz.f1aipredict;

import com.lbz.f1aipredict.sync.config.F1PredictFeedProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(F1PredictFeedProperties.class)
public class F1aipredictApplication {

    public static void main(String[] args) {
        SpringApplication.run(F1aipredictApplication.class, args);
    }

}

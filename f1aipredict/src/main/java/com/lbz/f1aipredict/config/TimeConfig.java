package com.lbz.f1aipredict.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一 UTC 时钟配置。
 * <p>
 * 赛季/分站「当前」对象选择依赖确定性时间，禁止在业务代码中直接调用
 * {@code Instant.now()} 或 {@code LocalDate.now()}。本配置注册唯一
 * {@link Clock} Bean，供 Season/Round 查询 Service 构造器注入。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟，全仓库唯一 Clock Bean。
     *
     * @return {@link Clock#systemUTC()}
     */
    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}

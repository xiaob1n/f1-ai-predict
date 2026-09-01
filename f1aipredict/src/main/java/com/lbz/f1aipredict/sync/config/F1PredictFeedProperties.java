package com.lbz.f1aipredict.sync.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * F1 Predict 官方 Feed 的类型安全配置。
 * <p>
 * YAML 前缀：{@code f1predict.feed}。业务 / 客户端代码禁止硬编码 Feed URL，一律从此 Bean 读取。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "f1predict.feed")
public class F1PredictFeedProperties {

    /** Feed 站点根地址，例如 https://f1predict.formula1.com */
    @NotBlank
    private String baseUrl;

    /** 赛程 Feed 路径，例如 /feeds/schedule/raceday_en.json */
    @NotBlank
    private String schedulePath;

    /** 限制 / 当前轮次 Feed 路径，例如 /feeds/limits/constraints.json */
    @NotBlank
    private String limitsPath;

    /** 题目 Feed 路径模板，须含 {@code {gamedayId}} 占位符 */
    @NotBlank
    private String questionsPath;

    /** Mix API 实时 Feed 路径，例如 /feeds/live/mixapi.json */
    @NotBlank
    private String mixApiPath;

    /** Web 配置 Feed 路径，例如 /feeds/apps/web_config.json */
    @NotBlank
    private String webConfigPath;

    /** HTTP 连接超时（毫秒），供后续 WebClient 使用 */
    @Positive
    private int connectTimeoutMs = 5_000;

    /** HTTP 读取超时（毫秒），供后续 WebClient 使用 */
    @Positive
    private int readTimeoutMs = 10_000;
}

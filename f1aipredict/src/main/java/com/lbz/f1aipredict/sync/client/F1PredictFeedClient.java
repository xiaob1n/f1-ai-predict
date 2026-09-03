package com.lbz.f1aipredict.sync.client;

import com.lbz.f1aipredict.sync.FeedSyncException;
import com.lbz.f1aipredict.sync.config.F1PredictFeedProperties;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * F1 Predict 官方 Feed 的 WebClient 包装。
 * <p>
 * URL 一律来自 {@link F1PredictFeedProperties}，禁止硬编码。
 * 返回同步 {@link String}，方便后续 Service 串行调用；WebClient 由本类根据超时配置构造。
 */
@Slf4j
@Component
public class F1PredictFeedClient {

    /** 固定客户端标识，避免改 YAML（Todo 3 不碰配置文件） */
    public static final String USER_AGENT = "F1AiPredict-FeedClient/1.0";

    private static final String GAMEDAY_PLACEHOLDER = "{gamedayId}";

    private final F1PredictFeedProperties properties;
    private final WebClient webClient;

    /**
     * 生产入口：根据配置构造带超时与默认头的 WebClient。
     * 必须标 {@link Autowired}：存在包内双参测试构造器时，Spring 不会自动挑选本构造器。
     *
     * @param properties Feed 根地址、路径与超时
     */
    @Autowired
    public F1PredictFeedClient(F1PredictFeedProperties properties) {
        this(properties, createWebClient(properties));
    }

    /**
     * 测试入口：注入已指向 MockWebServer 的 WebClient。
     */
    F1PredictFeedClient(F1PredictFeedProperties properties, WebClient webClient) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.webClient = Objects.requireNonNull(webClient, "webClient must not be null");
    }

    /**
     * 按配置构造 WebClient：连接/读取超时、User-Agent、Accept=application/json。
     */
    static WebClient createWebClient(F1PredictFeedProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        int connectTimeoutMs = properties.getConnectTimeoutMs();
        int readTimeoutMs = properties.getReadTimeoutMs();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 拉取赛程 Feed（{@code schedulePath}）。
     *
     * @return 原始 JSON 字符串
     */
    public String fetchSchedule() {
        return getJson(properties.getSchedulePath());
    }

    /**
     * 拉取限制 / 当前轮次 Feed（{@code limitsPath}）。
     *
     * @return 原始 JSON 字符串
     */
    public String fetchLimits() {
        return getJson(properties.getLimitsPath());
    }

    /**
     * 拉取指定比赛日题目 Feed，将 {@code questionsPath} 中的 {@code {gamedayId}} 替换为实际 ID。
     *
     * @param gamedayId 比赛日 ID，不得为 null
     * @return 原始 JSON 字符串
     * @throws IllegalArgumentException 当 gamedayId 为 null
     */
    public String fetchQuestions(Integer gamedayId) {
        if (gamedayId == null) {
            throw new IllegalArgumentException("gamedayId must not be null");
        }
        String path = properties.getQuestionsPath().replace(GAMEDAY_PLACEHOLDER, String.valueOf(gamedayId));
        return getJson(path);
    }

    /**
     * GET 指定相对路径，阻塞等待响应体。HTTP 错误与网络失败均转为 {@link FeedSyncException}。
     */
    private String getJson(String path) {
        String relativePath = ensureLeadingSlash(path);
        long startedAt = System.currentTimeMillis();
        log.debug("开始调用外部 Feed: path={}", relativePath);
        try {
            String body = webClient.get()
                    .uri(relativePath)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response -> {
                        int status = response.statusCode().value();
                        // 消费 body 避免连接泄漏，但不把原文塞进异常 message 或日志
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(ignored -> Mono.error(new FeedSyncException(
                                        "Feed request failed with HTTP " + status, status)));
                    })
                    .bodyToMono(String.class)
                    .block();
            String json = body == null ? "" : body;
            log.info("外部 Feed 调用完成: path={}, status={}, durationMs={}, responseBytes={}",
                    relativePath, 200, elapsedMs(startedAt), utf8ByteLength(json));
            return json;
        } catch (FeedSyncException ex) {
            log.warn("外部 Feed 调用失败: path={}, status={}, durationMs={}, error={}",
                    relativePath, ex.getHttpStatus(), elapsedMs(startedAt), ex.getMessage());
            throw ex;
        } catch (WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            log.warn("外部 Feed 调用失败: path={}, status={}, durationMs={}",
                    relativePath, status, elapsedMs(startedAt));
            throw new FeedSyncException("Feed request failed with HTTP " + status, status, ex);
        } catch (WebClientRequestException ex) {
            log.error("外部 Feed 网络失败: path={}, durationMs={}, errorType={}, causeType={}",
                    relativePath, elapsedMs(startedAt), ex.getClass().getSimpleName(), causeType(ex));
            throw new FeedSyncException("Feed request network failure", ex);
        } catch (RuntimeException ex) {
            FeedSyncException nested = findCause(ex, FeedSyncException.class);
            if (nested != null) {
                log.warn("外部 Feed 调用失败: path={}, status={}, durationMs={}, error={}",
                        relativePath, nested.getHttpStatus(), elapsedMs(startedAt), nested.getMessage());
                throw nested;
            }
            log.error("外部 Feed 网络失败: path={}, durationMs={}, errorType={}, causeType={}",
                    relativePath, elapsedMs(startedAt), ex.getClass().getSimpleName(), causeType(ex));
            throw new FeedSyncException("Feed request network failure", ex);
        }
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private static int utf8ByteLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String ensureLeadingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static String causeType(Throwable throwable) {
        return throwable.getCause() == null ? null : throwable.getCause().getClass().getSimpleName();
    }
}

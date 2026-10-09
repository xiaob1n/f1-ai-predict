package com.lbz.f1aipredict.it.support;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只监听 127.0.0.1 的替身 Feed：按请求路径返回测试设置的题目/答案 JSON，未设置的路径返回 404。
 * 生产 {@code F1PredictFeedClient} 通过 {@code f1predict.feed.base-url} 指向它，绝不访问真实站点。
 */
public final class ItFeedServer implements AutoCloseable {

    /** 与 d-stage-it.yaml 中 questions-path 一致。 */
    private static final String QUESTIONS_PATH = "/feeds/questions/questions_%d_en.json";

    private final MockWebServer server = new MockWebServer();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    public ItFeedServer() {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                requestCount.incrementAndGet();
                String body = bodies.get(request.getPath());
                if (body == null) {
                    return new MockResponse().setResponseCode(404);
                }
                return new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body);
            }
        });
        try {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
        } catch (IOException error) {
            throw new UncheckedIOException("替身 Feed 启动失败", error);
        }
    }

    /** 供 {@code f1predict.feed.base-url} 使用的根地址。 */
    public String baseUrl() {
        return server.url("/").toString();
    }

    /** 设置某比赛日题目 Feed 的当前响应体。 */
    public void setQuestionsFeed(int gamedayId, String body) {
        bodies.put(QUESTIONS_PATH.formatted(gamedayId), body);
    }

    /** 已收到的请求总数，用于确认确实经过 HTTP 客户端。 */
    public int requestCount() {
        return requestCount.get();
    }

    @Override
    public void close() {
        try {
            server.shutdown();
        } catch (IOException error) {
            throw new UncheckedIOException("替身 Feed 关闭失败", error);
        }
    }
}

package com.lbz.f1aipredict.common;

import com.lbz.f1aipredict.common.dto.ApiErrorResponse;
import com.lbz.f1aipredict.sync.FeedSyncException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 * <p>
 * 通过 {@link RestControllerAdvice} 统一捕获 REST 控制器抛出的业务异常，
 * 并转换为稳定、安全的 HTTP 响应结构（{@link ApiErrorResponse}），
 * 避免将 SQL、堆栈、内部类名或外部 Feed URL 等敏感信息暴露给客户端。
 * 未注册的异常（如 {@link IllegalStateException}）不映射为本 advice 的 404 / 502。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 对外稳定摘要：无上游 HTTP 状态时使用 */
    private static final String FEED_SYNC_FAILED = "Feed sync failed";

    /**
     * 处理资源未找到异常。
     * <p>
     * 将 {@link ResourceNotFoundException} 映射为 HTTP 404，
     * 并返回统一错误码 {@code RESOURCE_NOT_FOUND}，
     * 错误信息直接复用异常中安全的人类可读描述。
     *
     * @param ex 资源未找到异常
     * @return HTTP 404 + 统一错误响应体
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        ApiErrorResponse body = ApiErrorResponse.builder()
                .code("RESOURCE_NOT_FOUND")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 处理 Feed 同步失败。
     * <p>
     * 将 {@link FeedSyncException} 映射为 HTTP 502 Bad Gateway，
     * 并返回统一错误码 {@code FEED_SYNC_ERROR}。
     * 对外 message 使用稳定摘要（必要时附带上游 HTTP 状态码），
     * 并防御性剥离 {@code http://}/{@code https://} 片段，避免泄漏 Feed 地址。
     *
     * @param ex Feed 同步异常
     * @return HTTP 502 + 统一错误响应体
     */
    @ExceptionHandler(FeedSyncException.class)
    public ResponseEntity<ApiErrorResponse> handleFeedSync(FeedSyncException ex) {
        ApiErrorResponse body = ApiErrorResponse.builder()
                .code("FEED_SYNC_ERROR")
                .message(safeFeedSyncMessage(ex))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * 构造对外安全摘要：稳定文案 + 可选上游 HTTP 状态，再剥离 URL。
     *
     * @param ex Feed 同步异常
     * @return 不含 URL / 堆栈 / 内部类名的人类可读摘要
     */
    private static String safeFeedSyncMessage(FeedSyncException ex) {
        String message = ex.hasHttpStatus()
                ? FEED_SYNC_FAILED + " with HTTP " + ex.getHttpStatus()
                : FEED_SYNC_FAILED;
        return stripUrls(message);
    }

    /**
     * 防御性剥离消息中的 http/https URL，避免把 Feed 地址泄漏给客户端。
     *
     * @param raw 原始摘要，可空
     * @return 剥离 URL 后的文本；空白时回退为稳定摘要
     */
    private static String stripUrls(String raw) {
        if (raw == null || raw.isBlank()) {
            return FEED_SYNC_FAILED;
        }
        String stripped = raw.replaceAll("(?i)https?://\\S+", "").replaceAll("\\s{2,}", " ").trim();
        return stripped.isEmpty() ? FEED_SYNC_FAILED : stripped;
    }
}
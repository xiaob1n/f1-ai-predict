package com.lbz.f1aipredict.common;

import com.lbz.f1aipredict.common.dto.ApiErrorResponse;
import com.lbz.f1aipredict.sync.FeedSyncException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器。
 * <p>
 * 通过 {@link RestControllerAdvice} 统一捕获 REST 控制器抛出的业务异常，
 * 并转换为稳定、安全的 HTTP 响应结构（{@link ApiErrorResponse}），
 * 避免将 SQL、堆栈、内部类名或外部 Feed URL 等敏感信息暴露给客户端。
 * 未注册的异常（如 {@link IllegalStateException}）不映射为本 advice 的 404 / 400 / 502。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 对外稳定摘要：无上游 HTTP 状态时使用 */
    private static final String FEED_SYNC_FAILED = "Feed sync failed";

    /** 对外固定安全摘要：非法请求不回显底层原文 */
    private static final String INVALID_REQUEST_MESSAGE = "Invalid request";

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
        log.info("资源未找到: errorType={}", ex.getClass().getSimpleName());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .code("RESOURCE_NOT_FOUND")
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 处理非法请求。
     * <p>
     * 将 {@link InvalidRequestException} 映射为 HTTP 400，
     * 并返回统一错误码 {@code INVALID_REQUEST}。
     * 对外 message 固定为安全摘要，不回显异常原文、URL、SQL、内部类名或堆栈。
     *
     * @param ex 非法请求异常
     * @return HTTP 400 + 统一错误响应体
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(InvalidRequestException ex) {
        log.info("非法请求: errorType={}, causeType={}",
                ex.getClass().getSimpleName(), causeType(ex));
        ApiErrorResponse body = ApiErrorResponse.builder()
                .code("INVALID_REQUEST")
                .message(INVALID_REQUEST_MESSAGE)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 将 Bean Validation、JSON 反序列化和参数绑定失败统一转换为安全 400。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            BindException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleBindingFailure(Exception ex) {
        log.info("请求绑定失败: errorType={}", ex.getClass().getSimpleName());
        ApiErrorResponse body = ApiErrorResponse.builder()
                .code("INVALID_REQUEST")
                .message(INVALID_REQUEST_MESSAGE)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
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
        log.error("Feed 同步失败: httpStatus={}, errorType={}, causeType={}",
                ex.getHttpStatus(), ex.getClass().getSimpleName(), causeType(ex));
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

    private static String causeType(Throwable throwable) {
        return throwable.getCause() == null ? null : throwable.getCause().getClass().getSimpleName();
    }
}

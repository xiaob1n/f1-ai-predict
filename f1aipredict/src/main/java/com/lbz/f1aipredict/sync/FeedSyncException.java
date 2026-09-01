package com.lbz.f1aipredict.sync;

/**
 * Feed 同步过程中的可预期失败（HTTP 错误或网络故障）。
 * <p>
 * 消息仅包含安全、人类可读的摘要（含 HTTP 状态码，若有），
 * 不得把完整堆栈或响应体直接暴露给调用方。
 * 全局异常映射由后续 Todo 10 负责，本类不绑定 HTTP 对外状态。
 */
public class FeedSyncException extends RuntimeException {

    /** 上游 Feed 返回的 HTTP 状态码；网络失败等场景为 {@code null} */
    private final Integer httpStatus;

    /**
     * 无 HTTP 状态的失败（例如连接超时、DNS 失败）。
     *
     * @param message 安全摘要
     */
    public FeedSyncException(String message) {
        this(message, null, null);
    }

    /**
     * 带 HTTP 状态的失败（例如上游 500）。
     *
     * @param message    安全摘要，应包含状态码数字
     * @param httpStatus 上游 HTTP 状态码
     */
    public FeedSyncException(String message, Integer httpStatus) {
        this(message, httpStatus, null);
    }

    /**
     * 网络层失败，保留 cause 供日志，但不把堆栈写入 message。
     *
     * @param message 安全摘要
     * @param cause   底层异常
     */
    public FeedSyncException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /**
     * 完整构造。
     *
     * @param message    安全摘要
     * @param httpStatus 上游 HTTP 状态码，可空
     * @param cause      底层异常，可空
     */
    public FeedSyncException(String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /**
     * @return 上游 HTTP 状态码；网络失败时为 {@code null}
     */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /**
     * @return 是否携带上游 HTTP 状态码
     */
    public boolean hasHttpStatus() {
        return httpStatus != null;
    }
}

package com.lbz.f1aipredict.common;

/**
 * HTTP 与定时任务共用的请求追踪标识。
 * <p>
 * 只把短且无控制字符的值写入 MDC / 响应头，避免日志注入。
 */
public final class RequestId {

    /** 对外响应与入站校验使用的请求头 */
    public static final String HEADER = "X-Request-Id";

    /** Logback {@code %X{requestId}} 对应的 MDC 键 */
    public static final String MDC_KEY = "requestId";

    private RequestId() {
    }
}

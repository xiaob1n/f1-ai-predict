package com.lbz.f1aipredict.common;

/**
 * 非法请求异常。
 * <p>
 * 用于表示输入冲突或业务校验失败（如互斥参数、非法状态过滤）。
 * 由全局异常处理统一映射为 HTTP 400，错误码 {@code INVALID_REQUEST}。
 * 对外 message 使用固定安全摘要，绝不回显 URL、SQL、内部类名、堆栈或底层异常原文。
 */
public class InvalidRequestException extends RuntimeException {

    /**
     * 构造一个非法请求异常。
     *
     * @param message 仅供服务端日志的内部描述，不得直接作为对外响应
     */
    public InvalidRequestException(String message) {
        super(message);
    }

    /**
     * 包装底层原因，供服务端排查；cause 不得进入客户端响应。
     *
     * @param message 仅供服务端日志的内部描述
     * @param cause   底层异常，可空
     */
    public InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}

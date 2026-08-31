package com.lbz.f1aipredict.common;

/**
 * 资源未找到异常。
 * <p>
 * 用于表示查询目标（如问题、快照）不存在，或快照与问题归属不匹配等资源级错误。
 * 由全局异常处理（@RestControllerAdvice）统一映射为 HTTP 404，
 * 并在响应 DTO 中使用稳定错误码 RESOURCE_NOT_FOUND。
 * message 仅包含安全、人类可读的资源描述，绝不携带 SQL、堆栈或内部类名等信息。
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * 构造一个资源未找到异常。
     *
     * @param message 安全的人类可读资源描述，
     *                如 "Question not found: 1"、"Snapshot not found: 2"、
     *                "Snapshot does not belong to question: 2/1"
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
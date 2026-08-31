package com.lbz.f1aipredict.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 错误响应 DTO。
 * <p>
 * 供全局异常处理（@RestControllerAdvice）返回稳定、可机读的错误结构。
 * 字段显式声明 camelCase {@link JsonProperty}，如 {"code":"RESOURCE_NOT_FOUND","message":"..."}。
 * 不暴露 SQL、堆栈或内部类名等敏感信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiErrorResponse {

    /** 稳定错误码，如 RESOURCE_NOT_FOUND */
    @JsonProperty("code")
    private String code;

    /** 安全的人类可读错误描述 */
    @JsonProperty("message")
    private String message;
}
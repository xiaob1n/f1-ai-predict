package com.lbz.f1aipredict.common;

import com.lbz.f1aipredict.common.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 * <p>
 * 通过 {@link RestControllerAdvice} 统一捕获 REST 控制器抛出的业务异常，
 * 并转换为稳定、安全的 HTTP 响应结构（{@link ApiErrorResponse}），
 * 避免将 SQL、堆栈或内部类名等敏感信息暴露给客户端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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
}
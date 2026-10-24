package com.cryptobench.benchmark.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一处理业务 Controller 抛出的异常，向前端返回 {error: 提示} 结构。
 * 认证和 CSRF 等过滤器阶段的异常由 SecurityConfiguration 的处理器响应。
 */
@RestControllerAdvice(basePackages = "com.cryptobench.benchmark")
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 保留业务异常明确指定的状态和消息。
     */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of("error", exception.getMessage()));
    }

    /**
     * 将 @Valid 校验失败转换为 400，取第一个字段错误作为提示。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage()).findFirst().orElse("请求参数无效");
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /**
     * 请求体无法解析为期望的 JSON 时返回 400，隐藏底层解析细节。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> handleJson() {
        return ResponseEntity.badRequest().body(Map.of("error", "JSON 请求格式无效"));
    }

    /**
     * 将唯一键或外键等完整性约束失败转换为 409，避免暴露 SQL 细节。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> handleConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "记录已存在或关联数据无效"));
    }

    /**
     * 未知异常完整记录到服务端日志，对客户端只返回通用的 500 提示。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleUnexpected(Exception exception) {
        LOG.error("Unexpected API failure", exception);
        return ResponseEntity.internalServerError().body(Map.of("error", "服务暂时不可用"));
    }
}

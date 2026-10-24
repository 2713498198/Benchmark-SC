package com.cryptobench.benchmark.common;

import org.springframework.http.HttpStatus;

/**
 * 可预期的业务异常，携带 HTTP 状态和可向用户展示的消息，由统一异常处理器转换。
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    /**
     * 保存业务失败对应的状态码与提示，例如参数无效、未登录或记录冲突。
     */
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 提供响应状态，供 ApiExceptionHandler 构造统一错误响应。
     */
    public HttpStatus getStatus() {
        return status;
    }
}

package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.common.ApiException;
import org.springframework.http.HttpStatus;

/** Expected reset failures can commit attempt counters without exposing account information. */
public class PasswordResetFlowException extends ApiException {
    private final String code;
    private final long retryAfterSeconds;
    public PasswordResetFlowException(HttpStatus status, String code, String message) { this(status, code, message, 0); }
    public PasswordResetFlowException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(status, message); this.code = code; this.retryAfterSeconds = retryAfterSeconds;
    }
    public String code() { return code; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}

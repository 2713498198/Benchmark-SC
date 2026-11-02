package com.cryptobench.benchmark.auth.dto;

import java.util.LinkedHashMap;

/**
 * Preserve strict string checks while preventing Spring's request-body debug logging
 * from formatting passwords, reset links or six-digit codes through Map.toString().
 */
public final class PasswordResetRequest extends LinkedHashMap<String, Object> {
    @Override public String toString() { return "PasswordResetRequest[REDACTED]"; }
}

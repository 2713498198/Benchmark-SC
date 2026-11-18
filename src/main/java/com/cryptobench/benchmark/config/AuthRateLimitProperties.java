package com.cryptobench.benchmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Persistent rolling-window limits for login and registration. Changing these
 * values never clears already-recorded attempts.
 */
@ConfigurationProperties(prefix = "benchmark.auth-rate-limit")
public record AuthRateLimitProperties(
        int loginMaxAttempts, long loginWindowSeconds,
        int registerMaxAttempts, long registerWindowSeconds) {
    public AuthRateLimitProperties {
        if (loginMaxAttempts < 1 || registerMaxAttempts < 1) throw new IllegalArgumentException("Auth rate limits must be positive");
        if (loginWindowSeconds < 1 || registerWindowSeconds < 1) throw new IllegalArgumentException("Auth rate windows must be positive");
    }
}

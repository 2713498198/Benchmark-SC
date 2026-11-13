package com.cryptobench.benchmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-private controls for the optional V4 workspace-validator scorer. */
@ConfigurationProperties(prefix = "benchmark.v4")
public record V4ScoringProperties(String suiteRoot, String python, long validatorTimeoutSeconds) {
    public String pythonOrDefault() {
        return python == null || python.isBlank() ? "python3" : python.trim();
    }

    public long timeoutSecondsOrDefault() {
        return validatorTimeoutSeconds <= 0 ? 120 : validatorTimeoutSeconds;
    }

    public boolean enabled() {
        return suiteRoot != null && !suiteRoot.isBlank();
    }
}

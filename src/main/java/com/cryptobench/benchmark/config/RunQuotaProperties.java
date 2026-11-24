package com.cryptobench.benchmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Server-side limits that prevent one account or the public queue from exhausting capacity. */
@ConfigurationProperties(prefix = "benchmark.run-quota")
public record RunQuotaProperties(int maxActivePerUser, int maxCreatedPerDayPerUser, int maxActiveGlobal) {
    public RunQuotaProperties {
        if (maxActivePerUser < 1 || maxCreatedPerDayPerUser < 1 || maxActiveGlobal < 1) {
            throw new IllegalArgumentException("Run quota values must be positive");
        }
    }
}

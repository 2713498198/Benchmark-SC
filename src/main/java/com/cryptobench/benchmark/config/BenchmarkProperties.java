package com.cryptobench.benchmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "benchmark")
public record BenchmarkProperties(boolean demoEnabled, boolean secureCookie,
                                  boolean legacyImportEnabled, String legacyDbFile) {
}

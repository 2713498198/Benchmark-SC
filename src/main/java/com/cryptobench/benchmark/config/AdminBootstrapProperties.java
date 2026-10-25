package com.cryptobench.benchmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit one-time administrator bootstrap credentials. */
@ConfigurationProperties(prefix = "benchmark.admin.bootstrap")
public record AdminBootstrapProperties(String email, String name, String password) {
}

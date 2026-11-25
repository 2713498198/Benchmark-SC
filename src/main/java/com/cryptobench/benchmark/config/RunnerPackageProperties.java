package com.cryptobench.benchmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Location of the release-built portable Runner archive and manifest. */
@ConfigurationProperties(prefix = "benchmark.runner-package")
public record RunnerPackageProperties(String directory) {
}

package com.cryptobench.benchmark.execution.model;

public record RunLog(String runId, long sequence, String level, String message, String createdAt) {
}

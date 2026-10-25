package com.cryptobench.benchmark.execution.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record RunTask(String runId, String taskId, int ordinal, String status, int attempt,
                      ObjectNode agentResponse, ObjectNode verificationResult, Boolean passed,
                      Double score, Double costUsd, Long elapsedMs, String startedAt, String finishedAt) {
}

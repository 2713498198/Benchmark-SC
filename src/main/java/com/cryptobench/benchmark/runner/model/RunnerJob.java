package com.cryptobench.benchmark.runner.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Durable task exchanged between the website worker and a local Runner. */
public record RunnerJob(String id, String runnerId, String ownerId, String runId,
                        long generation, String taskId, int attempt, String status,
                        ObjectNode request, ObjectNode result, String errorCode,
                        String errorMessage, long createdAt, Long leaseUntil, Long finishedAt) {
}

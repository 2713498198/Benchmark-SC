package com.cryptobench.benchmark.execution.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 持久化的服务端运行记录。运行完成后不可覆盖，只能创建新的 retry 运行。 */
public record Run(String id, String ownerId, String kind, String source, String datasetId, String version,
                  String budget, String scope, String model, String agentVersion, long seed, String status,
                  int totalTasks, int completedTasks, int passedTasks, int failedTasks, ObjectNode config,
                  String errorCode, String errorMessage, String reportId, String retryOf, boolean cancelRequested,
                  String createdAt, String startedAt, String finishedAt) {
}

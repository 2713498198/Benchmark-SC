package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Server-owned binding for one immutable dataset version. Hidden answers and
 * verification rules stay behind this interface and are never exposed as
 * static frontend data.
 */
public interface BenchmarkEvaluator {
    String datasetId();

    String version();

    String evaluatorVersion();

    /** Classpath resource containing the private execution task catalog. */
    String taskResource();

    ObjectNode evaluate(TaskDefinition task, ObjectNode response);
}

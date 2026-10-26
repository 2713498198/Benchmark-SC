package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 读取服务端私有的可执行任务；该资源不位于 static 目录，不会被浏览器直接下载。 */
@Service
public class TaskCatalogService {
    private final ObjectMapper mapper;
    private final Map<DatasetVersion, BenchmarkEvaluator> evaluators;

    public TaskCatalogService(ObjectMapper mapper, List<BenchmarkEvaluator> evaluatorList) {
        this.mapper = mapper;
        this.evaluators = evaluatorList.stream().collect(Collectors.toUnmodifiableMap(
                evaluator -> new DatasetVersion(evaluator.datasetId(), evaluator.version()),
                Function.identity()));
    }

    public List<TaskDefinition> tasks(String datasetId, String version) {
        BenchmarkEvaluator evaluator = evaluator(datasetId, version);
        try (InputStream input = getClass().getResourceAsStream(evaluator.taskResource())) {
            if (input == null) {
                throw new IllegalStateException("Execution catalog is missing for " + datasetId + "@" + version);
            }
            JsonNode root = mapper.readTree(input);
            List<TaskDefinition> result = new ArrayList<>();
            for (JsonNode task : root.path("tasks")) {
                List<String> files = new ArrayList<>();
                task.path("publicFiles").forEach(node -> files.add(node.asText()));
                List<String> requiredTools = new ArrayList<>();
                task.path("requiredTools").forEach(node -> requiredTools.add(node.asText()));
                List<String> requiredCapabilities = new ArrayList<>();
                task.path("requiredCapabilities").forEach(node -> requiredCapabilities.add(node.asText()));
                result.add(new TaskDefinition(task.path("id").asText(), task.path("question").asText(),
                        task.path("expectedAnswer").asText(), files, requiredTools, requiredCapabilities));
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read execution catalog", exception);
        }
    }

    public String evaluatorVersion(String datasetId, String version) {
        return evaluator(datasetId, version).evaluatorVersion();
    }

    public ObjectNode evaluate(String datasetId, String version, TaskDefinition task, ObjectNode response) {
        return evaluator(datasetId, version).evaluate(task, response);
    }

    private BenchmarkEvaluator evaluator(String datasetId, String version) {
        BenchmarkEvaluator evaluator = evaluators.get(new DatasetVersion(datasetId, version));
        if (evaluator == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该测评集版本尚未绑定服务端验证器");
        }
        return evaluator;
    }

    private record DatasetVersion(String datasetId, String version) {}
}

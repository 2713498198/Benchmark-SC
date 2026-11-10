package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.dataset.repository.DatasetRepository;
import com.cryptobench.benchmark.dataset.service.CustomDatasetRules;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Resolves immutable built-in catalogs or validated database packages; metadata alone is never executable. */
@Service
public class TaskCatalogService {
    private final ObjectMapper mapper;
    private final DatasetRepository datasets;
    private final Map<DatasetVersion, BenchmarkEvaluator> evaluators;

    public TaskCatalogService(ObjectMapper mapper, List<BenchmarkEvaluator> evaluatorList, DatasetRepository datasets) {
        this.mapper = mapper;
        this.datasets = datasets;
        this.evaluators = evaluatorList.stream().collect(Collectors.toUnmodifiableMap(
                evaluator -> new DatasetVersion(evaluator.datasetId(), evaluator.version()), Function.identity()));
    }

    public record ExecutionCatalog(List<TaskDefinition> tasks, String evaluatorVersion, String catalogSha256) {
        public ExecutionCatalog { tasks = List.copyOf(tasks); }
    }

    @PostConstruct
    void validateRegistrations() {
        evaluators.forEach((key, evaluator) -> {
            if (evaluator.evaluatorVersion() == null || evaluator.evaluatorVersion().isBlank()) {
                throw new IllegalStateException("Evaluator version is missing for " + key);
            }
            String resource = evaluator.taskResource();
            if (resource == null || !resource.startsWith("/evaluation/") || resource.contains("/static/")) {
                throw new IllegalStateException("Evaluator catalog must be a private evaluation resource for " + key);
            }
            List<TaskDefinition> registered = snapshot(key.datasetId(), key.version()).tasks();
            if (registered.isEmpty()) throw new IllegalStateException("Evaluator catalog is empty for " + key);
            Set<String> ids = new HashSet<>();
            for (TaskDefinition task : registered) {
                if (task.id() == null || task.id().isBlank() || !ids.add(task.id())) throw new IllegalStateException("Duplicate or blank task ID for " + key);
                if (task.question() == null || task.question().isBlank()) throw new IllegalStateException("Blank question for " + key + " / " + task.id());
            }
        });
    }

    public ExecutionCatalog snapshot(String datasetId, String version) {
        BenchmarkEvaluator evaluator = evaluators.get(new DatasetVersion(datasetId, version));
        JsonNode root;
        String evaluatorVersion;
        boolean custom = evaluator == null;
        if (evaluator != null && !evaluator.executionReady()) throw unbound();
        if (custom) {
            ObjectNode summary = datasets.findPublic(datasetId, version).orElseThrow(this::unbound);
            if (!summary.path("executionReady").asBoolean() || !CustomDatasetRules.SCHEMA.equals(summary.path("schema").asText())) throw unbound();
            root = datasets.findPrivate(datasetId, version).orElseThrow(this::unbound);
            evaluatorVersion = summary.path("evaluatorVersion").asText();
            if (!CustomDatasetRules.EVALUATOR.equals(evaluatorVersion) || !CustomDatasetRules.publicHash((ObjectNode) root, true).equals(summary.path("catalogSha256").asText())) {
                throw new ApiException(HttpStatus.CONFLICT, "测评集评分器或目录校验不一致，无法执行");
            }
        } else {
            try (InputStream input = getClass().getResourceAsStream(evaluator.taskResource())) {
                if (input == null) throw new IllegalStateException("Execution catalog missing for " + datasetId + "@" + version);
                root = mapper.readTree(input);
            } catch (IOException exception) { throw new IllegalStateException("Cannot read execution catalog", exception); }
            evaluatorVersion = evaluator.evaluatorVersion();
        }
        List<TaskDefinition> result = new ArrayList<>();
        for (JsonNode task : root.path("tasks")) {
            result.add(new TaskDefinition(task.path("id").asText(), task.path("question").asText(),
                    custom ? null : task.path("expectedAnswer").asText(), strings(task.path("publicFiles")),
                    strings(task.path("requiredTools")), strings(task.path("requiredCapabilities")),
                    task.path("input").isObject() ? task.path("input") : mapper.createObjectNode(),
                    task.path("category").asText(""), task.path("verification")));
        }
        return new ExecutionCatalog(result, evaluatorVersion, custom ? CustomDatasetRules.publicHash((ObjectNode) root, true)
                : CustomDatasetRules.builtInPublicHash(root, datasetId, version, evaluatorVersion));
    }

    public List<TaskDefinition> tasks(String datasetId, String version) { return snapshot(datasetId, version).tasks(); }
    public String evaluatorVersion(String datasetId, String version) { return snapshot(datasetId, version).evaluatorVersion(); }
    public String catalogSha256(String datasetId, String version) { return snapshot(datasetId, version).catalogSha256(); }

    public ObjectNode evaluate(String datasetId, String version, TaskDefinition task, ObjectNode response) {
        BenchmarkEvaluator evaluator = evaluators.get(new DatasetVersion(datasetId, version));
        if (evaluator != null) return evaluator.evaluate(task, response);
        // The task was loaded from an immutable private package when this run was created.
        if (!task.verification().has("type")) throw unbound();
        return DeclarativeTaskEvaluator.evaluate(task, response);
    }

    private List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) values.forEach(value -> result.add(value.asText()));
        return result;
    }
    private ApiException unbound() { return new ApiException(HttpStatus.BAD_REQUEST, "该测评集版本尚未绑定服务端验证器"); }
    private record DatasetVersion(String datasetId, String version) {}
}

package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 读取服务端私有的可执行任务；该资源不位于 static 目录，不会被浏览器直接下载。 */
@Service
public class TaskCatalogService {
    private final ObjectMapper mapper;

    public TaskCatalogService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<TaskDefinition> tasks(String datasetId, String version) {
        if (!"mini-spn".equals(datasetId) || !"1.0.0".equals(version)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "该测评集版本尚未绑定服务端验证器");
        }
        try (InputStream input = getClass().getResourceAsStream("/evaluation/mini-spn/1.0.0/tasks.json")) {
            if (input == null) {
                throw new IllegalStateException("Mini-SPN execution catalog is missing");
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
        if ("mini-spn".equals(datasetId) && "1.0.0".equals(version)) {
            return "mini-spn-evaluator-1.0.0";
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "该测评集版本尚未绑定服务端验证器");
    }
}

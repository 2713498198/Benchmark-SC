package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.config.V4ScoringProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the benchmark-side Python task validator against a materialized agent
 * workspace. The agent's submitted text and files are written to a temporary
 * run directory; the validator is trusted benchmark code and only reads that
 * directory plus the server-private suite root. The runner never executes the
 * agent's own code.
 */
@Service
public class V4ValidatorRunner {
    private static final Logger log = LoggerFactory.getLogger(V4ValidatorRunner.class);

    private final V4ScoringProperties properties;
    private final ObjectMapper mapper;

    public V4ValidatorRunner(V4ScoringProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public boolean available() {
        return properties.enabled();
    }

    public ObjectNode evaluate(String taskId, JsonNode artifact) {
        ObjectNode result = mapper.createObjectNode();
        if (!properties.enabled()) {
            result.put("status", "failed");
            result.put("passed", false);
            result.put("score", 0);
            result.put("reason", "该题需服务端工作区验证器，但评分通道未启用");
            return result;
        }

        Path runDir = null;
        try {
            runDir = Files.createTempDirectory("cryptobench-v4-run-");
            Path workspace = Files.createDirectories(runDir.resolve("workspace"));
            Files.writeString(runDir.resolve("final_answer.md"),
                    artifact == null ? "" : text(artifact.get("answer")), StandardCharsets.UTF_8);
            writeWorkspaceFiles(workspace, artifact);

            Path out = runDir.resolve("validator_report.json");
            List<String> command = new ArrayList<>(List.of(
                    properties.pythonOrDefault(),
                    Path.of(properties.suiteRoot(), "scripts", "run_task_validator.py").toString(),
                    "--suite-root", Path.of(properties.suiteRoot()).toString(),
                    "--task-id", taskId,
                    "--run-dir", runDir.toString(),
                    "--out", out.toString()));
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            boolean finished = process.waitFor(properties.timeoutSecondsOrDefault(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.put("status", "failed");
                result.put("passed", false);
                result.put("score", 0);
                result.put("reason", "工作区验证器超时");
                return result;
            }
            int exit = process.exitValue();
            JsonNode report = Files.exists(out) ? mapper.readTree(Files.readString(out, StandardCharsets.UTF_8)) : null;
            if (report == null || exit != 0) {
                result.put("status", "failed");
                result.put("passed", false);
                result.put("score", 0);
                result.put("reason", "工作区验证器执行失败");
                return result;
            }
            String status = report.path("status").asText("failed");
            boolean passed = "passed".equals(status);
            result.put("status", passed ? "passed" : "failed");
            result.put("passed", passed);
            result.put("score", passed ? report.path("final_score").asDouble(1.0) : 0);
            if (!passed) result.put("reason", "提交未通过该题工作区验证器");
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result.put("status", "failed");
            result.put("passed", false);
            result.put("score", 0);
            result.put("reason", "工作区验证器被中断");
            return result;
        } catch (IOException exception) {
            log.warn("V4 workspace validator failed for {}", taskId, exception);
            result.put("status", "failed");
            result.put("passed", false);
            result.put("score", 0);
            result.put("reason", "工作区验证器执行异常");
            return result;
        } finally {
            deleteRecursively(runDir);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        return node.toString();
    }

    private static void writeWorkspaceFiles(Path workspace, JsonNode artifact) throws IOException {
        JsonNode files = artifact == null ? null : artifact.get("files");
        if (files == null || !files.isArray()) return;
        for (JsonNode file : files) {
            String name = file.path("path").asText("");
            if (name.isBlank() || name.contains("..") || name.startsWith("/") || name.startsWith("\\")) continue;
            Path target = workspace.resolve(name).normalize();
            if (!target.startsWith(workspace.normalize())) continue;
            Files.createDirectories(target.getParent());
            Files.writeString(target, text(file.get("content")), StandardCharsets.UTF_8);
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(item -> {
                try { Files.deleteIfExists(item); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}

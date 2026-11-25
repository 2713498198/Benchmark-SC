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
        if (!properties.enabled()) return false;
        Path scripts = Path.of(properties.suiteRoot(), "scripts");
        return Files.isRegularFile(scripts.resolve("grade_submission.py"))
                || Files.isRegularFile(scripts.resolve("run_task_validator.py"));
    }

    public ObjectNode evaluate(String taskId, JsonNode response) {
        return evaluate(taskId, response, properties.timeoutSecondsOrDefault());
    }

    public ObjectNode evaluate(String taskId, JsonNode response, long timeoutSeconds) {
        ObjectNode result = mapper.createObjectNode();
        if (!available()) {
            result.put("status", "failed");
            result.put("passed", false);
            result.put("score", 0);
            result.put("reason", "该题需服务端工作区验证器，但评分通道未启用或私有套件不完整");
            return result;
        }

        Path workspaceRoot = null;
        try {
            workspaceRoot = Files.createTempDirectory("cryptobench-v4-run-");
            Path runDir = Files.createDirectories(workspaceRoot.resolve("run"));
            Path workspace = Files.createDirectories(runDir.resolve("workspace"));
            JsonNode artifact = response != null && response.isObject() && response.has("artifact")
                    ? response.get("artifact") : response;
            Files.writeString(runDir.resolve("final_answer.md"),
                    artifact == null ? "" : text(artifact.get("answer")), StandardCharsets.UTF_8);
            writeWorkspaceFiles(runDir, workspace, artifact);
            writeEvidence(runDir, response != null && response.isObject()
                    ? response.get("evidence") : null);

            Path suiteRoot = Path.of(properties.suiteRoot());
            Path gradeEntry = suiteRoot.resolve("scripts").resolve("grade_submission.py");
            boolean releaseJudge = Files.isRegularFile(gradeEntry);
            Path out = workspaceRoot.resolve("validator_report.json");
            String entry = releaseJudge
                    ? gradeEntry.toString()
                    : suiteRoot.resolve("scripts").resolve("run_task_validator.py").toString();
            List<String> command = new ArrayList<>(List.of(
                    properties.pythonOrDefault(), "-B", entry,
                    "--suite-root", suiteRoot.toString(),
                    "--task-id", taskId,
                    "--run-dir", runDir.toString(),
                    "--out", out.toString()));
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
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
            if (report == null || (exit != 0 && !"validator_error".equals(report.path("status").asText("")))) {
                result.put("status", "failed");
                result.put("passed", false);
                result.put("score", 0);
                result.put("reason", "工作区验证器执行失败");
                return result;
            }
            double finalScore = report.path("final_score").asDouble(0.0);
            String releaseStatus = report.path("release_check_status").asText("");
            boolean passed = releaseStatus.isBlank()
                    ? finalScore >= 0.9999
                    : "passed".equals(releaseStatus);
            String reportedStatus = report.path("status").asText("");
            String status = "validator_error".equals(reportedStatus)
                    ? "validator_error" : passed ? "passed" : "failed";
            result.put("status", status);
            result.put("passed", passed);
            result.put("score", finalScore);
            if (report.has("completion_status")) {
                result.put("completionStatus", report.path("completion_status").asText());
            }
            if (report.has("completion_metric_eligible")) {
                result.put("completionMetricEligible", report.path("completion_metric_eligible").asBoolean());
            }
            if (!releaseStatus.isBlank()) {
                result.put("releaseCheckStatus", releaseStatus);
                result.put("releaseCheckScope", report.path("release_check_scope").asText(""));
            }
            if (!passed) result.put("reason", "validator_error".equals(status)
                    ? "工作区验证器执行失败"
                    : "提交未通过该题工作区验证器");
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
            deleteRecursively(workspaceRoot);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        return node.toString();
    }

    private static void writeWorkspaceFiles(Path runDir, Path workspace, JsonNode artifact) throws IOException {
        JsonNode files = artifact == null ? null : artifact.get("files");
        if (files == null || !files.isArray()) return;
        for (JsonNode file : files) {
            String name = file.path("path").asText("");
            if (name.isBlank() || name.contains("..") || name.startsWith("/") || name.startsWith("\\")) continue;
            Path target = workspace.resolve(name).normalize();
            if (!target.startsWith(workspace.normalize())) continue;
            Files.createDirectories(target.getParent());
            String content = text(file.get("content"));
            Files.writeString(target, content, StandardCharsets.UTF_8);
            // Some benchmark validators read their original run harness paths.
            // Mirror log-like artifacts outside workspace without changing the
            // Agent-provided workspace view.
            if (isHarnessPath(name)) {
                Path mirror = runDir.resolve(name).normalize();
                if (mirror.startsWith(runDir.normalize()) && !mirror.equals(target)) {
                    Files.createDirectories(mirror.getParent());
                    Files.writeString(mirror, content, StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static void writeEvidence(Path runDir, JsonNode evidence) throws IOException {
        if (evidence == null || !evidence.isArray() || evidence.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        for (JsonNode item : evidence) {
            String content = item.isObject() && item.has("content")
                    ? text(item.get("content"))
                    : item.toString();
            if (!content.isBlank()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(content);
            }
        }
        if (text.isEmpty()) return;
        Path logs = Files.createDirectories(runDir.resolve("logs"));
        Path output = logs.resolve("agent_stdout.txt");
        if (Files.exists(output) && Files.size(output) > 0) {
            Files.writeString(output, System.lineSeparator() + text, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        } else {
            Files.writeString(output, text, StandardCharsets.UTF_8);
        }
    }

    private static boolean isHarnessPath(String name) {
        String normalized = name.replace('\\', '/');
        return normalized.startsWith("logs/")
                || normalized.equals("events.jsonl")
                || normalized.equals("trajectory.jsonl");
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

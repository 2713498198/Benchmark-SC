package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.dataset.service.CustomDatasetRules;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Server-owned binding for CryptoBench-SC V4.1.
 *
 * The public site exposes questions and attachments only. This catalog keeps
 * each task's authoritative expected answer as a deterministic rule. Tasks
 * whose upstream scoring depends on workspace replay, solver runs, or LLM
 * semantic extraction are marked {@code dynamic} and fail closed; the suite
 * stays {@code executionReady=false} until every task has a deterministic rule.
 */
@Component
public class CryptobenchV4Evaluator implements BenchmarkEvaluator {
    private final ObjectMapper mapper;

    public CryptobenchV4Evaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String datasetId() { return "cryptobench-sc-v4"; }

    @Override
    public String version() { return "4.1.0"; }

    @Override
    public String evaluatorVersion() { return "cryptobench-sc-v4-evaluator-1.0.0"; }

    @Override
    public String taskResource() { return "/evaluation/cryptobench-sc-v4/4.1.0/tasks.json"; }

    @Override
    public boolean executionReady() { return false; }

    @Override
    public ObjectNode evaluate(TaskDefinition task, ObjectNode response) {
        ObjectNode result = mapper.createObjectNode();
        result.put("evaluatorVersion", evaluatorVersion());
        String status = response == null ? "" : response.path("status").asText("");
        JsonNode actual = response == null ? null : response.path("artifact").get("answer");
        if (!"completed".equals(status) || actual == null || actual.isNull()) {
            result.put("status", "invalid");
            result.put("passed", false);
            result.put("score", 0);
            result.put("reason", "Agent 鏈彁渚?completed 鐘舵€佸拰 artifact.answer");
            return result;
        }

        JsonNode rule = task.verification();
        String type = rule.path("type").asText("");
        boolean passed = switch (type) {
            case "numeric" -> numericMatch(rule, actual);
            case "string" -> stringMatch(rule, actual);
            case "one-of" -> oneOfMatch(rule, actual);
            case "boolean" -> actual.isBoolean() && actual.asBoolean() == rule.path("expected").asBoolean();
            case "exact-json" -> CustomDatasetRules.canonical(rule.get("expected"))
                    .equals(CustomDatasetRules.canonical(actual));
            default -> false;
        };
        result.put("status", passed ? "passed" : "failed");
        result.put("passed", passed);
        result.put("score", passed ? 1 : 0);
        if (!passed) result.put("reason", "dynamic".equals(type)
                ? "该题暂无可确定性校验的评分规则，已按未通过处理"
                : "提交结果未满足服务端评分规则");
        return result;
    }

    private boolean numericMatch(JsonNode rule, JsonNode actual) {
        if (!actual.isNumber() || !Double.isFinite(actual.asDouble())) return false;
        BigDecimal target = rule.path("expected").decimalValue();
        BigDecimal absolute = rule.path("absoluteTolerance").decimalValue();
        BigDecimal relative = rule.path("relativeTolerance").decimalValue().multiply(target.abs());
        return actual.decimalValue().subtract(target).abs().compareTo(absolute.max(relative)) <= 0;
    }

    private boolean stringMatch(JsonNode rule, JsonNode actual) {
        if (!actual.isTextual()) return false;
        String expected = normalize(rule.path("expected").asText(""));
        String submitted = normalize(actual.asText());
        if (rule.path("ignoreCase").asBoolean(false)) {
            expected = expected.toLowerCase(Locale.ROOT);
            submitted = submitted.toLowerCase(Locale.ROOT);
        }
        if (expected.equals(submitted)) return true;
        Double expectedWeight = probabilityWeight(expected);
        Double submittedWeight = probabilityWeight(submitted);
        return expectedWeight != null && submittedWeight != null
                && Math.abs(expectedWeight - submittedWeight) < 1e-9;
    }

    private boolean oneOfMatch(JsonNode rule, JsonNode actual) {
        if (!actual.isTextual()) return false;
        String submitted = normalize(actual.asText());
        if (rule.path("ignoreCase").asBoolean(true)) submitted = submitted.toLowerCase(Locale.ROOT);
        for (JsonNode option : rule.path("options")) {
            String expected = normalize(option.asText(""));
            if (rule.path("ignoreCase").asBoolean(true)) expected = expected.toLowerCase(Locale.ROOT);
            if (expected.equals(submitted)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    /**
     * Parse a probability written as a power of two (2^-41, 2^(-41), 2^{-41},
     * 1/2^41) into its weight, so equivalent notations compare equal.
     */
    private static Double probabilityWeight(String value) {
        String cleaned = value == null ? "" : value.replace("(", "").replace(")", "").replace("{", "").replace("}", "");
        java.util.regex.Matcher power = java.util.regex.Pattern
                .compile("2\\s*\\^\\s*(-\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(cleaned);
        if (power.matches()) return Math.abs(Double.parseDouble(power.group(1)));
        java.util.regex.Matcher reciprocal = java.util.regex.Pattern
                .compile("1\\s*/\\s*2\\s*\\^\\s*(\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(cleaned);
        if (reciprocal.matches()) return Double.parseDouble(reciprocal.group(1));
        return null;
    }
}

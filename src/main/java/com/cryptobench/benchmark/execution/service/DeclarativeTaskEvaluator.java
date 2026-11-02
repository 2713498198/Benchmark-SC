package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.dataset.service.CustomDatasetRules;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;

/** Finite declarative rules only; uploaded packages never supply executable code. */
public final class DeclarativeTaskEvaluator {
    private DeclarativeTaskEvaluator() {}

    public static ObjectNode evaluate(TaskDefinition task, ObjectNode response) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("evaluatorVersion", CustomDatasetRules.EVALUATOR);
        boolean completed = response != null && "completed".equals(response.path("status").asText());
        JsonNode actual = response == null ? null : response.path("artifact").get("answer");
        boolean passed = false;
        JsonNode rule = task.verification();
        if (completed && CustomDatasetRules.supportedJson(actual) && rule.has("expected")) {
            JsonNode expected = rule.get("expected");
            if ("exact-json-v1".equals(rule.path("type").asText())) {
                passed = CustomDatasetRules.canonical(expected).equals(CustomDatasetRules.canonical(actual));
            } else if ("numeric-v1".equals(rule.path("type").asText()) && actual.isNumber() && Double.isFinite(actual.asDouble())) {
                BigDecimal target = expected.decimalValue();
                BigDecimal absolute = rule.path("absoluteTolerance").decimalValue();
                BigDecimal relative = rule.path("relativeTolerance").decimalValue().multiply(target.abs());
                passed = actual.decimalValue().subtract(target).abs().compareTo(absolute.max(relative)) <= 0;
            }
        }
        result.put("status", !completed || actual == null ? "invalid" : passed ? "passed" : "failed");
        result.put("passed", passed);
        result.put("score", passed ? 1 : 0);
        if (!passed) result.put("reason", completed && actual != null ? "提交结果未满足服务端评分规则" : "Agent 未提供 completed 状态和 artifact.answer");
        return result;
    }
}

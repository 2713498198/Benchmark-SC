package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class MiniSpnEvaluator {
    private final ObjectMapper mapper;

    public MiniSpnEvaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode evaluate(TaskDefinition task, ObjectNode response) {
        ObjectNode result = mapper.createObjectNode();
        result.put("evaluatorVersion", "mini-spn-evaluator-1.0.0");
        String status = response.path("status").asText("");
        if (!"completed".equals(status)) {
            result.put("status", status.isBlank() ? "invalid" : status);
            result.put("passed", false);
            result.put("score", 0);
            result.put("reason", "Agent 未返回 completed 结果");
            return result;
        }
        String answer = response.path("artifact").path("answer").asText("").trim();
        boolean passed = task.expectedAnswer().equals(answer);
        result.put("status", passed ? "passed" : "failed");
        result.put("passed", passed);
        result.put("score", passed ? 1 : 0);
        if (!passed) result.put("reason", "artifact.answer 与服务端验证答案不匹配");
        result.set("evidence", response.path("evidence").deepCopy());
        return result;
    }
}

package com.cryptobench.benchmark;

import com.cryptobench.benchmark.config.V4ScoringProperties;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.cryptobench.benchmark.execution.service.CryptobenchV4Evaluator;
import com.cryptobench.benchmark.execution.service.V4ValidatorRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class V4DeterministicRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CryptobenchV4Evaluator evaluator = new CryptobenchV4Evaluator(
            mapper,
            new V4ValidatorRunner(new V4ScoringProperties("", "python3", 0, ""), mapper));

    @Test
    void everyDeterministicRuleAcceptsItsAuthoritativeAnswer() throws Exception {
        JsonNode catalog = mapper.readTree(getClass().getResourceAsStream(
                "/evaluation/cryptobench-sc-v4/4.1.0/tasks.json"));
        int count = 0;
        for (JsonNode node : catalog.path("tasks")) {
            TaskDefinition task = task(node);
            if ("workspace-validator".equals(task.verification().path("type").asText())) continue;
            ObjectNode response = response(expectedAnswer(task.verification()));
            ObjectNode result = evaluator.evaluate(task, response);
            assertThat(result.path("passed").asBoolean())
                    .as(task.id() + " / " + task.verification().path("type").asText())
                    .isTrue();
            assertThat(result.path("score").asDouble()).isEqualTo(1.0);
            count++;
        }
        assertThat(count).isEqualTo(105);
    }

    @Test
    void everyDeterministicRuleRejectsAnObviousWrongAnswer() throws Exception {
        JsonNode catalog = mapper.readTree(getClass().getResourceAsStream(
                "/evaluation/cryptobench-sc-v4/4.1.0/tasks.json"));
        for (JsonNode node : catalog.path("tasks")) {
            TaskDefinition task = task(node);
            JsonNode rule = task.verification();
            if ("workspace-validator".equals(rule.path("type").asText())) continue;
            ObjectNode response = response(wrongAnswer(rule));
            assertThat(evaluator.evaluate(task, response).path("passed").asBoolean())
                    .as(task.id() + " must reject a wrong answer")
                    .isFalse();
        }
    }

    private TaskDefinition task(JsonNode node) {
        return new TaskDefinition(
                node.path("id").asText(),
                node.path("question").asText(),
                node.path("expectedAnswer").asText(),
                mapper.convertValue(node.path("publicFiles"),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                List.of(), List.of(), node.path("input"),
                node.path("category").asText(), node.path("verification"));
    }

    private ObjectNode response(JsonNode answer) {
        ObjectNode response = mapper.createObjectNode().put("status", "completed");
        response.putObject("artifact").set("answer", answer);
        return response;
    }

    private JsonNode expectedAnswer(JsonNode rule) {
        return switch (rule.path("type").asText()) {
            case "numeric", "string", "boolean" -> rule.get("expected");
            case "fields" -> rule.get("fields");
            case "piecewise" -> mapper.createObjectNode().set("cases", rule.get("cases"));
            default -> throw new IllegalStateException("Unexpected rule: " + rule);
        };
    }

    private JsonNode wrongAnswer(JsonNode rule) {
        return switch (rule.path("type").asText()) {
            case "numeric" -> mapper.getNodeFactory().numberNode(
                    rule.path("expected").asDouble()
                            + Math.max(1.0, rule.path("absoluteTolerance").asDouble() + 1.0));
            case "string" -> mapper.getNodeFactory().textNode(rule.path("expected").asText() + "-wrong");
            case "boolean" -> mapper.getNodeFactory().booleanNode(!rule.path("expected").asBoolean());
            case "fields" -> {
                ObjectNode copy = ((ObjectNode) rule.get("fields")).deepCopy();
                if (!copy.isEmpty()) copy.remove(copy.fieldNames().next());
                yield copy;
            }
            case "piecewise" -> mapper.createArrayNode();
            default -> throw new IllegalStateException("Unexpected rule: " + rule);
        };
    }
}

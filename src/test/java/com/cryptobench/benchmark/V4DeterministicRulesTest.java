package com.cryptobench.benchmark;

import com.cryptobench.benchmark.config.V4ScoringProperties;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.cryptobench.benchmark.execution.service.CryptobenchV4Evaluator;
import com.cryptobench.benchmark.execution.service.V4ValidatorRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class V4DeterministicRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CryptobenchV4Evaluator evaluator = new CryptobenchV4Evaluator(
            mapper,
            new V4ValidatorRunner(new V4ScoringProperties("", "python3", 0, ""), mapper));

    @Test
    void everyTaskUsesTheServerPrivateJudgeAndExposesNoAnswer() throws Exception {
        JsonNode catalog = mapper.readTree(getClass().getResourceAsStream(
                "/evaluation/cryptobench-sc-v4/4.2.0/tasks.json"));
        int count = 0;
        Map<String, Long> routes;
        for (JsonNode node : catalog.path("tasks")) {
            TaskDefinition task = task(node);
            assertThat(task.verification().path("type").asText()).isEqualTo("workspace-validator");
            assertThat(task.expectedAnswer()).isEmpty();
            count++;
        }
        routes = catalog.path("tasks").findValuesAsText("judgeRoute").stream()
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        assertThat(count).isEqualTo(170);
        assertThat(routes).containsEntry("generic", 103L).containsEntry("task_specific", 67L);
    }

    @Test
    void missingPrivateSuiteFailsClosedForEveryTask() throws Exception {
        JsonNode catalog = mapper.readTree(getClass().getResourceAsStream(
                "/evaluation/cryptobench-sc-v4/4.2.0/tasks.json"));
        for (JsonNode node : catalog.path("tasks")) {
            TaskDefinition task = task(node);
            ObjectNode result = evaluator.evaluate(task, response("anything"));
            assertThat(result.path("passed").asBoolean()).as(task.id()).isFalse();
            assertThat(result.path("reason").asText()).contains("未启用");
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

    private ObjectNode response(String answer) {
        ObjectNode response = mapper.createObjectNode().put("status", "completed");
        response.putObject("artifact").put("answer", answer);
        return response;
    }
}

package com.cryptobench.benchmark;

import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.cryptobench.benchmark.execution.service.CryptobenchV4Evaluator;
import com.cryptobench.benchmark.execution.service.V4ValidatorRunner;
import com.cryptobench.benchmark.config.V4ScoringProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CryptobenchV4EvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final V4ValidatorRunner validatorRunner =
            new V4ValidatorRunner(new V4ScoringProperties("", "python3", 0), mapper);
    private final CryptobenchV4Evaluator evaluator = new CryptobenchV4Evaluator(mapper, validatorRunner);

    private ObjectNode answer(Object value) {
        ObjectNode response = mapper.createObjectNode();
        response.put("status", "completed");
        ObjectNode artifact = response.putObject("artifact");
        if (value instanceof Number number) artifact.put("answer", number.doubleValue());
        else if (value instanceof Boolean bool) artifact.put("answer", bool);
        else artifact.put("answer", String.valueOf(value));
        return response;
    }

    private TaskDefinition task(String type, String verificationJson) {
        try {
            ObjectNode verification = (ObjectNode) mapper.readTree(verificationJson);
            return new TaskDefinition("001", "question", "", List.of(), List.of(), List.of(),
                    mapper.createObjectNode(), "", verification);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void numericRuleHonorsTolerance() {
        TaskDefinition task = task("numeric", "{\"type\":\"numeric\",\"expected\":25,\"absoluteTolerance\":0,\"relativeTolerance\":0}");
        assertThat(evaluator.evaluate(task, answer(25)).path("passed").asBoolean()).isTrue();
        assertThat(evaluator.evaluate(task, answer(26)).path("passed").asBoolean()).isFalse();

        TaskDefinition tolerant = task("numeric", "{\"type\":\"numeric\",\"expected\":34.78,\"absoluteTolerance\":0.08,\"relativeTolerance\":0}");
        assertThat(evaluator.evaluate(tolerant, answer(34.80)).path("passed").asBoolean()).isTrue();
        assertThat(evaluator.evaluate(tolerant, answer(35.0)).path("passed").asBoolean()).isFalse();
    }

    @Test
    void stringRuleIsNormalizedButExact() {
        TaskDefinition task = task("string", "{\"type\":\"string\",\"expected\":\"2^-41\",\"ignoreCase\":true}");
        assertThat(evaluator.evaluate(task, answer("2^-41")).path("passed").asBoolean()).isTrue();
        assertThat(evaluator.evaluate(task, answer("2 ^ -41")).path("passed").asBoolean()).isTrue();
        assertThat(evaluator.evaluate(task, answer("2^-42")).path("passed").asBoolean()).isFalse();
    }

    @Test
    void stringRuleAcceptsEquivalentProbabilityNotation() {
        TaskDefinition task = task("string", "{\"type\":\"string\",\"expected\":\"2^-41\",\"ignoreCase\":true}");
        assertThat(evaluator.evaluate(task, answer("2^(-41)")).path("passed").asBoolean()).isTrue();
        assertThat(evaluator.evaluate(task, answer("2^{-41}")).path("passed").asBoolean()).isTrue();
        assertThat(evaluator.evaluate(task, answer("1/2^41")).path("passed").asBoolean()).isTrue();
        assertThat(evaluator.evaluate(task, answer("1/2^40")).path("passed").asBoolean()).isFalse();
    }

    @Test
    void booleanRuleMatchesExactly() {
        TaskDefinition task = task("boolean", "{\"type\":\"boolean\",\"expected\":false}");
        assertThat(evaluator.evaluate(task, answer(false)).path("passed").asBoolean()).isTrue();
        assertThat(evaluator.evaluate(task, answer(true)).path("passed").asBoolean()).isFalse();
    }

    @Test
    void dynamicRuleFailsClosed() {
        TaskDefinition task = task("dynamic", "{\"type\":\"dynamic\"}");
        ObjectNode result = evaluator.evaluate(task, answer("anything"));
        assertThat(result.path("passed").asBoolean()).isFalse();
        assertThat(result.path("status").asText()).isEqualTo("failed");
    }

    @Test
    void missingAnswerIsInvalid() {
        TaskDefinition task = task("numeric", "{\"type\":\"numeric\",\"expected\":1,\"absoluteTolerance\":0,\"relativeTolerance\":0}");
        ObjectNode result = evaluator.evaluate(task, mapper.createObjectNode().put("status", "completed"));
        assertThat(result.path("status").asText()).isEqualTo("invalid");
    }

    @Test
    void workspaceValidatorFailsClosedWhenUnavailable() {
        TaskDefinition task = task("workspace-validator", "{\"type\":\"workspace-validator\"}");
        ObjectNode result = evaluator.evaluate(task, answer("anything"));
        assertThat(result.path("passed").asBoolean()).isFalse();
        assertThat(result.path("reason").asText()).contains("未启用");
    }
}

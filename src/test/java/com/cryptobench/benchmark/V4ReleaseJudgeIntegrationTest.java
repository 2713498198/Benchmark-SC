package com.cryptobench.benchmark;

import com.cryptobench.benchmark.config.V4ScoringProperties;
import com.cryptobench.benchmark.execution.service.V4ValidatorRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class V4ReleaseJudgeIntegrationTest {
    @Test
    void scoresGenericTaskThroughTheStagedReleaseJudge() {
        Path suite = Path.of("private", "v4-suite").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(suite.resolve("scripts").resolve("grade_submission.py")),
                "staged V4 judge is not present");

        ObjectMapper mapper = new ObjectMapper();
        V4ValidatorRunner runner = new V4ValidatorRunner(
                new V4ScoringProperties(
                        suite.toString(),
                        System.getenv().getOrDefault("CRYPTOBENCH_V4_PYTHON", "python"),
                        120,
                        ""),
                mapper);
        ObjectNode response = mapper.createObjectNode().put("status", "completed");
        response.putObject("artifact").put("answer", "The minimum active S-box count is 25.");

        ObjectNode result = runner.evaluate("001_CBSC-V2-NL-A01", response);

        assertThat(result.path("passed").asBoolean()).isTrue();
        assertThat(result.path("score").asDouble()).isEqualTo(1.0);
        assertThat(result.path("completionStatus").asText()).isEqualTo("answer_scored");
    }

    @Test
    void executesTaskSpecificValidatorWithoutTreatingFailureAsAnInfrastructureError() {
        Path suite = Path.of("private", "v4-suite").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(suite.resolve("scripts").resolve("grade_submission.py")),
                "staged V4 judge is not present");

        ObjectMapper mapper = new ObjectMapper();
        V4ValidatorRunner runner = new V4ValidatorRunner(
                new V4ScoringProperties(
                        suite.toString(),
                        System.getenv().getOrDefault("CRYPTOBENCH_V4_PYTHON", "python"),
                        120,
                        ""),
                mapper);
        ObjectNode response = mapper.createObjectNode().put("status", "completed");
        response.putObject("artifact").put("answer", "No certificate supplied.");

        ObjectNode result = runner.evaluate("106_CBSC-V4-PB-GIFT64-3DIFF-ZERO-016", response);

        assertThat(result.path("status").asText()).isIn("failed", "validator_error");
        assertThat(result.path("reason").asText()).doesNotContain("执行异常", "未启用");
    }
}

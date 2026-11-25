package com.cryptobench.benchmark;

import com.cryptobench.benchmark.config.V4ScoringProperties;
import com.cryptobench.benchmark.execution.service.V4ValidatorRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in proof against the server-private suite; CI never needs hidden material. */
class V4PrivateSuiteIntegrationTest {
    private static final String TASK_ID = "142_CBSC-V4-PDL-CA10-SBOX-AUDIT-038";

    @Test
    void scoresUpstreamPositiveWorkspaceFixture() throws Exception {
        String suite = System.getenv("CRYPTOBENCH_V4_TEST_SUITE_ROOT");
        assumeTrue(suite != null && !suite.isBlank(), "CRYPTOBENCH_V4_TEST_SUITE_ROOT is not configured");
        Path task = Path.of(suite, "tasks", TASK_ID);
        Path positive = task.resolve("oracle").resolve("validator_selftest_positive");
        assumeTrue(Files.isDirectory(positive), "private positive fixture is not present");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode response = mapper.createObjectNode().put("status", "completed");
        ObjectNode artifact = response.putObject("artifact");
        artifact.put("answer", Files.readString(positive.resolve("final_answer.md"), StandardCharsets.UTF_8));
        var files = artifact.putArray("files");
        Path workspace = positive.resolve("workspace");
        try (var stream = Files.walk(workspace)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                files.addObject()
                        .put("path", workspace.relativize(file).toString().replace('\\', '/'))
                        .put("content", Files.readString(file, StandardCharsets.UTF_8));
            }
        }

        V4ValidatorRunner runner = new V4ValidatorRunner(
                new V4ScoringProperties(
                        suite,
                        System.getenv().getOrDefault("CRYPTOBENCH_V4_PYTHON", "python3"),
                        120,
                        ""),
                mapper);
        ObjectNode result = runner.evaluate(TASK_ID, response);

        assertThat(result.path("reason").asText()).doesNotContain("未启用", "执行失败", "执行异常");
        assertThat(result.path("passed").asBoolean()).isTrue();
        assertThat(result.path("score").asDouble()).isEqualTo(1.0);
    }
}

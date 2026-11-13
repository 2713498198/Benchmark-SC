package com.cryptobench.benchmark;

import com.cryptobench.benchmark.config.V4ScoringProperties;
import com.cryptobench.benchmark.execution.service.V4ValidatorRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class V4ValidatorRunnerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reportsUnavailableWhenDisabled() {
        V4ValidatorRunner runner = new V4ValidatorRunner(new V4ScoringProperties("", "python3", 0), mapper);
        assertThat(runner.available()).isFalse();
        ObjectNode result = runner.evaluate("001", mapper.createObjectNode());
        assertThat(result.path("passed").asBoolean()).isFalse();
        assertThat(result.path("reason").asText()).contains("未启用");
    }

    @Test
    void runsMockValidatorAndParsesReport() throws Exception {
        String python = resolvePython();
        if (python == null) return;

        Path suite = Files.createTempDirectory("cryptobench-v4-suite-");
        try {
            Files.createDirectories(suite.resolve("scripts"));
            Files.writeString(suite.resolve("scripts").resolve("run_task_validator.py"),
                    "import json, sys\n"
                    + "out = None\n"
                    + "args = sys.argv[1:]\n"
                    + "for i, a in enumerate(args):\n"
                    + "    if a == '--out' and i + 1 < len(args):\n"
                    + "        out = args[i + 1]\n"
                    + "report = {'schema_version':'cbsc_task_validator_report_v1','task_id':'001_TEST','status':'passed','authoritative':True,'final_score':1.0}\n"
                    + "open(out, 'w', encoding='utf-8').write(json.dumps(report))\n");

            V4ValidatorRunner runner = new V4ValidatorRunner(
                    new V4ScoringProperties(suite.toString(), python, 60), mapper);
            ObjectNode artifact = mapper.createObjectNode();
            artifact.put("answer", "final answer");
            ObjectNode result = runner.evaluate("001_TEST", artifact);
            assertThat(result.path("passed").asBoolean()).isTrue();
            assertThat(result.path("score").asDouble()).isEqualTo(1.0);
        } finally {
            deleteRecursively(suite);
        }
    }

    private static String resolvePython() {
        for (String candidate : new String[]{"python", "python3"}) {
            try {
                Process probe = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                if (probe.waitFor() == 0) return candidate;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static void deleteRecursively(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(item -> {
                try { Files.deleteIfExists(item); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}

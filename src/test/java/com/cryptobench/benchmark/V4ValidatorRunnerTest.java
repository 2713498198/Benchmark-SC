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
        V4ValidatorRunner runner = new V4ValidatorRunner(new V4ScoringProperties("", "python3", 0, ""), mapper);
        assertThat(runner.available()).isFalse();
        ObjectNode response = mapper.createObjectNode();
        response.putObject("artifact").put("answer", "answer");
        ObjectNode result = runner.evaluate("001", response);
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
                    new V4ScoringProperties(suite.toString(), python, 60, ""), mapper);
            ObjectNode response = mapper.createObjectNode();
            response.putObject("artifact").put("answer", "final answer");
            ObjectNode result = runner.evaluate("001_TEST", response);
            assertThat(result.path("passed").asBoolean()).isTrue();
            assertThat(result.path("score").asDouble()).isEqualTo(1.0);
        } finally {
            deleteRecursively(suite);
        }
    }

    @Test
    void usesReleaseJudgeAndMapsCompletionMetadata() throws Exception {
        String python = resolvePython();
        if (python == null) return;

        Path suite = Files.createTempDirectory("cryptobench-v4-judge-");
        try {
            Files.createDirectories(suite.resolve("scripts"));
            Files.writeString(suite.resolve("scripts").resolve("grade_submission.py"), """
                    import json, sys
                    from pathlib import Path
                    args = sys.argv[1:]
                    out = Path(args[args.index('--out') + 1])
                    report = {
                        'task_id': '023_TEST',
                        'status': 'passed',
                        'final_score': 1.0,
                        'completion_status': 'answer_verified',
                        'completion_metric_eligible': False,
                        'release_check_status': 'passed',
                        'release_check_scope': 'fixed_instance_record',
                    }
                    out.write_text(json.dumps(report), encoding='utf-8')
                    """);

            V4ValidatorRunner runner = new V4ValidatorRunner(
                    new V4ScoringProperties(suite.toString(), python, 60, ""), mapper);
            ObjectNode response = mapper.createObjectNode().put("status", "completed");
            response.putObject("artifact").put("answer", "certificate");
            ObjectNode result = runner.evaluate("023_TEST", response);

            assertThat(result.path("passed").asBoolean()).isTrue();
            assertThat(result.path("score").asDouble()).isEqualTo(1.0);
            assertThat(result.path("completionStatus").asText()).isEqualTo("answer_verified");
            assertThat(result.path("completionMetricEligible").asBoolean()).isFalse();
            assertThat(result.path("releaseCheckStatus").asText()).isEqualTo("passed");
            assertThat(result.path("releaseCheckScope").asText()).isEqualTo("fixed_instance_record");
        } finally {
            deleteRecursively(suite);
        }
    }

    @Test
    void writesArtifactFilesAndEvidenceIntoValidatorWorkspace() throws Exception {
        String python = resolvePython();
        if (python == null) return;

        Path suite = Files.createTempDirectory("cryptobench-v4-suite-");
        try {
            Files.createDirectories(suite.resolve("scripts"));
            Files.writeString(suite.resolve("scripts").resolve("run_task_validator.py"), """
                    import json, sys
                    from pathlib import Path
                    args = sys.argv[1:]
                    run_dir = Path(args[args.index('--run-dir') + 1])
                    out = Path(args[args.index('--out') + 1])
                    passed = (
                        (run_dir / 'workspace' / 'code' / 'solve.py').read_text(encoding='utf-8') == "print('ok')\\n"
                        and (run_dir / 'logs' / 'agent_stdout.txt').read_text(encoding='utf-8').strip() == 'executed solver'
                    )
                    report = {'status': 'passed' if passed else 'failed', 'final_score': 1.0 if passed else 0.0}
                    out.write_text(json.dumps(report), encoding='utf-8')
                    """);

            V4ValidatorRunner runner = new V4ValidatorRunner(
                    new V4ScoringProperties(suite.toString(), python, 60, ""), mapper);
            ObjectNode response = mapper.createObjectNode();
            ObjectNode artifact = response.putObject("artifact");
            artifact.put("answer", "done");
            artifact.putArray("files").addObject()
                    .put("path", "code/solve.py")
                    .put("content", "print('ok')\n");
            response.putArray("evidence").addObject()
                    .put("type", "command")
                    .put("content", "executed solver");

            ObjectNode result = runner.evaluate("001_TEST", response);
            assertThat(result.path("passed").asBoolean()).isTrue();
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

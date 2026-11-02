package com.cryptobench.benchmark.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptobench.benchmark.execution.model.RunTask;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportMetricsTest {
    private TaskDefinition task(String id, String category) {
        return new TaskDefinition(id, "q", "a", List.of(), List.of(), List.of(), null, category, null);
    }

    private RunTask result(String id, boolean passed, Long elapsed) {
        return new RunTask("run", id, 0, passed ? "passed" : "failed", 2,
                null, null, passed, null, null, elapsed, null, null);
    }

    @Test
    void includesFailedTasksAndUsesOnlySelectedCategoriesAndFinalAttempt() {
        var report = new ObjectMapper().createObjectNode();
        ReportMetrics.add(report, List.of(task("1", " 数学 "), task("2", "数学"), task("3", "")),
                List.of(result("main::1", true, 1000L), result("main::2", false, 3000L), result("main::3", true, 2000L)));
        assertThat(report.path("totalTimeSeconds").asDouble()).isEqualTo(6);
        assertThat(report.path("averageTimeSeconds").asDouble()).isEqualTo(2);
        assertThat(report.path("categories").size()).isEqualTo(2);
        assertThat(report.at("/categories/0/name").asText()).isEqualTo("数学");
        assertThat(report.at("/categories/0/passed").asInt()).isEqualTo(1);
        assertThat(report.at("/categories/0/total").asInt()).isEqualTo(2);
        assertThat(report.at("/categories/0/score").asDouble()).isEqualTo(50);
        assertThat(report.at("/categories/1/name").asText()).isEqualTo("未分类");
    }

    @Test
    void deterministicTwoTaskTimingAndParticipantIsolation() {
        var selected = List.of(task("1", "A"), task("2", "B"));
        var a = new ObjectMapper().createObjectNode();
        var b = new ObjectMapper().createObjectNode();
        ReportMetrics.add(a, selected, List.of(result("a::1", true, 1000L), result("a::2", false, 3000L)));
        ReportMetrics.add(b, selected, List.of(result("b::1", false, 0L), result("b::2", true, 0L)));
        assertThat(a.path("totalTimeSeconds").asDouble()).isEqualTo(4);
        assertThat(a.path("averageTimeSeconds").asDouble()).isEqualTo(2);
        assertThat(b.path("averageTimeSeconds").asDouble()).isZero();
        assertThat(a.at("/categories/0/score").asDouble()).isEqualTo(100);
        assertThat(b.at("/categories/0/score").asDouble()).isZero();
    }

    @Test
    void incompleteOrInvalidTimingNeverBecomesZeroOrPartialAverage() {
        var selected = List.of(task("1", "A"), task("2", "B"));
        for (var results : List.of(
                List.of(result("main::1", true, 1000L)),
                List.of(result("main::1", true, 1000L), result("main::2", false, null)),
                List.of(result("main::1", true, -1L), result("main::2", false, 3000L)),
                List.of(result("main::1", true, 1000L), result("main::1", false, 3000L)))) {
            var report = new ObjectMapper().createObjectNode();
            ReportMetrics.add(report, selected, results);
            assertThat(report.path("totalTimeSeconds").isNull()).isTrue();
            assertThat(report.path("averageTimeSeconds").isNull()).isTrue();
            assertThat(report.at("/categories/1/total").asInt()).isEqualTo(1);
        }
    }
}

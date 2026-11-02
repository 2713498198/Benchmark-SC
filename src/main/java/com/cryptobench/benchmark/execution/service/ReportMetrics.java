package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.execution.model.RunTask;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Metrics use the selected tasks and the final persisted result of each task. */
final class ReportMetrics {
    private ReportMetrics() {}

    static String taskId(RunTask task) {
        int separator = task.taskId().indexOf("::");
        return separator < 0 ? task.taskId() : task.taskId().substring(separator + 2);
    }

    static String category(TaskDefinition task) {
        return task.category().isBlank() ? "未分类" : task.category().trim();
    }

    static void add(ObjectNode report, List<TaskDefinition> selected, List<RunTask> results) {
        Map<String, RunTask> byId = new LinkedHashMap<>();
        boolean complete = !selected.isEmpty() && results.size() == selected.size();
        for (RunTask result : results) {
            if (byId.put(taskId(result), result) != null) complete = false;
        }
        double elapsed = 0;
        Map<String, int[]> categories = new LinkedHashMap<>();
        for (TaskDefinition task : selected) {
            RunTask result = byId.get(task.id());
            int[] counts = categories.computeIfAbsent(category(task), ignored -> new int[2]);
            counts[1]++;
            if (result != null && Boolean.TRUE.equals(result.passed())) counts[0]++;
            if (result == null || result.elapsedMs() == null || result.elapsedMs() < 0) complete = false;
            else elapsed += result.elapsedMs() / 1000d;
        }
        if (complete && Double.isFinite(elapsed)) {
            report.put("totalTimeSeconds", elapsed);
            report.put("averageTimeSeconds", elapsed / selected.size());
        } else {
            report.putNull("totalTimeSeconds");
            report.putNull("averageTimeSeconds");
        }
        ArrayNode output = report.putArray("categories");
        categories.forEach((name, counts) -> {
            ObjectNode row = output.addObject();
            row.put("name", name);
            row.put("passed", counts[0]);
            row.put("total", counts[1]);
            row.put("score", Math.round(counts[0] * 1000d / counts[1]) / 10d);
        });
    }
}

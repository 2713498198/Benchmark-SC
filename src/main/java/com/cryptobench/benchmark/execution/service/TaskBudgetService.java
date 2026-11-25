package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.config.V4ScoringProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves immutable per-task time budgets. V4 uses the published suite budget
 * profiles; other datasets retain the original short integration-test budgets.
 */
@Service
public class TaskBudgetService {
    static final String V4_DATASET = "cryptobench-sc-v4";
    private static final long DEFAULT_V4_TIMEOUT = 1_800;
    private static final long MAX_TIMEOUT = 24 * 60 * 60;
    private static final Logger log = LoggerFactory.getLogger(TaskBudgetService.class);

    private final ObjectMapper mapper;
    private final V4ScoringProperties properties;
    private final Map<String, JsonNode> publicCatalogs = new ConcurrentHashMap<>();
    private volatile JsonNode budgetProfiles;

    public TaskBudgetService(ObjectMapper mapper, V4ScoringProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public record TaskBudget(long timeoutSeconds, int maxAgentCalls, long validationTimeoutSeconds,
                             String budgetClass, String profile) {}

    public TaskBudget resolve(String datasetId, String version, String taskId, String runBudget) {
        String profile = "extended".equals(runBudget) ? "extended" : "standard";
        if (!V4_DATASET.equals(datasetId)) {
            return new TaskBudget(profile.equals("extended") ? 600 : 300,
                    profile.equals("extended") ? 40 : 20, 120, "", profile);
        }

        String budgetClass = normalizeBudgetClass(budgetClass(version, taskId));
        JsonNode settings = profileSettings(profile, budgetClass);
        long base = positive(settings.path("agent_wall_time_sec").asLong(), fallbackTimeout(budgetClass));
        long extension = positive(settings.path("wall_time_extension_sec").asLong(), 0);
        long extensionCount = positive(settings.path("max_wall_time_extensions").asLong(), 0);
        long timeout = multiplyAndAdd(base, extension, extensionCount);
        int maxCalls = (int) Math.max(1, Math.min(Integer.MAX_VALUE,
                positive(settings.path("max_turns").asLong(), profile.equals("extended") ? 40 : 20)));
        long validation = positive(settings.path("validation_timeout_sec").asLong(), 120);
        return new TaskBudget(timeout, maxCalls, validation, budgetClass, profile);
    }

    public long validationTimeoutSeconds(String datasetId, String version, String taskId) {
        if (!V4_DATASET.equals(datasetId)) return 120;
        String budgetClass = normalizeBudgetClass(budgetClass(version, taskId));
        long standard = validationTimeout("standard", budgetClass);
        long extended = validationTimeout("extended", budgetClass);
        return Math.max(standard, extended);
    }

    private long validationTimeout(String profile, String budgetClass) {
        return positive(profileSettings(profile, budgetClass).path("validation_timeout_sec").asLong(), 120);
    }

    private JsonNode profileSettings(String profile, String budgetClass) {
        JsonNode profiles = budgetProfiles().path("profiles");
        JsonNode settings = profiles.path(profile).path(budgetClass);
        if (!settings.isObject() && "extended".equals(profile)) {
            settings = profiles.path("standard").path(budgetClass);
        }
        return settings.isObject() ? settings : mapper.createObjectNode();
    }

    private String budgetClass(String version, String taskId) {
        String path = "/static/data/" + V4_DATASET + "/" + safeSegment(version) + "/catalog.json";
        JsonNode catalog = publicCatalogs.computeIfAbsent(path, this::readClasspathJson);
        for (JsonNode task : catalog.path("tasks")) {
            String id = task.path("task_id").asText(task.path("id").asText(""));
            if (taskId.equals(id)) return task.path("budget_class").asText(task.path("budgetClass").asText(""));
        }
        return "";
    }

    private JsonNode readClasspathJson(String resource) {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            return input == null ? mapper.createObjectNode() : mapper.readTree(input);
        } catch (IOException exception) {
            log.warn("Unable to read task budget catalog {}", resource, exception);
            return mapper.createObjectNode();
        }
    }

    private JsonNode budgetProfiles() {
        JsonNode current = budgetProfiles;
        if (current != null) return current;
        synchronized (this) {
            if (budgetProfiles != null) return budgetProfiles;
            Path path = budgetProfilePath();
            try {
                budgetProfiles = Files.isRegularFile(path)
                        ? mapper.readTree(Files.readString(path))
                        : mapper.createObjectNode();
                if (!Files.isRegularFile(path)) {
                    log.warn("V4 budget profile not found at {}; using conservative built-in budgets", path);
                }
            } catch (IOException exception) {
                log.warn("Unable to read V4 budget profile at {}; using built-in budgets", path, exception);
                budgetProfiles = mapper.createObjectNode();
            }
            return budgetProfiles;
        }
    }

    private Path budgetProfilePath() {
        if (properties.budgetProfiles() != null && !properties.budgetProfiles().isBlank()) {
            return Path.of(properties.budgetProfiles().trim());
        }
        if (properties.suiteRoot() != null && !properties.suiteRoot().isBlank()) {
            return Path.of(properties.suiteRoot().trim(), "configs", "budget_profiles.json");
        }
        return Path.of("private", "cryptobench-sc-v4", "170-20261112", "configs", "budget_profiles.json");
    }

    private static String normalizeBudgetClass(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "easy", "easy_medium", "easy_to_medium" -> "easy_to_medium";
            case "medium", "medium_hard", "medium_to_medium_hard" -> "medium_to_medium_hard";
            case "hard" -> "hard";
            case "very_hard", "veryhard" -> "very_hard";
            default -> "very_hard";
        };
    }

    private static long fallbackTimeout(String budgetClass) {
        return switch (budgetClass) {
            case "easy_to_medium" -> 1_800;
            case "medium_to_medium_hard" -> 2_400;
            case "hard" -> 7_200;
            default -> 10_800;
        };
    }

    private static long multiplyAndAdd(long base, long extension, long count) {
        long extra;
        try {
            extra = Math.multiplyExact(extension, count);
        } catch (ArithmeticException overflow) {
            extra = MAX_TIMEOUT;
        }
        long total;
        try {
            total = Math.addExact(base, extra);
        } catch (ArithmeticException overflow) {
            total = MAX_TIMEOUT;
        }
        return Math.max(1, Math.min(total, MAX_TIMEOUT));
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static String safeSegment(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "");
    }
}

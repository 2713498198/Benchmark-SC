package com.cryptobench.benchmark;

import com.cryptobench.benchmark.config.V4ScoringProperties;
import com.cryptobench.benchmark.execution.service.TaskBudgetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskBudgetServiceTest {
    Path temporary;

    @BeforeEach
    void prepare() throws Exception {
        temporary = Files.createTempDirectory(Path.of("target"), "task-budget-");
    }

    @Test
    void keepsShortBudgetForIntegrationDatasets() {
        TaskBudgetService service = new TaskBudgetService(
                new ObjectMapper(), new V4ScoringProperties("", "python3", 120, ""));

        assertThat(service.resolve("mini-spn", "1.0.0", "mini-spn-001", "standard").timeoutSeconds())
                .isEqualTo(300);
        assertThat(service.resolve("mini-spn", "1.0.0", "mini-spn-001", "extended").timeoutSeconds())
                .isEqualTo(600);
    }

    @Test
    void appliesV4ClassBudgetAndAvailableExtension() throws Exception {
        Path profiles = temporary.resolve("budget_profiles.json");
        Files.writeString(profiles, """
                {
                  "profiles": {
                    "standard": {
                      "easy_to_medium": {
                        "agent_wall_time_sec": 1800,
                        "wall_time_extension_sec": 600,
                        "max_wall_time_extensions": 2,
                        "max_turns": 80,
                        "validation_timeout_sec": 300
                      }
                    }
                  }
                }
                """);
        TaskBudgetService service = new TaskBudgetService(
                new ObjectMapper(), new V4ScoringProperties("", "python3", 120, profiles.toString()));

        TaskBudgetService.TaskBudget budget = service.resolve(
                "cryptobench-sc-v4", "4.1.0", "001_CBSC-V2-NL-A01", "standard");

        assertThat(budget.budgetClass()).isEqualTo("easy_to_medium");
        assertThat(budget.profile()).isEqualTo("standard");
        assertThat(budget.timeoutSeconds()).isEqualTo(3000);
        assertThat(budget.maxAgentCalls()).isEqualTo(80);
        assertThat(budget.validationTimeoutSeconds()).isEqualTo(300);
    }
}

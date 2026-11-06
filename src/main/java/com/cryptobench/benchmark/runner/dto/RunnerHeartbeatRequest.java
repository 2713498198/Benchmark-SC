package com.cryptobench.benchmark.runner.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.Size;

/** Periodic metadata sent by a Runner while it is available for work. */
public record RunnerHeartbeatRequest(@Size(max = 80) String runnerVersion,
                                     ObjectNode agents, Boolean acceptingTasks) {
}

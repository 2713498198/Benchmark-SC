package com.cryptobench.benchmark.runner.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload used by a local Runner to consume a one-time website pairing code. */
public record RunnerPairRequest(@NotBlank @Size(max = 160) String pairingCode,
                                @NotBlank @Size(max = 80) String displayName,
                                @Size(max = 80) String runnerVersion,
                                ObjectNode agents) {
}

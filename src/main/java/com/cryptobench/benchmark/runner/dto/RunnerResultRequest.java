package com.cryptobench.benchmark.runner.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.Size;

/** Result envelope returned by a Runner; the server evaluates the nested response. */
public record RunnerResultRequest(String status, ObjectNode response, String errorCode,
                                  @Size(max = 500) String errorMessage, Long elapsedMs) {
}

package com.cryptobench.benchmark.execution.service;

import java.util.List;

public record DockerRuntimeStatus(boolean enabled, boolean available, String message,
                                  List<ImageStatus> images) {
    public record ImageStatus(String name, boolean available) {}
}

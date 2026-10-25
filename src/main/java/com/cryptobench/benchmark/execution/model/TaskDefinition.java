package com.cryptobench.benchmark.execution.model;

import java.util.List;

/** 服务端可执行任务；expectedAnswer 只存在于 classpath 的非静态资源中。 */
public record TaskDefinition(String id, String question, String expectedAnswer, List<String> publicFiles,
                             List<String> requiredTools, List<String> requiredCapabilities) {
    public TaskDefinition(String id, String question, String expectedAnswer, List<String> publicFiles) {
        this(id, question, expectedAnswer, publicFiles, List.of(), List.of());
    }

    public TaskDefinition {
        publicFiles = publicFiles == null ? List.of() : List.copyOf(publicFiles);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
    }
}

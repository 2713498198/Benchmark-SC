package com.cryptobench.benchmark.execution.model;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** 服务端可执行任务；expectedAnswer/verification 仅由私有目录读取，不用于公开序列化。 */
public record TaskDefinition(String id, String question, String expectedAnswer, List<String> publicFiles,
                             List<String> requiredTools, List<String> requiredCapabilities,
                             JsonNode input, String category, JsonNode verification) {
    public TaskDefinition(String id, String question, String expectedAnswer, List<String> publicFiles) {
        this(id, question, expectedAnswer, publicFiles, List.of(), List.of());
    }

    public TaskDefinition(String id, String question, String expectedAnswer, List<String> publicFiles,
                          List<String> requiredTools, List<String> requiredCapabilities) {
        this(id, question, expectedAnswer, publicFiles, requiredTools, requiredCapabilities,
                JsonNodeFactory.instance.objectNode(), "", JsonNodeFactory.instance.objectNode());
    }

    public TaskDefinition {
        publicFiles = publicFiles == null ? List.of() : List.copyOf(publicFiles);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        input = input == null ? JsonNodeFactory.instance.objectNode() : input.deepCopy();
        category = category == null ? "" : category;
        verification = verification == null ? JsonNodeFactory.instance.objectNode() : verification.deepCopy();
    }
}

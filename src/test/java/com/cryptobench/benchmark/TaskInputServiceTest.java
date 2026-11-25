package com.cryptobench.benchmark;

import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.cryptobench.benchmark.execution.service.TaskInputService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskInputServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test
    void attachesPublicFileBytesAndWorkspacePath() throws Exception {
        String id = "106_CBSC-V4-PB-GIFT64-3DIFF-ZERO-016";
        String path = "tasks/" + id + "/public/attachments/gift64_3diff_instance.json";
        TaskDefinition task = new TaskDefinition(id, "question", "", List.of(path),
                List.of(), List.of(), mapper.createObjectNode(), "", mapper.createObjectNode());
        ObjectNode request = mapper.createObjectNode();

        new TaskInputService().attach("cryptobench-sc-v4", "4.2.0", task, request);

        assertThat(request.path("files")).hasSize(1);
        var file = request.path("files").get(0);
        byte[] content = Base64.getDecoder().decode(file.path("content").asText());
        assertThat(file.path("path").asText()).isEqualTo("attachments/gift64_3diff_instance.json");
        assertThat(file.path("url").asText()).isEqualTo(
                "data/cryptobench-sc-v4/4.2.0/" + path);
        assertThat(file.path("encoding").asText()).isEqualTo("base64");
        assertThat(file.path("bytes").asInt()).isEqualTo(content.length);
        assertThat(file.path("sha256").asText()).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        assertThat(new String(content, StandardCharsets.UTF_8)).contains("GIFT");
    }

    @Test
    void resolvesEveryPublishedV4Attachment() throws Exception {
        var catalog = mapper.readTree(getClass().getResourceAsStream(
                "/evaluation/cryptobench-sc-v4/4.2.0/tasks.json"));
        TaskInputService service = new TaskInputService();
        int count = 0;
        for (var taskNode : catalog.path("tasks")) {
            List<String> publicFiles = new ArrayList<>();
            taskNode.path("publicFiles").forEach(path -> publicFiles.add(path.asText()));
            if (publicFiles.isEmpty()) continue;
            TaskDefinition task = new TaskDefinition(taskNode.path("id").asText(), "question", "",
                    publicFiles, List.of(), List.of(), mapper.createObjectNode(), "", mapper.createObjectNode());
            ObjectNode request = mapper.createObjectNode();
            service.attach("cryptobench-sc-v4", "4.2.0", task, request);
            for (var file : request.path("files")) {
                assertThat(file.path("path").asText()).doesNotStartWith("tasks/");
                assertThat(file.path("content").asText()).isNotBlank();
                assertThat(file.path("sha256").asText()).hasSize(64);
                count++;
            }
        }
        assertThat(count).isEqualTo(245);
    }
}

package com.cryptobench.benchmark;

import com.cryptobench.benchmark.dataset.repository.DatasetRepository;
import com.cryptobench.benchmark.execution.service.TaskCatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:custom-dataset-api-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomDatasetApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DatasetRepository datasets;
    @Autowired TaskCatalogService catalog;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void validatesPublishesAndServesOnlyPublicCatalogThroughRealFilterChain() throws Exception {
        Client admin = admin();
        String id = "custom-" + UUID.randomUUID();
        ObjectNode fixture = CustomDatasetRulesTest.fixture(mapper, id);
        fixture.withArray("tasks").get(0).withObject("verification").put("expected", "HIDDEN_STANDARD_VALUE");
        MvcResult validation = admin.send("POST", "/api/datasets/custom/validate", fixture);
        assertThat(validation.getResponse().getStatus()).isEqualTo(200);
        assertThat(admin.json(validation).path("valid").asBoolean()).isTrue();
        assertThat(validation.getResponse().getContentAsString()).doesNotContain("HIDDEN_STANDARD_VALUE", "verification", "expected");
        assertThat(datasets.exists(id, "1.0.0")).isFalse();
        MvcResult published = admin.send("POST", "/api/datasets/custom", fixture);
        assertThat(published.getResponse().getStatus()).isEqualTo(201);
        JsonNode summary = admin.json(published);
        assertThat(summary.path("executionReady").asBoolean()).isTrue();
        assertThat(summary.path("catalogSha256").asText()).hasSize(64);
        Client guest = new Client();
        MvcResult publicCatalog = guest.send("GET", summary.path("catalogUrl").asText(), null);
        assertThat(publicCatalog.getResponse().getStatus()).isEqualTo(200);
        assertThat(guest.json(publicCatalog).path("tasks").get(0).path("input").path("rounds").asInt()).isEqualTo(4);
        for (MvcResult response : new MvcResult[]{published, publicCatalog, guest.send("GET", "/api/bootstrap", null)}) {
            assertThat(response.getResponse().getContentAsString()).doesNotContain("HIDDEN_STANDARD_VALUE", "taskPackage", "\"expected\"");
        }
        assertThat(admin.send("POST", "/api/datasets/custom", fixture).getResponse().getStatus()).isEqualTo(409);
        assertThat(admin.json(admin.send("POST", "/api/datasets/custom/validate", fixture)).path("valid").asBoolean()).isFalse();
        Client researcher = new Client();
        researcher.send("POST", "/api/auth/register", Map.of("email", UUID.randomUUID()+"@example.test", "name", "Reader", "password", "test-password"));
        assertThat(researcher.send("POST", "/api/datasets/custom/validate", fixture).getResponse().getStatus()).isEqualTo(403);
        assertThat(guest.send("GET", "/api/datasets/missing/versions/1.0.0/catalog", null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void legacyPackagesRemainBrowsableButCannotRunAndNewVersionsSortSemantically() throws Exception {
        Client admin = admin();
        String owner = admin.json(admin.send("GET", "/api/auth/me", null)).path("user").path("id").asText();
        String id = "old-" + UUID.randomUUID();
        ObjectNode legacy = mapper.createObjectNode().put("id", id).put("version", "1.0.0").put("name", "Legacy");
        legacy.putArray("taskPackage").addObject().put("id", "old-task").put("objective", "Public legacy question")
                .putObject("expected").put("answer", "OLD_SECRET");
        legacy.put("tasks", 1);
        datasets.insertLegacy(owner, legacy);
        MvcResult response = new Client().send("GET", "/api/datasets/" + id + "/versions/1.0.0/catalog", null);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(response.getResponse().getContentAsString()).contains("Public legacy question").doesNotContain("OLD_SECRET", "expected");
        assertThatThrownBy(() -> catalog.snapshot(id, "1.0.0")).hasMessageContaining("尚未绑定");
        String freshId = "sort-" + UUID.randomUUID();
        for (String version : new String[]{"1.10.0", "1.9.0"}) {
            assertThat(admin.send("POST", "/api/datasets/custom", CustomDatasetRulesTest.fixture(mapper, freshId).put("version", version)).getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(datasets.findPublished().stream().filter(item -> freshId.equals(item.path("id").asText())).map(item -> item.path("version").asText()))
                .containsExactly("1.9.0", "1.10.0");
    }

    @Test
    void rejectsActualOversizedBodyAndReturnsFieldPathsForInvalidPackages() throws Exception {
        Client admin = admin();
        assertThat(admin.sendRaw("POST", "/api/datasets/custom/validate", " ".repeat(2_000_000)+"{}").getResponse().getStatus()).isEqualTo(413);
        ObjectNode bad = CustomDatasetRulesTest.fixture(mapper, "bad-test");
        bad.withArray("tasks").get(0).withObject("verification").remove("expected");
        MvcResult response = admin.send("POST", "/api/datasets/custom", bad);
        assertThat(response.getResponse().getStatus()).isEqualTo(400);
        assertThat(admin.json(response).path("errors").toString()).contains("tasks[0].verification.expected");
    }

    @Test
    void executesPublishedTaskWithPublicInlineInputAndProducesVerifiedReport() throws Exception {
        Client admin = admin();
        String id = "run-custom-" + UUID.randomUUID();
        JsonNode summary = admin.json(admin.send("POST", "/api/datasets/custom", CustomDatasetRulesTest.fixture(mapper, id)));
        AtomicReference<JsonNode> received = new AtomicReference<>();
        HttpServer agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.equals("/health")) body = "{\"status\":\"ok\",\"version\":\"custom-fixture-1\"}";
            else if (path.equals("/capabilities")) body = "{\"models\":[\"test-model\"],\"tools\":[],\"capabilities\":[]}";
            else {
                JsonNode request = mapper.readTree(exchange.getRequestBody()); received.set(request);
                body = "{\"status\":\"completed\",\"artifact\":{\"answer\":4},\"usage\":{\"inputTokens\":1,\"outputTokens\":1}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        agent.start();
        try {
            MvcResult createdResponse = admin.send("POST", "/api/runs", Map.of("datasetId", id, "version", "1.0.0", "scope", "full", "budget", "standard",
                    "source", "local", "endpoint", "http://127.0.0.1:"+agent.getAddress().getPort(), "model", "test-model", "seed", 987));
            assertThat(createdResponse.getResponse().getStatus()).isEqualTo(202);
            JsonNode run = admin.json(createdResponse);
            for (int index = 0; index < 100 && java.util.Set.of("queued", "running").contains(run.path("status").asText()); index++) {
                Thread.sleep(50);
                run = admin.json(admin.send("GET", "/api/runs/"+run.path("runId").asText(), null));
            }
            assertThat(run.path("status").asText()).isEqualTo("completed");
            assertThat(received.get().path("task").path("input").path("rounds").asInt()).isEqualTo(4);
            assertThat(received.get().toString()).doesNotContain("verification", "expected");
            JsonNode report = admin.json(admin.send("GET", "/api/reports/"+run.path("reportId").asText(), null));
            assertThat(report.path("score").asDouble()).isEqualTo(100);
            assertThat(report.path("verified").asBoolean()).isTrue();
            assertThat(report.path("seed").asLong()).isEqualTo(987);
            assertThat(report.path("evaluatorVersion").asText()).isEqualTo(summary.path("evaluatorVersion").asText());
            assertThat(report.toString()).doesNotContain("\"expected\"");
        } finally { agent.stop(0); }
    }

    private Client admin() throws Exception {
        Client client = new Client();
        assertThat(client.send("POST", "/api/auth/demo", Map.of("role", "admin")).getResponse().getStatus()).isEqualTo(200);
        return client;
    }

    @Test
    void httpPublicationPersistenceAndAgentTransportPreserveHighPrecisionDecimals() throws Exception {
        Client admin=admin();
        HttpServer agent=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        agent.createContext("/", exchange->{
            byte[] bytes="{\"status\":\"completed\",\"artifact\":{\"answer\":1.00000000000000002}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
        });agent.start();
        try{
            for(String rule: new String[]{"exact-json-v1","numeric-v1"}){
                String id="precision-"+UUID.randomUUID();ObjectNode fixture=CustomDatasetRulesTest.fixture(mapper,id);
                fixture.withArray("tasks").get(0).withObject("verification").put("type",rule).put("expected","DECIMAL_EXPECTED");
                fixture.withArray("tasks").get(0).withObject("input").put("value","DECIMAL_EXPECTED");
                String raw=fixture.toString().replace("\"DECIMAL_EXPECTED\"","1.00000000000000001");
                MvcResult published=admin.sendRaw("POST","/api/datasets/custom",raw);
                assertThat(published.getResponse().getStatus()).isEqualTo(201);
                var snapshot=catalog.snapshot(id,"1.0.0");var task=snapshot.tasks().getFirst();
                assertThat(task.input().path("value").decimalValue()).isEqualByComparingTo("1.00000000000000001");
                String publicBody=new Client().send("GET",admin.json(published).path("catalogUrl").asText(),null).getResponse().getContentAsString();
                assertThat(publicBody).contains("1.00000000000000001").doesNotContain("expected", "package_sha256");
                var response=new com.cryptobench.benchmark.execution.service.AgentHttpClient(mapper).analyze("http://127.0.0.1:"+agent.getAddress().getPort(),"",mapper.createObjectNode(),java.time.Duration.ofSeconds(3));
                assertThat(response.path("artifact").path("answer").decimalValue()).isEqualByComparingTo("1.00000000000000002");
                assertThat(catalog.evaluate(id,"1.0.0",task,response).path("passed").asBoolean()).isFalse();
                String privateHash=jdbc.queryForObject("SELECT package_sha256 FROM benchmark_dataset_packages WHERE dataset_id=?",String.class,id);
                assertThat(publicBody).doesNotContain(privateHash);
                jdbc.update("UPDATE benchmark_dataset_packages SET private_payload=? WHERE dataset_id=?",raw.replace("1.00000000000000001","1.00000000000000002"),id);
                assertThatThrownBy(()->catalog.snapshot(id,"1.0.0")).hasMessageContaining("integrity");
            }
        }finally{agent.stop(0);}
    }
    private class Client {
        private final Map<String, Cookie> cookies = new HashMap<>();
        MvcResult send(String method, String path, Object body) throws Exception { return sendRaw(method, path, body == null ? null : mapper.writeValueAsString(body)); }
        MvcResult sendRaw(String method, String path, String body) throws Exception {
            var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
            if (!method.equals("GET")) {
                JsonNode csrf = json(send("GET", "/api/auth/csrf", null));
                request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
            }
            if (!cookies.isEmpty()) request.cookie(cookies.values().toArray(Cookie[]::new));
            if (body != null) request.contentType("application/json").content(body.getBytes(StandardCharsets.UTF_8));
            MvcResult result = mvc.perform(request).andReturn();
            for (Cookie cookie : result.getResponse().getCookies()) cookies.put(cookie.getName(), cookie);
            return result;
        }
        JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
    }
}

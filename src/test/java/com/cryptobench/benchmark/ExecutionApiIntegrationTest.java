package com.cryptobench.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.cryptobench.benchmark.execution.service.ServerCostCalculator;
import com.cryptobench.benchmark.execution.model.Run;
import com.cryptobench.benchmark.execution.repository.RunRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExecutionApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ServerCostCalculator serverCosts;
    @Autowired RunRepository runRepository;
    private static HttpServer agent;

    @BeforeAll
    static void startAgent() throws IOException {
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/health", exchange -> json(exchange, 200, "{\"status\":\"ok\",\"version\":\"test-agent-1\"}"));
        agent.createContext("/capabilities", exchange -> json(exchange, 200,
                "{\"protocolVersion\":\"1.0\",\"agentVersion\":\"test-agent-1\",\"models\":[\"test-model\",\"GPT-4.1\"]}"));
        agent.createContext("/analyze", exchange -> {
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            String id = request.path("taskId").asText();
            String answer = switch (id) {
                case "mini-spn-001" -> "active_sbox_bound=4";
                case "mini-spn-002" -> "active_sbox_bound=6";
                case "mini-spn-003" -> "trail_weight=-8";
                case "mini-spn-004" -> "key_bits=8";
                case "mini-spn-005" -> "linear_bias=0.25";
                case "mini-spn-006" -> "rounds=4";
                case "mini-spn-007" -> "candidate_count=2";
                default -> "verified=true";
            };
            json(exchange, 200, "{\"status\":\"completed\",\"passed\":false,\"score\":0,\"artifact\":{\"answer\":\"" + answer
                    + "\"},\"evidence\":[{\"type\":\"test\",\"content\":\"ok\"}],\"usage\":{\"costUsd\":999,\"inputTokens\":10,\"outputTokens\":10},\"elapsedMs\":1}");
        });
        agent.setExecutor(Executors.newCachedThreadPool());
        agent.start();
    }

    @AfterAll
    static void stopAgent() {
        if (agent != null) agent.stop(0);
    }

    @Test
    void runsLocalAgentAndCreatesServerVerifiedReport() throws Exception {
        Client client = registeredClient();
        String endpoint = "http://127.0.0.1:" + agent.getAddress().getPort();
        JsonNode check = client.json(client.send("POST", "/api/agent/check", Map.of("endpoint", endpoint)));
        assertThat(check.path("reachable").asBoolean()).isTrue();
        JsonNode created = client.json(client.send("POST", "/api/runs", Map.of(
                "datasetId", "mini-spn", "version", "1.0.0", "scope", "sample", "budget", "standard",
                "source", "local", "endpoint", endpoint, "model", "test-model")));
        String id = created.path("runId").asText();
        JsonNode state = created;
        for (int i = 0; i < 50 && !"completed".equals(state.path("status").asText()); i++) {
            Thread.sleep(100);
            state = client.json(client.send("GET", "/api/runs/" + id, null));
        }
        assertThat(state.path("status").asText()).isEqualTo("completed");
        assertThat(state.path("progress").path("percent").asDouble()).isEqualTo(100);
        JsonNode report = client.json(client.send("GET", "/api/reports/" + state.path("reportId").asText(), null));
        assertThat(report.path("verified").asBoolean()).isTrue();
        assertThat(report.path("verificationStatus").asText()).isEqualTo("verified");
        assertReportMetrics(report, report.path("results"), "main");
        assertThat(report.path("score").asDouble()).isEqualTo(100);
        assertThat(report.path("cost").isNull()).isTrue();
        assertThat(report.path("usage").path("inputTokens").asLong()).isEqualTo(80);
        assertThat(report.path("usage").path("outputTokens").asLong()).isEqualTo(80);
        assertThat(report.path("usage").path("totalTokens").asLong()).isEqualTo(160);
        assertThat(report.path("usage").path("knownTasks").asInt()).isEqualTo(8);
        assertThat(report.path("results").get(0).path("usage").path("totalTokens").asLong()).isEqualTo(20);
        assertThat(report.path("results").get(0).path("verification").path("passed").asBoolean()).isTrue();
        JsonNode completedRuns = client.json(client.send("GET", "/api/runs?status=completed&page=0&size=1", null));
        assertThat(completedRuns.isArray()).isTrue();
        assertThat(completedRuns).hasSize(1);
        assertThat(completedRuns.get(0).path("runId").asText()).isEqualTo(id);
        assertThat(client.send("GET", "/api/runs?status=unknown", null).getResponse().getStatus()).isEqualTo(400);
        JsonNode retried = client.json(client.send("POST", "/api/runs/" + id + "/retry", null));
        assertThat(retried.path("runId").asText()).isNotBlank();
        assertThat(retried.path("runId").asText()).isNotEqualTo(id);
        Client other = registeredClient();
        assertThat(other.send("GET", "/api/runs/" + id, null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void rejectsNonLoopbackAgentEndpoint() throws Exception {
        Client client = registeredClient();
        MvcResult result = client.send("POST", "/api/agent/check", Map.of("endpoint", "http://example.com"));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void refusesRetiredDeviceExecutionInsteadOfFallingBackToLocalAgent() throws Exception {
        Client client = registeredClient();
        Map<String, Object> input = new HashMap<>(Map.of(
                "datasetId", "mini-spn", "version", "1.0.0", "scope", "sample", "budget", "standard",
                "source", "local", "endpoint", "http://127.0.0.1:" + agent.getAddress().getPort(),
                "model", "test-model", "executorType", "bridge"));
        input.put("bridgeId", "retired-device");
        input.put("localAgentId", "retired-agent");
        assertThat(client.send("POST", "/api/agent/check", input).getResponse().getStatus()).isEqualTo(400);
        assertThat(client.send("POST", "/api/runs", input).getResponse().getStatus()).isEqualTo(400);
        String owner = client.json(client.send("GET", "/api/auth/me", null)).path("user").path("id").asText();
        assertThat(runRepository.findByOwner(owner)).isEmpty();
    }

    @Test
    void historicalDeviceRunsRemainReadableAndCancelableButCannotResumeOrRetryLocally() throws Exception {
        Client client = registeredClient();
        String owner = client.json(client.send("GET", "/api/auth/me", null)).path("user").path("id").asText();
        String id = "retired-device-run-" + UUID.randomUUID();
        String now = Instant.now().toString();
        ObjectNode config = mapper.createObjectNode().put("executorType", "bridge").put("bridgeId", "retired-device")
                .put("localAgentId", "retired-agent").put("endpoint", "http://127.0.0.1:" + agent.getAddress().getPort());
        runRepository.insert(new Run(id, owner, "evaluation", "local", "mini-spn", "1.0.0", "standard", "sample",
                "test-model", null, 987, "paused", 1, 0, 0, 0, config, "device_offline", "Device offline",
                null, null, false, now, now, null));
        assertThat(client.send("POST", "/api/runs/" + id + "/resume", Map.of()).getResponse().getStatus()).isEqualTo(409);
        assertThat(client.json(client.send("GET", "/api/runs/" + id, null)).path("status").asText()).isEqualTo("paused");
        assertThat(client.send("POST", "/api/runs/" + id + "/cancel", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(client.send("POST", "/api/runs/" + id + "/retry", null).getResponse().getStatus()).isEqualTo(409);
        assertThat(runRepository.findByOwner(owner)).hasSize(1);
        assertThat(runRepository.find(id, false).orElseThrow().config()).isEqualTo(config);
    }

    @Test
    void retiredDeviceEndpointsAreUnavailableEvenToAuthenticatedUsers() throws Exception {
        Client client = registeredClient();
        for (String path : java.util.List.of("/api/bridges", "/api/bridge-device/jobs/next")) {
            MvcResult result = client.send("GET", path, null);
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getHandler()).isNull();
        }
        for (String path : java.util.List.of("/api/bridges/pairing", "/api/bridge-device/pair", "/api/bridge-device/heartbeat")) {
            MvcResult result = client.send("POST", path, Map.of());
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getHandler()).isNull();
        }
    }

    @Test
    void calculatesCostFromServerPricingAndIgnoresAgentCost() throws Exception {
        assertThat(serverCosts.calculate("GPT-4.1", mapper.readTree(
                "{\"usage\":{\"inputTokens\":10,\"outputTokens\":10}}"))).isEqualTo(0.0001d);
        Client client = registeredClient();
        String endpoint = "http://127.0.0.1:" + agent.getAddress().getPort();
        JsonNode created = client.json(client.send("POST", "/api/runs", Map.of(
                "datasetId", "mini-spn", "version", "1.0.0", "scope", "sample", "budget", "standard",
                "source", "local", "endpoint", endpoint, "model", "GPT-4.1")));
        String id = created.path("runId").asText();
        JsonNode state = created;
        for (int i = 0; i < 50 && !"completed".equals(state.path("status").asText()); i++) {
            Thread.sleep(100);
            state = client.json(client.send("GET", "/api/runs/" + id, null));
        }
        JsonNode report = client.json(client.send("GET", "/api/reports/" + state.path("reportId").asText(), null));
        assertThat(report.path("cost").asDouble()).isEqualTo(0.0008d);
        assertThat(report.path("pricingVersion").asText()).isEqualTo("cryptobench-price-table-1.0.0");
        assertThat(report.path("results").get(0).path("costUsd").asDouble()).isEqualTo(0.0001d);
    }

    @Test
    void exposesDockerRuntimeStatusOnlyToAuthenticatedUsers() throws Exception {
        Client anonymous = new Client();
        assertThat(anonymous.send("GET", "/api/agent/runtime", null).getResponse().getStatus()).isEqualTo(401);
        Client client = registeredClient();
        JsonNode status = client.json(client.send("GET", "/api/agent/runtime", null));
        assertThat(status.has("dockerEnabled")).isTrue();
        assertThat(status.has("dockerAvailable")).isTrue();
        assertThat(status.path("limits").path("memory").asText()).isEqualTo("512m");
    }

    @Test
    void marksQueuedAndRunningRunsInterruptedAfterRestartButPreservesTerminalRuns() throws Exception {
        Client client = registeredClient();
        String ownerId = client.json(client.send("GET", "/api/auth/me", null)).path("user").path("id").asText();
        String now = Instant.now().toString();
        String queuedId = "run-recovery-queued-" + UUID.randomUUID();
        String runningId = "run-recovery-running-" + UUID.randomUUID();
        String completedId = "run-recovery-completed-" + UUID.randomUUID();

        runRepository.insert(recoveryFixture(queuedId, ownerId, "queued", now, null));
        runRepository.insert(recoveryFixture(runningId, ownerId, "running", now, now));
        runRepository.insert(recoveryFixture(completedId, ownerId, "completed", now, now));

        assertThat(runRepository.markInterruptedRuns()).isEqualTo(2);
        assertThat(runRepository.find(queuedId, false).orElseThrow().status()).isEqualTo("failed");
        assertThat(runRepository.find(queuedId, false).orElseThrow().errorCode()).isEqualTo("worker_restarted");
        assertThat(runRepository.find(runningId, false).orElseThrow().status()).isEqualTo("failed");
        assertThat(runRepository.find(completedId, false).orElseThrow().status()).isEqualTo("completed");
    }

    @Test
    void pausesInterruptedTasksAndPreservesCompletedResultsAndCancelledRuns() throws Exception {
        Client client = registeredClient();
        String ownerId = client.json(client.send("GET", "/api/auth/me", null)).path("user").path("id").asText();
        String now = Instant.now().toString();
        String queuedId = "run-requeue-" + UUID.randomUUID();
        String cancelledId = "run-cancelled-" + UUID.randomUUID();

        ObjectNode config = mapper.createObjectNode();
        config.put("endpoint", "http://127.0.0.1:8000");
        config.putArray("participants").addObject()
                .put("key", "main").put("label", "Agent").put("agent", "Agent")
                .put("model", "test-model").put("agentVersion", "test-agent-1");
        runRepository.insert(new Run(queuedId, ownerId, "evaluation", "local", "mini-spn", "1.0.0",
                "standard", "sample", "test-model", null, 42, "running", 1, 0, 0, 0, config,
                null, null, null, null, false, now, now, null));
        runRepository.insertTask(queuedId, "main:mini-spn-001", 1);
        runRepository.updateTask(queuedId, "main:mini-spn-001", "running", mapper.createObjectNode(),
                mapper.createObjectNode(), true, 1d, 0.1d, 10L, now, null);
        runRepository.insertTask(queuedId, "main:mini-spn-002", 2);
        runRepository.updateTask(queuedId, "main:mini-spn-002", "passed", mapper.createObjectNode(),
                mapper.createObjectNode(), true, 1d, 0.2d, 20L, now, now);

        runRepository.insert(new Run(cancelledId, ownerId, "evaluation", "local", "mini-spn", "1.0.0",
                "standard", "sample", "test-model", null, 42, "cancelled", 1, 0, 0, 0,
                config, "cancelled", "用户取消运行", null, null, true, now, now, now));

        runRepository.recoverInterruptedRuns();
        Run recovered = runRepository.find(queuedId, false).orElseThrow();
        assertThat(recovered.status()).isEqualTo("paused");
        assertThat(recovered.errorCode()).isEqualTo("worker_restarted");
        assertThat(recovered.startedAt()).isEqualTo(now);
        assertThat(runRepository.findTasks(queuedId).get(0).status()).isEqualTo("interrupted");
        assertThat(runRepository.findTasks(queuedId).get(1).status()).isEqualTo("passed");
        assertThat(runRepository.findTasks(queuedId).get(1).costUsd()).isEqualTo(0.2d);
        assertThat(recovered.completedTasks()).isEqualTo(1);
        assertThat(runRepository.find(cancelledId, false).orElseThrow().status()).isEqualTo("cancelled");
        // Keep the shared test database independent of method execution order.
        runRepository.updateStatus(queuedId, "failed", 0, 0, 0, "test_cleanup", null, null,
                false, null, Instant.now().toString());
    }

    private Run recoveryFixture(String id, String ownerId, String status, String createdAt, String startedAt) {
        return new Run(id, ownerId, "evaluation", "local", "mini-spn", "1.0.0", "standard", "sample",
                "test-model", null, 42, status, 1, 0, 0, 0, mapper.createObjectNode(), null, null, null,
                null, false, createdAt, startedAt, null);
    }

    @Test
    void refusesDatasetThatAgentExplicitlyDoesNotAdvertise() throws Exception {
        HttpServer incompatible = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        incompatible.createContext("/health", exchange -> json(exchange, 200,
                "{\"status\":\"ok\",\"version\":\"limited-agent-1\"}"));
        incompatible.createContext("/capabilities", exchange -> json(exchange, 200,
                "{\"protocolVersion\":\"1.0\",\"agentVersion\":\"limited-agent-1\","
                        + "\"models\":[\"test-model\"],\"datasets\":[{\"id\":\"other-suite\",\"version\":\"1.0.0\"}]}"));
        incompatible.createContext("/analyze", exchange -> json(exchange, 500, "{\"error\":\"should not run\"}"));
        incompatible.setExecutor(Executors.newCachedThreadPool());
        incompatible.start();
        try {
            Client client = registeredClient();
            String endpoint = "http://127.0.0.1:" + incompatible.getAddress().getPort();
            JsonNode created = client.json(client.send("POST", "/api/runs", Map.of(
                    "datasetId", "mini-spn", "version", "1.0.0", "scope", "sample", "budget", "standard",
                    "source", "local", "endpoint", endpoint, "model", "test-model")));
            String id = created.path("runId").asText();
            JsonNode state = created;
            for (int i = 0; i < 50 && !"failed".equals(state.path("status").asText()); i++) {
                Thread.sleep(50);
                state = client.json(client.send("GET", "/api/runs/" + id, null));
            }
            assertThat(state.path("status").asText()).isEqualTo("failed");
            assertThat(state.path("errorMessage").asText()).contains("不支持测评集");
        } finally {
            incompatible.stop(0);
        }
    }

    @Test
    void refusesV4UntilARegisteredServerEvaluatorAndPrivateCatalogExist() throws Exception {
        Client client = registeredClient();
        String endpoint = "http://127.0.0.1:" + agent.getAddress().getPort();
        MvcResult response = client.send("POST", "/api/runs", Map.of(
                "datasetId", "cryptobench-sc-v4", "version", "4.0.0", "scope", "sample",
                "budget", "standard", "source", "local", "endpoint", endpoint, "model", "test-model"));
        assertThat(response.getResponse().getStatus()).isEqualTo(400);
        assertThat(client.json(response).path("error").asText()).contains("尚未绑定服务端验证器");
    }

    @Test
    void runsPkWithTwoServerParticipants() throws Exception {
        Client client = registeredClient();
        String endpoint = "http://127.0.0.1:" + agent.getAddress().getPort();
        JsonNode created = client.json(client.send("POST", "/api/runs", Map.of(
                "kind", "pk", "datasetId", "mini-spn", "version", "1.0.0", "scope", "sample",
                "budget", "standard", "source", "local", "endpoint", endpoint,
                "players", java.util.List.of(Map.of("label", "A", "agent", "Agent A", "model", "test-model"),
                        Map.of("label", "B", "agent", "Agent B", "model", "test-model")))));
        String id = created.path("runId").asText();
        JsonNode state = created;
        for (int i = 0; i < 50 && !"completed".equals(state.path("status").asText()); i++) {
            Thread.sleep(100);
            state = client.json(client.send("GET", "/api/runs/" + id, null));
        }
        assertThat(state.path("status").asText()).isEqualTo("completed");
        JsonNode report = client.json(client.send("GET", "/api/reports/" + state.path("reportId").asText(), null));
        assertThat(report.path("kind").asText()).isEqualTo("pk");
        assertThat(report.path("verified").asBoolean()).isTrue();
        assertThat(report.path("competitors")).hasSize(2);
        for (JsonNode competitor : report.path("competitors")) {
            assertReportMetrics(competitor, report.path("results"), competitor.path("participantKey").asText());
        }
        assertThat(report.path("categories")).isEqualTo(report.at("/competitors/0/categories"));
        assertThat(report.path("competitors").get(0).path("score").asDouble()).isEqualTo(100);
        assertThat(report.path("competitors").get(0).path("usage").path("inputTokens").asLong()).isEqualTo(80);
        assertThat(report.path("competitors").get(0).path("usage").path("outputTokens").asLong()).isEqualTo(80);
        assertThat(report.path("competitors").get(0).path("usage").path("totalTokens").asLong()).isEqualTo(160);
        assertThat(report.path("competitors").get(1).path("usage").path("knownTasks").asInt()).isEqualTo(8);
    }

    private void assertReportMetrics(JsonNode summary, JsonNode results, String participant) {
        double elapsed = 0;
        int count = 0;
        for (JsonNode row : results) {
            assertThat(row.path("category").asText()).isNotBlank();
            assertThat(row.path("participantKey").asText()).isNotBlank();
            if (participant.equals(row.path("participantKey").asText())) {
                elapsed += row.path("elapsedMs").asLong() / 1000d;
                count++;
            }
        }
        assertThat(count).isEqualTo(summary.path("total").asInt());
        assertThat(summary.path("totalTimeSeconds").asDouble()).isCloseTo(elapsed, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(summary.path("averageTimeSeconds").asDouble()).isCloseTo(elapsed / count, org.assertj.core.data.Offset.offset(1e-9));
        int categoryTotal = 0;
        for (JsonNode category : summary.path("categories")) {
            categoryTotal += category.path("total").asInt();
            assertThat(category.has("score")).isTrue();
        }
        assertThat(categoryTotal).isEqualTo(count);
    }

    private Client registeredClient() throws Exception {
        Client client = new Client();
        assertThat(client.send("POST", "/api/auth/register", Map.of("name", "Runner", "email",
                UUID.randomUUID() + "@example.com", "password", "test-password")).getResponse().getStatus()).isEqualTo(201);
        return client;
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private class Client {
        private final Map<String, Cookie> cookies = new HashMap<>();

        MvcResult send(String method, String path, Object body) throws Exception {
            var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
            if (!"GET".equals(method)) {
                JsonNode csrf = json(send("GET", "/api/auth/csrf", null));
                request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
            }
            if (!cookies.isEmpty()) request.cookie(cookies.values().toArray(Cookie[]::new));
            if (body != null) request.contentType("application/json").content(mapper.writeValueAsBytes(body));
            MvcResult result = mvc.perform(request).andReturn();
            for (Cookie cookie : result.getResponse().getCookies()) cookies.put(cookie.getName(), cookie);
            return result;
        }

        JsonNode json(MvcResult result) throws Exception {
            return mapper.readTree(result.getResponse().getContentAsByteArray());
        }
    }
}

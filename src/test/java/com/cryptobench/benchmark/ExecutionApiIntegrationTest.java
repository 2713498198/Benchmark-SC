package com.cryptobench.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    private static HttpServer agent;

    @BeforeAll
    static void startAgent() throws IOException {
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/health", exchange -> json(exchange, 200, "{\"status\":\"ok\",\"version\":\"test-agent-1\"}"));
        agent.createContext("/capabilities", exchange -> json(exchange, 200,
                "{\"protocolVersion\":\"1.0\",\"agentVersion\":\"test-agent-1\",\"models\":[\"test-model\"]}"));
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
        assertThat(report.path("score").asDouble()).isEqualTo(100);
        assertThat(report.path("cost").isNull()).isTrue();
        assertThat(report.path("results").get(0).path("verification").path("passed").asBoolean()).isTrue();
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
        assertThat(report.path("competitors").get(0).path("score").asDouble()).isEqualTo(100);
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

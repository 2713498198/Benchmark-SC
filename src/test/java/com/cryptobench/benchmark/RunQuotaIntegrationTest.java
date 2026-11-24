package com.cryptobench.benchmark;

import com.cryptobench.benchmark.execution.model.Run;
import com.cryptobench.benchmark.execution.repository.RunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:run-quota-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "benchmark.run-quota.max-active-per-user=1",
        "benchmark.run-quota.max-created-per-day-per-user=2",
        "benchmark.run-quota.max-active-global=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunQuotaIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RunRepository runs;

    @Test
    void enforcesPerUserDailyAndGlobalQuotasWithExplicitRateLimitErrors() throws Exception {
        Client active = registeredClient();
        insert(active.ownerId(), "paused");
        MvcResult activeLimited = active.send("POST", "/api/runs", runBody());
        assertThat(activeLimited.getResponse().getStatus()).isEqualTo(429);
        assertThat(active.json(activeLimited).path("error").asText()).contains("同时保留的运行已达上限");

        JsonNode runtime = active.json(active.send("GET", "/api/agent/runtime", null));
        assertThat(runtime.path("limits").path("runQuota").path("maxActivePerUser").asInt()).isEqualTo(1);
        assertThat(runtime.path("limits").path("runQuota").path("activeRuns").asInt()).isEqualTo(1);
        assertThat(runtime.path("limits").path("runQuota").path("createdToday").asInt()).isEqualTo(1);

        Client daily = registeredClient();
        insert(daily.ownerId(), "completed");
        insert(daily.ownerId(), "failed");
        MvcResult dailyLimited = daily.send("POST", "/api/runs", runBody());
        assertThat(dailyLimited.getResponse().getStatus()).isEqualTo(429);
        assertThat(daily.json(dailyLimited).path("error").asText()).contains("今日创建运行已达上限");

        insert(registeredClient().ownerId(), "paused");
        insert(registeredClient().ownerId(), "paused");
        Client global = registeredClient();
        MvcResult globalLimited = global.send("POST", "/api/runs", runBody());
        assertThat(globalLimited.getResponse().getStatus()).isEqualTo(429);
        assertThat(global.json(globalLimited).path("error").asText()).contains("平台活跃运行已达上限");
    }

    private void insert(String ownerId, String status) {
        String now = Instant.now().toString();
        var config = mapper.createObjectNode()
                .put("schema", "cryptobench-execution-plan-v1")
                .put("executorType", "server")
                .put("source", "local");
        runs.insert(new Run("quota-" + UUID.randomUUID(), ownerId, "evaluation", "local", "mini-spn",
                "1.0.0", "standard", "sample", "test-model", "fixture", 42, status, 0, 0, 0, 0,
                config, null, null, null, null, false, now, null, null));
    }

    private Map<String, Object> runBody() {
        return Map.of("datasetId", "mini-spn", "version", "1.0.0", "scope", "sample",
                "budget", "standard", "source", "local", "endpoint", "http://127.0.0.1:65534",
                "model", "test-model");
    }

    private Client registeredClient() throws Exception {
        Client client = new Client();
        assertThat(client.send("POST", "/api/auth/register", Map.of("name", "Quota",
                "email", client.email, "password", "quota-test-password")).getResponse().getStatus()).isEqualTo(201);
        return client;
    }

    private final class Client {
        private final Map<String, Cookie> cookies = new HashMap<>();
        private final String email = "quota-" + UUID.randomUUID() + "@example.com";
        private String ownerId;

        String ownerId() throws Exception {
            if (ownerId == null) ownerId = json(send("GET", "/api/auth/me", null)).path("user").path("id").asText();
            return ownerId;
        }

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

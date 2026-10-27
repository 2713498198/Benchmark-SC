package com.cryptobench.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the public price snapshot and the administrator version lifecycle. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PricingApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void exposesConfigFallbackAndAllowsOnlyAdminsToPublishVersionedPrices() throws Exception {
        Client anonymous = new Client();
        JsonNode fallback = anonymous.json(anonymous.send("GET", "/api/pricing/active", null));
        assertThat(fallback.path("status").asText()).isEqualTo("config");
        assertThat(fallback.path("prices").isArray()).isTrue();

        Client researcher = registeredClient();
        assertThat(researcher.send("GET", "/api/admin/pricing", null).getResponse().getStatus()).isEqualTo(403);

        Client admin = new Client();
        assertThat(admin.send("POST", "/api/auth/demo", Map.of("role", "admin")).getResponse().getStatus()).isEqualTo(200);
        String version = "test-price-" + UUID.randomUUID();
        JsonNode created = admin.json(admin.send("POST", "/api/admin/pricing", Map.of(
                "version", version,
                "prices", java.util.List.of(
                        Map.of("model", "Example Model", "inputPerMillionTokens", 1.25,
                                "outputPerMillionTokens", 2.5, "currency", "USD"),
                        Map.of("model", "second-model", "inputPerMillionTokens", 0.5,
                                "outputPerMillionTokens", 1.0, "currency", "USD")))));
        assertThat(created.path("status").asText()).isEqualTo("draft");
        assertThat(created.path("prices")).hasSize(2);

        assertThat(admin.send("POST", "/api/admin/pricing/" + version + "/activate", null)
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode active = anonymous.json(anonymous.send("GET", "/api/pricing/active", null));
        assertThat(active.path("version").asText()).isEqualTo(version);
        assertThat(active.path("status").asText()).isEqualTo("active");
        assertThat(active.path("createdBy").isNull()).isTrue();
        assertThat(active.path("prices")).hasSize(2);

        String invalid = "invalid-price-" + UUID.randomUUID();
        assertThat(admin.send("POST", "/api/admin/pricing", Map.of(
                "version", invalid,
                "prices", java.util.List.of(Map.of("model", "bad", "inputPerMillionTokens", -1,
                        "outputPerMillionTokens", 1, "currency", "USD"))))
                .getResponse().getStatus()).isEqualTo(400);
    }

    private Client registeredClient() throws Exception {
        Client client = new Client();
        assertThat(client.send("POST", "/api/auth/register", Map.of(
                "name", "Price Researcher", "email", UUID.randomUUID() + "@example.com",
                "password", "test-password")).getResponse().getStatus()).isEqualTo(201);
        return client;
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

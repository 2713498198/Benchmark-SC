package com.cryptobench.benchmark;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.SessionRepository;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.auth.service.DemoSessionInitializer;
import com.cryptobench.benchmark.config.BenchmarkProperties;
import com.cryptobench.benchmark.report.model.BenchmarkReport;
import com.cryptobench.benchmark.report.repository.ReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "benchmark.demo-enabled=false", "benchmark.docker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:demo-session-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DemoSessionInitializerIntegrationTest {
    @Autowired DemoSessionInitializer initializer;
    @Autowired UserRepository users;
    @Autowired SessionRepository sessions;
    @Autowired ReportRepository reports;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @Test
    void disabledDemoRejectsBothRolesWithValidCsrf() throws Exception {
        for (String role : new String[]{"admin", "researcher"}) {
            mvc.perform(post("/api/auth/demo").with(csrf()).contentType("application/json")
                    .content("{\"role\":\"" + role + "\"}")).andExpect(status().isForbidden());
        }
    }

    @Test
    void disablingDemoRevokesExactIdentitiesButPreservesRealSessionsAndOwnedData() throws Exception {
        // Cover canonical IDs even after an email change, and reused emails with unrelated IDs.
        String[][] accounts = {
                {"demo_admin", "renamed-admin@example.com"},
                {"demo_researcher", "renamed-researcher@example.com"},
                {"reused-admin", "admin@cryptobench.local"},
                {"reused-researcher", "researcher@cryptobench.local"},
                {"real-admin", "real-admin@cryptobench.local"}
        };
        for (int i = 0; i < accounts.length; i++) {
            users.insert(new BenchmarkUser(accounts[i][0], accounts[i][1], "Session test", "admin",
                    "unused-hash", null, Instant.now().toString()));
            addSession(accounts[i][0], token(i));
            mvc.perform(get("/api/admin/users").cookie(cookie(i))).andExpect(status().isOk());
        }
        // All sessions for a demo identity must be revoked, not just one browser's session.
        addSession("demo_admin", token(8));
        var payload = mapper.createObjectNode().put("id", "preserved-report").put("score", 75);
        reports.insert(new BenchmarkReport("preserved-report", "demo_admin", false, payload));

        initializer.run(null);
        initializer.run(null); // Idempotent startup.
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/api/admin/users").cookie(cookie(i))).andExpect(status().isUnauthorized());
            assertThat(users.findById(accounts[i][0])).isPresent();
        }
        mvc.perform(get("/api/admin/users").cookie(cookie(8))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").cookie(cookie(4))).andExpect(status().isOk());
        assertThat(reports.findById("preserved-report", false).orElseThrow().payload()).isEqualTo(payload);

        new DemoSessionInitializer(new BenchmarkProperties(true, false, false, ""), users, sessions).run(null);
        mvc.perform(get("/api/admin/users").cookie(cookie(0))).andExpect(status().isUnauthorized());
        addSession("demo_admin", token(9));
        mvc.perform(get("/api/admin/users").cookie(cookie(9))).andExpect(status().isOk());
    }

    @Test
    void enabledDemoDoesNotRevokeSessions() throws Exception {
        users.insert(new BenchmarkUser("demo_admin", "admin@cryptobench.local", "Demo", "admin",
                "unused-hash", null, Instant.now().toString()));
        addSession("demo_admin", token(0));
        new DemoSessionInitializer(new BenchmarkProperties(true, false, false, ""), users, sessions).run(null);
        mvc.perform(get("/api/admin/users").cookie(cookie(0))).andExpect(status().isOk());
    }

    private void addSession(String userId, String token) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        sessions.insert(hash, userId, System.currentTimeMillis() + 60_000);
    }

    private String token(int index) { return Integer.toHexString(index + 1).repeat(64); }
    private Cookie cookie(int index) { return new Cookie("cb_session", token(index)); }
}

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
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过 MockMvc 和 test profile 验证真实 Spring 过滤器链、业务接口及数据库持久化。
 * 每个 Client 独立维护 Cookie，模拟不同浏览器账号，使用随机 ID 避免测试间数据冲突。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BenchmarkApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    /**
     * 验证页面与 127 题公开目录可访问，同时禁止读取项目源码、运行数据及环境文件。
     */
    @Test
    void servesVueAndPublicDatasetWithoutExposingProjectOrRuntimeFiles() throws Exception {
        Client client = new Client();
        assertThat(client.send("GET", "/", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(client.send("GET", "/index.html", null).getResponse().getContentAsString()).contains("id=\"app\"");
        assertThat(client.send("GET", "/js/app.js", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(client.send("GET", "/vendor/vue/vue.esm-browser.prod.js", null).getResponse().getStatus()).isEqualTo(200);
        JsonNode catalog = client.json(client.send("GET", "/data/cryptobench-sc-v4/catalog.json", null));
        assertThat(catalog.path("tasks").size()).isEqualTo(127);
        assertThat(client.send("GET", "/data/cryptobench-sc-v4/public-tasks.zip", null).getResponse().getStatus()).isEqualTo(200);
        for (String path : new String[]{"/.env", "/pom.xml", "/data/runtime/db.json", "/var/data/cryptobench.mv.db",
                "/scripts/import_cryptobench.py", "/db/migration/V1__create_benchmark_schema.sql"}) {
            assertThat(client.send("GET", path, null).getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        }
    }

    /**
     * 验证注册参数、固定研究者角色、密码哈希、会话摘要，以及退出后原会话不可继续使用。
     */
    @Test
    void validatesRegistrationAndPersistsHashedPasswordsAndRevocableSessions() throws Exception {
        Client client = new Client();
        assertThat(client.send("GET", "/api/connections", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(client.send("POST", "/api/auth/register", Map.of("name", "Test", "email", "bad", "password", "123"))
                .getResponse().getStatus()).isEqualTo(400);
        String email = "auth-" + UUID.randomUUID() + "@example.com";
        MvcResult registration = client.send("POST", "/api/auth/register",
                Map.of("name", "研究者", "email", email, "password", "test-password", "role", "admin"));
        assertThat(registration.getResponse().getStatus()).isEqualTo(201);
        assertThat(client.json(registration).path("user").path("role").asText()).isEqualTo("researcher");
        assertThat(registration.getResponse().getContentAsString()).doesNotContain("password");
        assertThat(registration.getResponse().getHeaders("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM benchmark_users WHERE email = ?", String.class, email);
        assertThat(passwordHash).startsWith("{pbkdf2}").doesNotContain("test-password");
        String token = client.cookies.get("cb_session").getValue();
        // 原始令牌不能直接匹配数据库摘要列，确认数据库没有保存可直接使用的 Cookie 值。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_sessions WHERE token_hash = ?", Long.class, token)).isZero();
        assertThat(client.json(client.send("GET", "/api/auth/me", null)).path("user").path("email").asText()).isEqualTo(email);
        assertThat(client.send("POST", "/api/auth/register", Map.of("name", "Test", "email", email, "password", "test-password"))
                .getResponse().getStatus()).isEqualTo(409);
        client.send("POST", "/api/auth/logout", null);
        assertThat(client.send("GET", "/api/connections", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(client.send("POST", "/api/auth/login", Map.of("email", email, "password", "wrong"))
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(client.send("POST", "/api/auth/login", Map.of("email", email, "password", "test-password"))
                .getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * 用两个用户和一个游客验证归属隔离、服务端状态控制、同步后公开及公开后不可改写。
     */
    @Test
    void isolatesConnectionsAndReportsAndProtectsPublicationState() throws Exception {
        Client a = registeredClient();
        Client b = registeredClient();
        Client anonymous = new Client();
        MvcResult connection = a.send("POST", "/api/connections",
                Map.of("name", "Agent", "url", "http://127.0.0.1:9000", "model", "test"));
        assertThat(connection.getResponse().getStatus()).isEqualTo(201);
        String connectionId = a.json(connection).path("id").asText();
        assertThat(b.json(b.send("GET", "/api/connections", null)).size()).isZero();
        assertThat(b.send("DELETE", "/api/connections/" + connectionId, null).getResponse().getStatus()).isEqualTo(404);
        JsonNode edited = a.json(a.send("PATCH", "/api/connections/" + connectionId,
                Map.of("name", "Edited Agent", "url", "http://127.0.0.1:9001", "model", "edited-model")));
        assertThat(edited.path("name").asText()).isEqualTo("Edited Agent");
        assertThat(edited.path("url").asText()).isEqualTo("http://127.0.0.1:9001");
        assertThat(edited.has("token")).isFalse();
        assertThat(a.send("POST", "/api/connections/" + connectionId + "/check", null)
                .getResponse().getStatus()).isEqualTo(502);
        assertThat(b.send("POST", "/api/connections/" + connectionId + "/check", null)
                .getResponse().getStatus()).isEqualTo(404);
        String id = "report-" + UUID.randomUUID();
        JsonNode report = a.json(a.send("POST", "/api/reports", Map.of("id", id, "scope", "full", "kind", "evaluation",
                "demo", true, "synced", true, "published", true, "ownerId", "fake")));
        // 客户端伪造的归属、同步和公开字段均不应成为服务端认可的状态。
        assertThat(report.path("synced").asBoolean()).isFalse();
        assertThat(report.path("published").asBoolean()).isFalse();
        assertThat(report.has("ownerId")).isFalse();
        assertThat(b.json(b.send("GET", "/api/bootstrap", null)).path("reports").size()).isZero();
        assertThat(b.send("GET", "/api/reports/" + id, null).getResponse().getStatus()).isEqualTo(404);
        assertThat(b.send("POST", "/api/reports", Map.of("id", id)).getResponse().getStatus()).isEqualTo(409);
        assertThat(b.send("POST", "/api/reports/" + id + "/sync", null).getResponse().getStatus()).isEqualTo(404);
        assertThat(a.send("POST", "/api/reports/" + id + "/publish", null).getResponse().getStatus()).isEqualTo(400);
        a.send("POST", "/api/reports/" + id + "/sync", null);
        assertThat(a.send("POST", "/api/reports/" + id + "/publish", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(anonymous.json(anonymous.send("GET", "/api/reports/" + id, null)).path("published").asBoolean()).isTrue();
        assertThat(a.send("POST", "/api/reports", Map.of("id", id)).getResponse().getStatus()).isEqualTo(409);
        assertThat(a.send("DELETE", "/api/connections/" + connectionId, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void publicReportFeedReturnsOnlyPublishedReportsForEveryIdentity() throws Exception {
        Client owner = registeredClient();
        Client other = registeredClient();
        String publicId = "public-feed-" + UUID.randomUUID();
        String privateId = "private-feed-" + UUID.randomUUID();
        owner.send("POST", "/api/reports", Map.of("id", publicId, "kind", "evaluation", "scope", "full", "demo", true));
        owner.send("POST", "/api/reports", Map.of("id", privateId, "kind", "evaluation", "scope", "full"));
        owner.send("POST", "/api/reports/" + publicId + "/sync", null);
        assertThat(owner.send("POST", "/api/reports/" + publicId + "/publish", null)
                .getResponse().getStatus()).isEqualTo(200);
        for (Client client : new Client[]{new Client(), owner, other}) {
            MvcResult response = client.send("GET", "/api/reports/public", null);
            assertThat(response.getResponse().getStatus()).isEqualTo(200);
            JsonNode feed = client.json(response);
            assertThat(feed.isArray()).isTrue();
            assertThat(feed.toString()).contains(publicId).doesNotContain(privateId);
            for (JsonNode record : feed) {
                assertThat(record.path("published").asBoolean()).isTrue();
                assertThat(record.has("ownerId")).isFalse();
            }
        }
        assertThat(other.json(other.send("GET", "/api/reports", null)).size()).isZero();
    }

    /**
     * 验证普通用户不能发布，管理员可发布，重复 ID 与版本被拒绝，公开目录能读取新版本。
     */
    @Test
    void onlyAdminsCanPublishImmutableDatasetVersions() throws Exception {
        Client researcher = registeredClient();
        Client admin = new Client();
        String id = "suite-" + UUID.randomUUID();
        Map<String, Object> dataset = Map.of("id", id, "version", "1.0.0", "tasks", new Object[]{Map.of("id", "task-1")});
        assertThat(researcher.send("POST", "/api/datasets/custom", dataset).getResponse().getStatus()).isEqualTo(403);
        assertThat(admin.send("POST", "/api/auth/demo", Map.of("role", "admin")).getResponse().getStatus()).isEqualTo(200);
        MvcResult published = admin.send("POST", "/api/datasets/custom", dataset);
        assertThat(published.getResponse().getStatus()).isEqualTo(201);
        assertThat(admin.json(published).path("tasks").asInt()).isEqualTo(1);
        assertThat(admin.json(published).path("taskPackage").size()).isEqualTo(1);
        assertThat(admin.send("POST", "/api/datasets/custom", dataset).getResponse().getStatus()).isEqualTo(409);
        assertThat(new Client().json(new Client().send("GET", "/api/bootstrap", null)).path("customDatasets").toString()).contains(id);
    }

    @Test
    void enforcesAdministratorAccountLifecycleAndAuditRules() throws Exception {
        Client researcher = registeredClient();
        assertThat(researcher.send("GET", "/api/admin/users", null).getResponse().getStatus()).isEqualTo(403);

        Client admin = new Client();
        assertThat(admin.send("POST", "/api/auth/demo", Map.of("role", "admin")).getResponse().getStatus()).isEqualTo(200);
        JsonNode accounts = admin.json(admin.send("GET", "/api/admin/users?size=100", null));
        assertThat(accounts.path("items").isArray()).isTrue();
        String researcherId = null;
        for (JsonNode item : accounts.path("items")) {
            if (researcherEmail(researcher).equals(item.path("email").asText())) {
                researcherId = item.path("id").asText();
                break;
            }
        }
        assertThat(researcherId).isNotBlank();

        String inviteEmail = "invited-" + UUID.randomUUID() + "@example.com";
        MvcResult invite = admin.send("POST", "/api/admin/invitations",
                Map.of("email", inviteEmail, "name", "受邀管理员"));
        assertThat(invite.getResponse().getStatus()).isEqualTo(202);
        assertThat(invite.getResponse().getContentAsString()).doesNotMatch("[a-f0-9]{64}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_admin_invitations WHERE email=? AND used_at IS NULL",
                Integer.class, inviteEmail)).isEqualTo(1);
        assertThat(admin.send("POST", "/api/admin/invitations",
                Map.of("email", inviteEmail, "name", "受邀管理员")).getResponse().getStatus()).isEqualTo(409);

        assertThat(admin.send("PATCH", "/api/admin/users/" + researcherId + "/role",
                Map.of("role", "admin")).getResponse().getStatus()).isEqualTo(200);
        assertThat(admin.send("PATCH", "/api/admin/users/" + researcherId + "/status",
                Map.of("status", "disabled")).getResponse().getStatus()).isEqualTo(200);
        assertThat(researcher.send("GET", "/api/connections", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(admin.send("PATCH", "/api/admin/users/" + researcherId + "/status",
                Map.of("status", "active")).getResponse().getStatus()).isEqualTo(200);
        assertThat(admin.send("PATCH", "/api/admin/users/" + researcherId + "/role",
                Map.of("role", "researcher")).getResponse().getStatus()).isEqualTo(200);
        assertThat(admin.send("GET", "/api/admin/audit", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(admin.send("POST", "/api/admin/users/does-not-exist/sessions/revoke", null)
                .getResponse().getStatus()).isEqualTo(404);
    }

    private String researcherEmail(Client client) {
        return client.email;
    }

    /**
     * 即使报告已同步，PK 报告和抽样测评仍不允许公开。
     */
    @Test
    void rejectsPkAndPartialReportPublication() throws Exception {
        Client client = registeredClient();
        String unverified = "unverified-" + UUID.randomUUID();
        client.send("POST", "/api/reports", Map.of("id", unverified, "kind", "evaluation", "scope", "full"));
        client.send("POST", "/api/reports/" + unverified + "/sync", null);
        assertThat(client.send("POST", "/api/reports/" + unverified + "/publish", null).getResponse().getStatus()).isEqualTo(400);
        for (String kind : new String[]{"pk", "sample"}) {
            String id = "restricted-" + UUID.randomUUID();
            client.send("POST", "/api/reports", Map.of("id", id, "kind", kind.equals("pk") ? "pk" : "evaluation",
                    "scope", kind.equals("sample") ? "sample" : "full"));
            client.send("POST", "/api/reports/" + id + "/sync", null);
            assertThat(client.send("POST", "/api/reports/" + id + "/publish", null).getResponse().getStatus()).isEqualTo(400);
        }
    }

    /**
     * 直接构造请求验证缺少 CSRF 时返回 403、请求体超过 5,000,000 字节时返回 413。
     */
    @Test
    void rejectsWritesWithoutCsrfAndOversizedRequests() throws Exception {
        assertThat(mvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                .contentType("application/json").content("{}" )).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                .contentType("application/json").content("x".repeat(5_000_001)))
                .andReturn().getResponse().getStatus()).isEqualTo(413);
    }

    /**
     * 创建随机邮箱的研究者并保留登录 Cookie，供需要身份认证的测试复用。
     */
    private Client registeredClient() throws Exception {
        Client client = new Client();
        assertThat(client.send("POST", "/api/auth/register", Map.of("name", "Test", "email", client.email,
                "password", "test-password")).getResponse().getStatus()).isEqualTo(201);
        return client;
    }

    // 轻量测试客户端：每个实例有独立 Cookie 存储，避免不同用户的会话混用。
    private class Client {
        private final Map<String, Cookie> cookies = new HashMap<>();
        private final String email = UUID.randomUUID() + "@example.com";

        // 模拟浏览器发送 JSON；非 GET 请求先获取 CSRF 令牌，并接收响应中的新 Cookie。
        MvcResult send(String method, String path, Object body) throws Exception {
            var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
            if (!"GET".equals(method)) {
                JsonNode csrf = json(send("GET", "/api/auth/csrf", null));
                request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
            }
            if (!cookies.isEmpty()) {
                request.cookie(cookies.values().toArray(Cookie[]::new));
            }
            if (body != null) {
                request.contentType("application/json").content(mapper.writeValueAsBytes(body));
            }
            MvcResult result = mvc.perform(request).andReturn();
            for (Cookie cookie : result.getResponse().getCookies()) {
                cookies.put(cookie.getName(), cookie);
            }
            return result;
        }

        // 从响应字节解析 JSON，避免中文内容受默认字符集影响。
        JsonNode json(MvcResult result) throws Exception {
            return mapper.readTree(result.getResponse().getContentAsByteArray());
        }
    }
}

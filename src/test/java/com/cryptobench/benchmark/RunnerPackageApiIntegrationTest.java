package com.cryptobench.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "benchmark.runner-package.directory=target/runner-package-api-test",
        "benchmark.mail.mode=outbox",
        "benchmark.mail.outbox=target/test-mail/runner-package-api.jsonl",
        "benchmark.mail.worker-enabled=false",
        "benchmark.mail.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerPackageApiIntegrationTest {
    private static final Path DIRECTORY = Path.of("target", "runner-package-api-test");
    private static final byte[] ARCHIVE = "portable-runner-archive".getBytes(StandardCharsets.UTF_8);
    private static final String FILENAME = "CryptoBench-Runner-windows-x64-1.1.0.zip";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeAll
    static void installPackageFixture() throws Exception {
        deleteRecursively(DIRECTORY);
        Files.createDirectories(DIRECTORY);
        Files.write(DIRECTORY.resolve(FILENAME), ARCHIVE);
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(ARCHIVE));
        Files.writeString(DIRECTORY.resolve("runner-package-manifest.json"), """
                {
                  "schema": "cryptobench-runner-package-v1",
                  "runnerVersion": "1.1.0",
                  "platform": "windows-x64",
                  "nodeVersion": "24.22.0",
                  "sourceCommit": "abcdef",
                  "filename": "%s",
                  "bytes": %d,
                  "sha256": "%s",
                  "manifestPath": "/api/runners/package",
                  "downloadPath": "/api/runners/download"
                }
                """.formatted(FILENAME, ARCHIVE.length, hash));
    }

    @AfterAll
    static void removePackageFixture() {
        deleteRecursively(DIRECTORY);
    }

    @Test
    void authenticatedBrowserCanReadManifestAndDownloadArchive() throws Exception {
        Client anonymous = new Client();
        assertThat(anonymous.send("GET", "/api/runners/package", null).getResponse().getStatus()).isEqualTo(401);

        Client client = new Client();
        String email = "runner-package-" + UUID.randomUUID() + "@example.com";
        assertThat(client.send("POST", "/api/auth/register",
                Map.of("name", "Runner Package", "email", email, "password", "test-password"))
                .getResponse().getStatus()).isEqualTo(201);

        MvcResult manifestResult = client.send("GET", "/api/runners/package", null);
        assertThat(manifestResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode manifest = client.json(manifestResult);
        assertThat(manifest.path("runnerVersion").asText()).isEqualTo("1.1.0");
        assertThat(manifest.path("filename").asText()).isEqualTo(FILENAME);

        MvcResult download = client.send("GET", "/api/runners/download", null);
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getContentType()).isEqualTo("application/zip");
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(ARCHIVE);
        assertThat(download.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains(FILENAME);
        assertThat(download.getResponse().getHeader(HttpHeaders.ETAG)).hasSize(66);
    }

    private class Client {
        private final Map<String, Cookie> cookies = new HashMap<>();

        MvcResult send(String method, String path, Object body) throws Exception {
            var request = MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(method), path);
            if (!"GET".equals(method)) {
                JsonNode csrf = json(send("GET", "/api/auth/csrf", null));
                request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
            }
            if (!cookies.isEmpty()) request.cookie(cookies.values().toArray(Cookie[]::new));
            if (body != null) {
                request.contentType("application/json")
                        .content(mapper.writeValueAsBytes(body));
            }
            MvcResult result = mvc.perform(request).andReturn();
            for (Cookie cookie : result.getResponse().getCookies()) {
                cookies.put(cookie.getName(), cookie);
            }
            return result;
        }

        JsonNode json(MvcResult result) throws Exception {
            return mapper.readTree(result.getResponse().getContentAsByteArray());
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(item -> {
                        try {
                            Files.deleteIfExists(item);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }
}

package com.cryptobench.benchmark.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** 受控调用本机 Agent 协议；凭证只在方法参数中存在，不写入日志或数据库。 */
@Component
public class AgentHttpClient {
    private final ObjectMapper mapper;
    private final HttpClient client;

    public AgentHttpClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public ObjectNode check(String endpoint, String token) throws IOException, InterruptedException {
        return get(endpoint, "/health", token);
    }

    /** Call an endpoint created by the server's Docker sandbox proxy. */
    public ObjectNode checkSandbox(String endpoint) throws IOException, InterruptedException {
        return get(endpoint, "/health", null, true);
    }

    public ObjectNode capabilities(String endpoint, String token) throws IOException, InterruptedException {
        return get(endpoint, "/capabilities", token);
    }

    /** Call an endpoint created by the server's Docker sandbox proxy. */
    public ObjectNode capabilitiesSandbox(String endpoint) throws IOException, InterruptedException {
        return get(endpoint, "/capabilities", null, true);
    }

    public ObjectNode analyze(String endpoint, String token, ObjectNode request, Duration timeout)
            throws IOException, InterruptedException {
        return analyze(endpoint, token, request, timeout, false);
    }

    /** Analyze through an endpoint created by the server's Docker sandbox proxy. */
    public ObjectNode analyzeSandbox(String endpoint, ObjectNode request, Duration timeout)
            throws IOException, InterruptedException {
        return analyze(endpoint, null, request, timeout, true);
    }

    private ObjectNode analyze(String endpoint, String token, ObjectNode request, Duration timeout,
                               boolean allowNonLoopback) throws IOException, InterruptedException {
        URI uri = URI.create(normalize(endpoint, allowNonLoopback) + "/analyze");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Agent analyze returned HTTP " + response.statusCode());
        }
        JsonNodeLimit.ensure(response.body());
        return object(response.body());
    }

    public static String normalize(String endpoint) {
        return normalize(endpoint, false);
    }

    private static String normalize(String endpoint, boolean allowNonLoopback) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Agent endpoint 格式无效");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Agent endpoint must use http or https");
        }
        if (uri.getHost() == null || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Agent endpoint 格式无效");
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Agent endpoint must not contain credentials or query parameters");
        }
        try {
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (!allowNonLoopback && !address.isLoopbackAddress()) {
                throw new IllegalArgumentException("本地执行只允许连接回环地址");
            }
        } catch (IOException | SecurityException exception) {
            throw new IllegalArgumentException("无法解析 Agent 地址");
        }
        return value;
    }

    private ObjectNode get(String endpoint, String path, String token) throws IOException, InterruptedException {
        return get(endpoint, path, token, false);
    }

    private ObjectNode get(String endpoint, String path, String token, boolean allowNonLoopback)
            throws IOException, InterruptedException {
        URI uri = URI.create(normalize(endpoint, allowNonLoopback) + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET();
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Agent returned HTTP " + response.statusCode());
        }
        JsonNodeLimit.ensure(response.body());
        return object(response.body());
    }

    private ObjectNode object(String body) throws IOException {
        var node = mapper.readTree(body);
        if (node == null || !node.isObject()) throw new IOException("Agent response must be a JSON object");
        return (ObjectNode) node;
    }

    /** 防止 Agent 把超大响应写入运行数据库。 */
    private static final class JsonNodeLimit {
        static void ensure(String body) throws IOException {
            if (body == null || body.length() > 2_000_000) throw new IOException("Agent response is too large");
        }
    }
}

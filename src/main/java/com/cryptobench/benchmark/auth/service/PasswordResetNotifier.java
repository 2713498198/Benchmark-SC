package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 密码找回的可替换投递适配器。生产环境可配置一个只接受内网请求的邮件网关；
 * 本地联调可配置 outbox 文件读取邮件内容，不把令牌放入 API 响应。
 */
@Component
public class PasswordResetNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetNotifier.class);
    private final ObjectMapper mapper;
    private final String deliveryUrl;
    private final String outbox;
    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public PasswordResetNotifier(ObjectMapper mapper,
                                 @Value("${benchmark.password-reset.delivery-url:}") String deliveryUrl,
                                 @Value("${benchmark.password-reset.outbox:}") String outbox,
                                 @Value("${benchmark.password-reset.base-url:http://127.0.0.1:4173}") String baseUrl) {
        this.mapper = mapper;
        this.deliveryUrl = deliveryUrl == null ? "" : deliveryUrl.trim();
        this.outbox = outbox == null ? "" : outbox.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
    }

    public void send(BenchmarkUser user, String token, Instant expiresAt) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("email", user.email());
        payload.put("resetUrl", baseUrl + "/#/reset-password?token=" + token);
        payload.put("token", token);
        payload.put("expiresAt", expiresAt.toString());
        String body;
        try {
            body = mapper.writeValueAsString(payload);
            if (!deliveryUrl.isBlank()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(deliveryUrl)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("reset mail gateway returned HTTP " + response.statusCode());
                }
            } else if (!outbox.isBlank()) {
                Path file = Path.of(outbox);
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, body + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                LOG.info("Password reset mail queued for {} (configure PASSWORD_RESET_DELIVERY_URL or PASSWORD_RESET_OUTBOX)",
                        user.email());
            }
        } catch (Exception exception) {
            // 忘记密码请求仍返回统一成功，避免邮件系统故障泄露账号存在性。
            LOG.warn("Password reset delivery failed for {}", user.email());
        }
    }

    /** Sends an admin invitation through the same configured gateway or local outbox. */
    public void sendAdminInvite(String email, String name, String token, Instant expiresAt) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "admin_invite");
        payload.put("email", email);
        payload.put("name", name);
        payload.put("inviteUrl", baseUrl + "/#/admin-invite?token=" + token);
        payload.put("token", token);
        payload.put("expiresAt", expiresAt.toString());
        sendPayload(payload, email, "Admin invitation mail queued for {}", "Admin invitation delivery failed for {}");
    }

    private void sendPayload(ObjectNode payload, String email, String queuedMessage, String failureMessage) {
        try {
            String body = mapper.writeValueAsString(payload);
            if (!deliveryUrl.isBlank()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(deliveryUrl)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("mail gateway returned HTTP " + response.statusCode());
                }
            } else if (!outbox.isBlank()) {
                Path file = Path.of(outbox);
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, body + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                LOG.info(queuedMessage, email);
            }
        } catch (Exception exception) {
            LOG.warn(failureMessage, email);
        }
    }
}

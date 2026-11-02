package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.mail.MailDeliveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Builds messages; delivery happens after the enclosing transaction commits. */
@Component
public class PasswordResetNotifier {
    private final ObjectMapper mapper;
    private final MailDeliveryService delivery;
    public PasswordResetNotifier(ObjectMapper mapper, MailDeliveryService delivery) { this.mapper = mapper; this.delivery = delivery; }
    public boolean ready() { return delivery.status().ready(); }
    public void requireReady() { delivery.requireReady(); }
    public MailDeliveryService.Status status() { return delivery.status(); }
    public String deliveryMode() { return delivery.status().mode(); }
    public MailDeliveryService.DeliveryView delivery(String messageId) { return delivery.delivery(messageId); }
    public void guard() { delivery.guard(); }
    public void cancelInvitation(String id) { delivery.cancelInvitation(id); }
    public void cancelResets(String userId) { delivery.cancelResets(userId); }
    public String sendCode(String email, String challengeId, String code, Instant expiresAt, String userId) {
        var payload = mapper.createObjectNode();
        payload.put("type", "password_reset_code"); payload.put("email", email);
        payload.put("code", code); payload.put("expiresAt", expiresAt.toString());
        return delivery.enqueue("password_reset_code", challengeId, userId, 1, payload, expiresAt.toEpochMilli());
    }
    public void send(BenchmarkUser user, String token, Instant expiresAt) {
        var payload = mapper.createObjectNode();
        payload.put("type", "password_reset"); payload.put("email", user.email());
        payload.put("resetUrl", delivery.baseUrl() + "/#/reset-password?token=" + token);
        payload.put("token", token); payload.put("expiresAt", expiresAt.toString());
        delivery.enqueue("password_reset", hash(token), user.id(), 1, payload, expiresAt.toEpochMilli());
    }
    public String sendAdminInvite(String id, int generation, String email, String name, String token, Instant expiresAt) {
        var payload = mapper.createObjectNode();
        payload.put("type", "admin_invite"); payload.put("email", email); payload.put("name", name);
        payload.put("inviteUrl", delivery.baseUrl() + "/#/admin-invite?token=" + token);
        payload.put("token", token); payload.put("expiresAt", expiresAt.toString());
        return delivery.enqueue("admin_invite", id, null, generation, payload, expiresAt.toEpochMilli());
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}

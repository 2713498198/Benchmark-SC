package com.cryptobench.benchmark.mail;

import com.cryptobench.benchmark.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailDeliveryService {
    private final MailRepository messages;
    private final MailCipher cipher;
    private final MailSettings settings;
    private final ObjectMapper mapper;
    public MailDeliveryService(MailRepository messages, MailCipher cipher, MailSettings settings, ObjectMapper mapper) {
        this.messages = messages; this.cipher = cipher; this.settings = settings; this.mapper = mapper;
    }
    public Status status() {
        String error = settings.configurationError();
        if (error == null) { try { cipher.checkReady(); } catch (IllegalStateException e) { error = "mail_key_unavailable"; } }
        return new Status(settings.mode(), error == null, error, "provider_accepted 表示邮件服务已接收，不代表收件人已收到");
    }
    public void requireReady() {
        Status status = status();
        if (!status.ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "邮件功能尚未就绪（" + status.errorCode() + "），请检查邮件通道和加密密钥配置");
    }
    @Transactional
    public String enqueue(String purpose, String reference, String owner, int generation, ObjectNode payload, long expiresAt) {
        requireReady();
        String id = "mail_" + UUID.randomUUID();
        try {
            var encrypted = cipher.encrypt(id, purpose, reference, mapper.writeValueAsString(payload));
            messages.insert(id, purpose, reference, owner, generation, settings.mode(), encrypted, expiresAt, System.currentTimeMillis());
            return id;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("mail_payload_invalid"); }
    }
    public DeliveryView delivery(String id) {
        return messages.find(id).map(message -> new DeliveryView(message.id(), message.transport(), message.state(), message.attempts(),
                "retry_wait".equals(message.state()) || "queued".equals(message.state()) ? Instant.ofEpochMilli(message.nextAttemptAt()).toString() : null,
                message.errorCode())).orElse(new DeliveryView(null, null, "legacy_unknown", 0, null, null));
    }
    public String baseUrl() { return settings.baseUrl(); }
    public void guard() { messages.guard(); }
    public void cancelInvitation(String id) { messages.cancelReference("admin_invite", id, System.currentTimeMillis()); }
    public void cancelResets(String userId) { messages.cancelOwner(userId, System.currentTimeMillis()); }
    public record Status(String mode, boolean ready, String errorCode, String explanation) {}
    public record DeliveryView(String messageId, String transport, String state, int attempts, String nextAttemptAt, String errorCode) {}
}

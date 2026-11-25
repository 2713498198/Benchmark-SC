package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.mail.MailDeliveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 构造密码找回验证码邮件，事务提交后再交给投递服务发送。 */
@Component
public class PasswordResetNotifier {
    private final ObjectMapper mapper;
    private final MailDeliveryService delivery;
    public PasswordResetNotifier(ObjectMapper mapper, MailDeliveryService delivery) { this.mapper = mapper; this.delivery = delivery; }
    public boolean ready() { return delivery.status().ready(); }
    public void guard() { delivery.guard(); }
    public void cancelResets(String userId) { delivery.cancelResets(userId); }
    public String sendCode(String email, String challengeId, String code, Instant expiresAt, String userId) {
        var payload = mapper.createObjectNode();
        payload.put("type", "password_reset_code"); payload.put("email", email);
        payload.put("code", code); payload.put("expiresAt", expiresAt.toString());
        return delivery.enqueue("password_reset_code", challengeId, userId, 1, payload, expiresAt.toEpochMilli());
    }
}

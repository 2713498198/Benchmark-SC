package com.cryptobench.benchmark.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A database lease protects each bounded attempt; network calls never hold business transactions. */
@Component
public class MailDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(MailDispatcher.class);
    private static final long[] RETRY_DELAYS = {60_000L, 300_000L, 900_000L, 1_800_000L};
    private final MailProperties properties; private final MailRepository messages;
    private final MailCipher cipher; private final MailTransport transport; private final ObjectMapper mapper;
    public MailDispatcher(MailProperties properties, MailRepository messages, MailCipher cipher, MailTransport transport, ObjectMapper mapper) {
        this.properties = properties; this.messages = messages; this.cipher = cipher; this.transport = transport; this.mapper = mapper;
    }
    @Scheduled(fixedDelayString = "${benchmark.mail.poll-ms:5000}", initialDelayString = "${benchmark.mail.poll-ms:5000}")
    public void scheduled() {
        if (!properties.isWorkerEnabled()) return;
        try { dispatchDue(); } catch (Exception e) { LOG.warn("Mail queue processing temporarily unavailable"); }
    }
    public void dispatchDue() {
        long now = System.currentTimeMillis(); messages.expire(now);
        for (var candidate : messages.due(now)) messages.claim(candidate.id(), System.currentTimeMillis()).ifPresent(this::dispatch);
    }
    private void dispatch(MailRepository.Message message) {
        long now = System.currentTimeMillis();
        if (!messages.validReference(message, now)) { messages.finish(message, "cancelled", null, now); return; }
        ObjectNode payload;
        try { payload = (ObjectNode) mapper.readTree(cipher.decrypt(message.id(), message.purpose(), message.reference(), message.encrypted())); }
        catch (Exception e) { messages.finish(message, "failed", "mail_decryption_failed", now); return; }
        try {
            String result = transport.send(message.transport(), message.id(), payload);
            // Operational evidence only: never log recipients, message bodies, codes or credentials.
            LOG.info("Mail transport result: id={}, transport={}, result={}", message.id(), message.transport(), result);
            long finished = System.currentTimeMillis();
            messages.finish(message, message.expiresAt() <= finished ? "expired" : result, null, finished);
        } catch (MailTransport.DeliveryException e) {
            LOG.warn("Mail transport failure: id={}, transport={}, code={}", message.id(), message.transport(), e.code());
            long failed = System.currentTimeMillis();
            long next = failed + RETRY_DELAYS[Math.min(RETRY_DELAYS.length - 1, Math.max(0, message.attempts() - 1))];
            if (e.retryable() && message.attempts() < 5 && next < message.expiresAt()) messages.retry(message, e.code(), next);
            else messages.finish(message, message.expiresAt() <= failed ? "expired" : "failed", e.code(), failed);
        }
    }
    @Configuration
    @EnableScheduling
    static class Scheduling {}
}

package com.cryptobench.benchmark.mail;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.service.PasswordResetNotifier;
import com.cryptobench.benchmark.auth.service.UserAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mail-delivery-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "benchmark.mail.mode=outbox", "benchmark.mail.worker-enabled=false",
        "benchmark.mail.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@ActiveProfiles("test")
class MailDeliveryIntegrationTest {
    @Autowired PasswordResetNotifier notifier;
    @Autowired UserAccountService users;
    @Autowired MailProperties properties;
    @Autowired MailRepository messages;
    @Autowired MailDispatcher dispatcher;
    @Autowired MailCipher cipher;
    @Autowired MailDeliveryService delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    Path temp;
    BenchmarkUser actor;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("DELETE FROM benchmark_mail_messages");
        temp = Files.createTempDirectory(Path.of("target"), "mail-delivery-");
        properties.setMode("outbox");
        properties.setOutbox(temp.resolve("outbox.jsonl").toString());
        actor = new BenchmarkUser("mail_user_" + UUID.randomUUID(), UUID.randomUUID() + "@example.com",
                "Mail test", "researcher", "unused", null, Instant.now().toString());
        users.insert(actor);
    }

    @Test
    void persistsOnlyEncryptedVerificationCodeMailAndWritesExplicitOutbox() throws Exception {
        String id = codeMessage();
        var message = messages.find(id).orElseThrow();
        assertThat(message.encrypted().ciphertext()).doesNotContain("003917", actor.email());
        assertThat(messages.validReference(message, System.currentTimeMillis())).isTrue();
        dispatcher.dispatchDue();
        var payload = mapper.readTree(Files.readString(Path.of(properties.getOutbox())).trim());
        assertThat(payload.path("type").asText()).isEqualTo("password_reset_code");
        assertThat(payload.path("code").asText()).isEqualTo("003917");
        assertThat(payload.has("resetUrl")).isFalse();
        assertThat(payload.has("token")).isFalse();
        assertThat(delivery.delivery(id).state()).isEqualTo("local_outbox_written");
        assertCleared(id);
    }

    @Test
    void gatewayRetryPreservesCodeAndExpiryThenStopsBeforeExpiry() throws Exception {
        var received = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mail", exchange -> {
            received.add(mapper.readTree(exchange.getRequestBody()));
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                    .isEqualTo(received.getLast().path("messageId").asText());
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        properties.setMode("http");
        properties.setDeliveryUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mail");
        try {
            String id = codeMessage();
            dispatcher.dispatchDue();
            assertThat(delivery.delivery(id).state()).isEqualTo("retry_wait");
            due(id);
            dispatcher.dispatchDue();
            assertThat(delivery.delivery(id).state()).isEqualTo("retry_wait");
            due(id);
            dispatcher.dispatchDue();
            assertThat(delivery.delivery(id).state()).isEqualTo("failed");
            assertThat(received).hasSize(3);
            assertThat(received.get(0)).isEqualTo(received.get(1)).isEqualTo(received.get(2));
            assertThat(received.getFirst().path("code").asText()).isEqualTo("003917");
            assertThat(received.getFirst().path("type").asText()).isEqualTo("password_reset_code");
            assertCleared(id);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidExpiredAndUnknownMessagesNeverLeaveQueue() {
        String cancelled = codeMessage();
        jdbc.update("UPDATE benchmark_password_reset_challenges SET status='invalidated' WHERE id=?",
                messages.find(cancelled).orElseThrow().reference());
        dispatcher.dispatchDue();
        assertThat(delivery.delivery(cancelled).state()).isEqualTo("cancelled");
        assertCleared(cancelled);

        String expired = codeMessage();
        jdbc.update("UPDATE benchmark_mail_messages SET expires_at=0 WHERE id=?", expired);
        dispatcher.dispatchDue();
        assertThat(delivery.delivery(expired).state()).isEqualTo("expired");
        assertCleared(expired);

        String unknown = delivery.enqueue("unknown_purpose", "fixture", actor.id(), 1,
                mapper.createObjectNode(), System.currentTimeMillis() + 60_000);
        dispatcher.dispatchDue();
        assertThat(delivery.delivery(unknown).state()).isEqualTo("cancelled");
        assertCleared(unknown);
        assertThat(Path.of(properties.getOutbox())).doesNotExist();
    }

    @Test
    void retriesGatewayErrorsAndLabelsAcceptanceAccurately() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = gateway(attempts, 503, 202);
        try {
            String message = codeMessage();
            long before = System.currentTimeMillis();
            dispatcher.dispatchDue();
            assertThat(delivery.delivery(message).state()).isEqualTo("retry_wait");
            assertThat(messages.find(message).orElseThrow().nextAttemptAt())
                    .isBetween(before + 60_000L, System.currentTimeMillis() + 60_000L);
            due(message);
            dispatcher.dispatchDue();
            assertThat(delivery.delivery(message).state()).isEqualTo("provider_accepted");
            assertThat(delivery.delivery(message).attempts()).isEqualTo(2);
            assertCleared(message);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recoversAnExpiredWorkerLeaseAndStopsAtFiveAttempts() throws Exception {
        HttpServer server = gateway(new AtomicInteger(), 503, 503);
        try {
            String message = codeMessage();
            assertThat(messages.claim(message, System.currentTimeMillis())).isPresent();
            assertThat(messages.claim(message, System.currentTimeMillis())).isEmpty();
            jdbc.update("UPDATE benchmark_mail_messages SET attempts=4,state='dispatching',lease_until=0,next_attempt_at=0 WHERE id=?", message);
            dispatcher.dispatchDue();
            assertThat(delivery.delivery(message).state()).isEqualTo("failed");
            assertThat(delivery.delivery(message).attempts()).isEqualTo(5);
            assertCleared(message);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void permanentGatewayFailuresDoNotRetryAndExpiredPayloadsArePurged() throws Exception {
        HttpServer server = gateway(new AtomicInteger(), 400, 400);
        try {
            String failed = codeMessage();
            dispatcher.dispatchDue();
            assertThat(delivery.delivery(failed).state()).isEqualTo("failed");
            assertThat(delivery.delivery(failed).attempts()).isEqualTo(1);
            String expired = codeMessage();
            jdbc.update("UPDATE benchmark_mail_messages SET expires_at=0 WHERE id=?", expired);
            dispatcher.dispatchDue();
            assertThat(delivery.delivery(expired).state()).isEqualTo("expired");
            assertCleared(expired);
        } finally {
            server.stop(0);
        }
    }

    private String codeMessage() {
        String challenge = "challenge_" + UUID.randomUUID();
        long now = System.currentTimeMillis(), expires = now + 600_000;
        var digest = cipher.codeDigest(challenge, actor.id(), "003917");
        jdbc.update("""
                INSERT INTO benchmark_password_reset_challenges
                (id,user_id,email_key,code_digest,code_key_id,status,failed_attempts,created_at,expires_at)
                VALUES(?,?,?,?,?,'active',0,?,?)
                """, challenge, actor.id(), "a".repeat(64), digest.value(), digest.keyId(), now, expires);
        return notifier.sendCode(actor.email(), challenge, "003917", Instant.ofEpochMilli(expires), actor.id());
    }

    private void due(String id) {
        jdbc.update("UPDATE benchmark_mail_messages SET next_attempt_at=0 WHERE id=?", id);
    }

    private void assertCleared(String id) {
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM benchmark_mail_messages WHERE id=?", String.class, id)).isNull();
    }

    private HttpServer gateway(AtomicInteger attempts, int first, int following) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mail", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).startsWith("mail_");
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(attempts.incrementAndGet() == 1 ? first : following, -1);
            exchange.close();
        });
        server.start();
        properties.setMode("http");
        properties.setDeliveryUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mail");
        return server;
    }
}

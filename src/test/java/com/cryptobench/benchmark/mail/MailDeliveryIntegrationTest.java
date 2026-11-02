package com.cryptobench.benchmark.mail;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.AdminInvitationRepository;
import com.cryptobench.benchmark.auth.repository.PasswordResetRepository;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.auth.service.AdminAccountService;
import com.cryptobench.benchmark.auth.service.PasswordResetService;
import com.cryptobench.benchmark.auth.service.PasswordResetNotifier;
import com.cryptobench.benchmark.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:mail-delivery-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "benchmark.mail.mode=outbox", "benchmark.mail.worker-enabled=false", "benchmark.mail.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("test")
class MailDeliveryIntegrationTest {
    @Autowired AdminAccountService admins;
    @Autowired PasswordResetService resets;
    @Autowired PasswordResetNotifier notifier;
    @Autowired AdminInvitationRepository invitations;
    @Autowired PasswordResetRepository resetTokens;
    @Autowired UserRepository users;
    @Autowired MailProperties properties;
    @Autowired MailRepository messages;
    @Autowired MailDispatcher dispatcher;
    @Autowired MailCipher cipher;
    @Autowired MailDeliveryService delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temp;
    BenchmarkUser actor;

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM benchmark_mail_messages");
        jdbc.update("DELETE FROM benchmark_admin_invitations");
        properties.setMode("outbox"); properties.setOutbox(temp.resolve("outbox.jsonl").toString());
        actor = user("admin");
    }

    @Test void persistsCiphertextAndWritesOnlyAnExplicitLocalOutbox() throws Exception {
        var invite = invite();
        String token = token(invite.deliveryStatus().messageId());
        String stored = jdbc.queryForObject("SELECT ciphertext FROM benchmark_mail_messages WHERE id=?", String.class, invite.deliveryStatus().messageId());
        assertThat(stored).doesNotContain(token).doesNotContain("example.com");
        assertThat(mapper.writeValueAsString(invite)).doesNotContain(token);
        assertThat(invite.status()).isEqualTo("queued");
        dispatcher.dispatchDue();
        assertThat(delivery.delivery(invite.deliveryStatus().messageId()).state()).isEqualTo("local_outbox_written");
        assertThat(Files.readString(Path.of(properties.getOutbox()))).contains(token).contains("messageId");
        assertCleared(invite.deliveryStatus().messageId());
        assertThat(admins.invitations("pending", "local_outbox_written", 0, 1).items()).hasSize(1);
    }

    @Test void codePayloadUsesEncryptedQueueThenExplicitLocalOutbox() throws Exception {
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

    @Test void codeGatewayRetryPreservesCodeAndExpiryThenStopsBeforeExpiry() throws Exception {
        var received = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mail", exchange -> {
            received.add(mapper.readTree(exchange.getRequestBody()));
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo(received.getLast().path("messageId").asText());
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.start(); properties.setMode("http"); properties.setDeliveryUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mail");
        try {
            String id = codeMessage();
            dispatcher.dispatchDue(); assertThat(delivery.delivery(id).state()).isEqualTo("retry_wait");
            due(id); dispatcher.dispatchDue(); assertThat(delivery.delivery(id).state()).isEqualTo("retry_wait");
            due(id); dispatcher.dispatchDue(); assertThat(delivery.delivery(id).state()).isEqualTo("failed");
            assertThat(received).hasSize(3);
            assertThat(received.get(0)).isEqualTo(received.get(1)).isEqualTo(received.get(2));
            assertThat(received.getFirst().path("code").asText()).isEqualTo("003917");
            assertThat(received.getFirst().path("type").asText()).isEqualTo("password_reset_code");
            assertCleared(id);
        } finally { server.stop(0); }
    }

    @Test void invalidAndExpiredCodeMailNeverLeavesQueueAndUnknownPurposeFailsClosed() {
        String cancelled = codeMessage();
        jdbc.update("UPDATE benchmark_password_reset_challenges SET status='invalidated' WHERE id=?", messages.find(cancelled).orElseThrow().reference());
        dispatcher.dispatchDue(); assertThat(delivery.delivery(cancelled).state()).isEqualTo("cancelled"); assertCleared(cancelled);
        String expired = codeMessage();
        jdbc.update("UPDATE benchmark_mail_messages SET expires_at=0 WHERE id=?", expired);
        dispatcher.dispatchDue(); assertThat(delivery.delivery(expired).state()).isEqualTo("expired"); assertCleared(expired);
        String unknown = delivery.enqueue("unknown_purpose", "fixture", actor.id(), 1, mapper.createObjectNode(), System.currentTimeMillis()+60_000);
        dispatcher.dispatchDue(); assertThat(delivery.delivery(unknown).state()).isEqualTo("cancelled"); assertCleared(unknown);
        assertThat(Path.of(properties.getOutbox())).doesNotExist();
    }

    @Test void disabledDeliveryDoesNotLeaveAnInvitationOrResetToken() {
        properties.setMode("disabled");
        assertThatThrownBy(this::invite).isInstanceOf(ApiException.class).hasMessageContaining("尚未就绪");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_admin_invitations", Integer.class)).isZero();
        resets.request(actor.email());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_tokens WHERE user_id=?", Integer.class, actor.id())).isZero();
    }

    @Test void resendRotatesTheLinkAndRevokeCancelsQueuedMail() {
        var invite = invite(); String original = token(invite.deliveryStatus().messageId());
        var resent = admins.resendInvitation(actor, invite.id()); String replacement = token(resent.deliveryStatus().messageId());
        assertThat(replacement).isNotEqualTo(original);
        assertThat(delivery.delivery(invite.deliveryStatus().messageId()).state()).isEqualTo("cancelled");
        assertThatThrownBy(() -> admins.acceptInvitation(original, "password8")).hasMessageContaining("无效或已过期");
        assertThat(admins.revokeInvitation(actor, invite.id()).status()).isEqualTo("revoked");
        assertThat(admins.revokeInvitation(actor, invite.id()).status()).isEqualTo("revoked");
        assertThatThrownBy(() -> admins.acceptInvitation(replacement, "password8")).hasMessageContaining("无效或已过期");
        assertThatThrownBy(() -> admins.resendInvitation(actor, invite.id())).hasMessageContaining("不能重发");
        assertCleared(resent.deliveryStatus().messageId());
    }

    @Test void acceptsOnlyOneInvitationAndKeepsEightToTwentyFourCharacterPolicy() {
        var invite = invite(); String raw = token(invite.deliveryStatus().messageId());
        assertThatThrownBy(() -> admins.acceptInvitation(raw, "1".repeat(7))).hasMessageContaining("格式无效");
        assertThatThrownBy(() -> admins.acceptInvitation(raw, "1".repeat(25))).hasMessageContaining("格式无效");
        BenchmarkUser created = admins.acceptInvitation(raw, "1".repeat(8));
        assertThat(created.role()).isEqualTo("admin");
        assertThatThrownBy(() -> admins.acceptInvitation(raw, "1".repeat(8))).hasMessageContaining("无效或已过期");
        assertThat(admins.invitations("accepted", null, 0, 20).items()).hasSize(1);
        var second = invite();
        assertThat(admins.acceptInvitation(token(second.deliveryStatus().messageId()), "1".repeat(24)).role()).isEqualTo("admin");
    }

    @Test void oldInvitationAndResetLinksWithoutQueueMetadataStillWork() throws Exception {
        String old = "b".repeat(64), id = "invite_" + UUID.randomUUID();
        invitations.insert(id, UUID.randomUUID() + "@example.com", "Old invitation", hash(old), actor.id(), Instant.now().toString(), System.currentTimeMillis() + 60_000);
        assertThat(admins.invitations("pending", "legacy_unknown", 0, 20).items()).hasSize(1);
        assertThat(admins.acceptInvitation(old, "password8").role()).isEqualTo("admin");
        resetTokens.insert(hash(old), actor.id(), System.currentTimeMillis() + 60_000, Instant.now().toString());
        resets.reset(old, "password8");
        assertThat(resetTokens.find(hash(old)).orElseThrow().usedAt()).isNotNull();
    }

    @Test void newPasswordResetCancelsOldQueuedLinkAndConsumptionCancelsTheNewMessage() {
        resets.request(actor.email());
        var first = resetMessage(); String old = token(first.id());
        resets.request(actor.email());
        var second = resetMessage(); String fresh = token(second.id());
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(delivery.delivery(first.id()).state()).isEqualTo("cancelled");
        assertThatThrownBy(() -> resets.reset(old, "password8")).hasMessageContaining("无效或已过期");
        assertThatThrownBy(() -> resets.reset(fresh, "1".repeat(7))).hasMessageContaining("格式无效");
        assertThatThrownBy(() -> resets.reset(fresh, "1".repeat(25))).hasMessageContaining("格式无效");
        resets.reset(fresh, "1".repeat(24));
        assertThat(delivery.delivery(second.id()).state()).isEqualTo("cancelled");
        assertCleared(second.id());
    }

    @Test void transactionRollbackDoesNotLeaveDeliverableMessages() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            invite(); throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_mail_messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_admin_invitations", Integer.class)).isZero();
    }

    @Test void retriesGatewayErrorsAndLabelsAcceptanceAccurately() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = gateway(attempts, 503, 202);
        try {
            var invite = invite(); String message = invite.deliveryStatus().messageId();
            long before = System.currentTimeMillis();
            dispatcher.dispatchDue();
            assertThat(delivery.delivery(message).state()).isEqualTo("retry_wait");
            assertThat(messages.find(message).orElseThrow().nextAttemptAt()).isBetween(before + 60_000L, System.currentTimeMillis() + 60_000L);
            due(message); dispatcher.dispatchDue();
            assertThat(delivery.delivery(message).state()).isEqualTo("provider_accepted");
            assertThat(delivery.delivery(message).attempts()).isEqualTo(2);
            assertCleared(message);
        } finally { server.stop(0); }
    }

    @Test void stopsAfterFiveAttemptsAndRecoversAnExpiredWorkerLease() throws Exception {
        HttpServer server = gateway(new AtomicInteger(), 503, 503);
        try {
            var invite = invite(); String message = invite.deliveryStatus().messageId();
            assertThat(messages.claim(message, System.currentTimeMillis())).isPresent();
            assertThat(messages.claim(message, System.currentTimeMillis())).isEmpty();
            jdbc.update("UPDATE benchmark_mail_messages SET lease_until=0 WHERE id=?", message);
            long[] remainingDelays = {300_000L, 900_000L, 1_800_000L};
            for (int i = 0; i < 4; i++) {
                due(message); long before = System.currentTimeMillis(); dispatcher.dispatchDue();
                if (i < remainingDelays.length) assertThat(messages.find(message).orElseThrow().nextAttemptAt())
                        .isBetween(before + remainingDelays[i], System.currentTimeMillis() + remainingDelays[i]);
            }
            assertThat(delivery.delivery(message).state()).isEqualTo("failed");
            assertThat(delivery.delivery(message).attempts()).isEqualTo(5);
            assertCleared(message);
        } finally { server.stop(0); }
    }

    @Test void permanentGatewayFailuresDoNotRetryAndExpiredPayloadsArePurged() throws Exception {
        HttpServer server = gateway(new AtomicInteger(), 400, 400);
        try {
            String failed = invite().deliveryStatus().messageId(); dispatcher.dispatchDue();
            assertThat(delivery.delivery(failed).state()).isEqualTo("failed");
            assertThat(delivery.delivery(failed).attempts()).isEqualTo(1);
            String expired = invite().deliveryStatus().messageId();
            jdbc.update("UPDATE benchmark_mail_messages SET expires_at=0 WHERE id=?", expired); dispatcher.dispatchDue();
            assertThat(delivery.delivery(expired).state()).isEqualTo("expired"); assertCleared(expired);
        } finally { server.stop(0); }
    }

    @Test void parallelInvitesForOneMailboxHaveOnlyOneWinner() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> create = () -> { start.await(); try { admins.invite(actor, email, "Invitee"); return true; } catch (ApiException e) { return false; } };
            Future<Boolean> one = pool.submit(create), two = pool.submit(create); start.countDown();
            assertThat(java.util.List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_admin_invitations WHERE email=?", Integer.class, email)).isEqualTo(1);
    }

    private BenchmarkUser user(String role) {
        BenchmarkUser user = new BenchmarkUser("mail_user_" + UUID.randomUUID(), UUID.randomUUID() + "@example.com", "Mail test", role, "unused", null, Instant.now().toString());
        users.insert(user); return user;
    }
    private String codeMessage() {
        String challenge = "challenge_" + UUID.randomUUID();
        long now = System.currentTimeMillis(), expires = now + 600_000;
        var digest = cipher.codeDigest(challenge, actor.id(), "003917");
        jdbc.update("INSERT INTO benchmark_password_reset_challenges(id,user_id,email_key,code_digest,code_key_id,status,failed_attempts,created_at,expires_at) VALUES(?,?,?,?,?,'active',0,?,?)",
                challenge, actor.id(), "a".repeat(64), digest.value(), digest.keyId(), now, expires);
        return notifier.sendCode(actor.email(), challenge, "003917", Instant.ofEpochMilli(expires), actor.id());
    }
    private AdminAccountService.InvitationResult invite() { return admins.invite(actor, UUID.randomUUID() + "@example.com", "Invitee"); }
    private MailRepository.Message resetMessage() { return jdbc.query("SELECT id FROM benchmark_mail_messages WHERE purpose='password_reset' AND owner_id=? AND state='queued'", (r, i) -> messages.find(r.getString(1)).orElseThrow(), actor.id()).getFirst(); }
    private String token(String id) {
        var message = messages.find(id).orElseThrow();
        try { return mapper.readTree(cipher.decrypt(id, message.purpose(), message.reference(), message.encrypted())).path("token").asText(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void due(String id) { jdbc.update("UPDATE benchmark_mail_messages SET next_attempt_at=0 WHERE id=?", id); }
    private void assertCleared(String id) { assertThat(jdbc.queryForObject("SELECT ciphertext FROM benchmark_mail_messages WHERE id=?", String.class, id)).isNull(); }
    private String hash(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private HttpServer gateway(AtomicInteger attempts, int first, int following) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mail", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).startsWith("mail_");
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(attempts.incrementAndGet() == 1 ? first : following, -1); exchange.close();
        });
        server.start(); properties.setMode("http"); properties.setDeliveryUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mail");
        return server;
    }
}

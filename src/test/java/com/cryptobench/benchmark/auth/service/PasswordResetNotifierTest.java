package com.cryptobench.benchmark.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetNotifierTest {
    @TempDir Path temp;

    @Test
    void writesAdminInvitationToLocalOutboxWithoutReturningItToTheCaller() throws Exception {
        Path outbox = temp.resolve("mail.jsonl");
        PasswordResetNotifier notifier = new PasswordResetNotifier(new ObjectMapper(), "", outbox.toString(),
                "http://127.0.0.1:4173");

        assertThat(notifier.deliveryMode()).isEqualTo("outbox");
        assertThat(notifier.sendAdminInvite("admin@example.com", "Admin", "a".repeat(64),
                Instant.parse("2026-10-29T00:00:00Z"))).isTrue();
        String message = Files.readString(outbox);
        assertThat(message).contains("admin_invite", "admin@example.com", "a".repeat(64));
    }

    @Test
    void reportsUnconfiguredDeliveryWithoutPretendingTheMessageWasSent() {
        PasswordResetNotifier notifier = new PasswordResetNotifier(new ObjectMapper(), "", "",
                "http://127.0.0.1:4173");

        assertThat(notifier.deliveryMode()).isEqualTo("unconfigured");
        assertThat(notifier.sendAdminInvite("admin@example.com", "Admin", "b".repeat(64), Instant.now()))
                .isFalse();
    }
}

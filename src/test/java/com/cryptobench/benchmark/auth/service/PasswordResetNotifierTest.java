package com.cryptobench.benchmark.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cryptobench.benchmark.mail.MailDeliveryService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PasswordResetNotifierTest {
    @Test void queuesOnlyTheCodeContractWithoutALegacyLinkOrGrant() {
        MailDeliveryService delivery = mock(MailDeliveryService.class);
        PasswordResetNotifier notifier = new PasswordResetNotifier(new ObjectMapper(), delivery);
        Instant expires = Instant.parse("2027-01-01T00:00:00Z");
        notifier.sendCode("recipient@outlook.com", "challenge-example", "003917", expires, "user-example");
        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(delivery).enqueue(eq("password_reset_code"), eq("challenge-example"), eq("user-example"), eq(1), body.capture(), eq(expires.toEpochMilli()));
        assertThat(body.getValue().path("type").asText()).isEqualTo("password_reset_code");
        assertThat(body.getValue().path("code").asText()).isEqualTo("003917");
        assertThat(body.getValue().path("email").asText()).isEqualTo("recipient@outlook.com");
        assertThat(body.getValue().has("token")).isFalse();
        assertThat(body.getValue().has("resetUrl")).isFalse();
    }
    @Test
    void reportsUnconfiguredDeliveryWithoutPretendingTheMessageWasSent() {
        MailDeliveryService delivery = mock(MailDeliveryService.class);
        when(delivery.status()).thenReturn(new MailDeliveryService.Status("disabled", false, "delivery_disabled", ""));
        PasswordResetNotifier notifier = new PasswordResetNotifier(new ObjectMapper(), delivery);
        assertThat(notifier.ready()).isFalse();
        verify(delivery, never()).enqueue(any(), any(), any(), anyInt(), any(), anyLong());
    }
}

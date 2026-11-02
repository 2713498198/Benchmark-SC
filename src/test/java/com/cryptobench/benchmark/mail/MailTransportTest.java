package com.cryptobench.benchmark.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.MailSendException;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class MailTransportTest {
    @ParameterizedTest
    @CsvSource({"550,RCPT,false", "451,RCPT,true", "550,DATA,false", "451,DATA,true"})
    void classifiesActualWrappedSmtpFailuresWithoutExposingProviderMessages(int status, String phase, boolean retryable) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(10_000);
            Future<?> completed = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10_000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                    writer.print("220 test.local ESMTP\r\n"); writer.flush();
                    boolean data = false; String line;
                    while ((line = reader.readLine()) != null) {
                        if (data && !".".equals(line)) continue;
                        if (data) { data = false; writer.print(status + " fixture-private-provider-details\r\n"); }
                        else if (line.startsWith("RCPT") && phase.equals("RCPT")) writer.print(status + " fixture-private-provider-details\r\n");
                        else if (line.startsWith("DATA")) { data = true; writer.print("354 end with dot\r\n"); }
                        else if (line.startsWith("QUIT")) { writer.print("221 bye\r\n"); writer.flush(); break; }
                        else writer.print("250 OK\r\n");
                        writer.flush();
                    }
                }
                return null;
            });
            MailProperties properties = new MailProperties(); properties.setMode("smtp");
            properties.setSmtpHost("127.0.0.1"); properties.setSmtpPort(server.getLocalPort()); properties.setSmtpStarttls(false); properties.setFrom("sender@example.test");
            ObjectMapper mapper = new ObjectMapper(); var payload = mapper.createObjectNode();
            payload.put("email", "recipient@example.test"); payload.put("type", "admin_invite");
            payload.put("inviteUrl", "http://127.0.0.1/#/admin-invite?token=fixture-private-token");
            var transport = new MailTransport(properties, new MailSettings(properties, new MockEnvironment()), mapper);
            assertThatThrownBy(() -> transport.send("smtp", "mail_fixture_rejected", payload))
                    .isInstanceOfSatisfying(MailTransport.DeliveryException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("smtp_" + status);
                        assertThat(failure.retryable()).isEqualTo(retryable);
                        assertThat(failure.getMessage()).isEqualTo("smtp_" + status);
                        assertThat(failure.getCause()).isNull();
                    });
            completed.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void traversesSpringAggregatesAndJavaMailNextExceptions() throws Exception {
        var linked = new MessagingException("fixture-private-message");
        linked.setNextException(new SMTPAddressFailedException(new InternetAddress("recipient@example.test"), "RCPT TO", 550, "fixture-private-provider-details"));
        var aggregate = new MailSendException("fixture-private-aggregate", null, Map.of("fixture", linked));
        var failure = MailTransport.classify(new RuntimeException("fixture-private-wrapper", aggregate));
        assertThat(failure.code()).isEqualTo("smtp_550");
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.getMessage()).isEqualTo("smtp_550");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin_invite", "password_reset_code"})
    void sendsOnlyToALoopbackSmtpFixtureAndDoesNotClaimInboxDelivery(String type) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(10_000);
            Future<String> received = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10_000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                    writer.print("220 test.local ESMTP\r\n"); writer.flush();
                    StringBuilder body = new StringBuilder(); boolean data = false; String line;
                    while ((line = reader.readLine()) != null) {
                        if (data && !".".equals(line)) { body.append(line).append('\n'); continue; }
                        if (data) { data = false; writer.print("250 accepted\r\n"); }
                        else if (line.startsWith("DATA")) { data = true; writer.print("354 end with dot\r\n"); }
                        else if (line.startsWith("QUIT")) { writer.print("221 bye\r\n"); writer.flush(); break; }
                        else writer.print("250 OK\r\n");
                        writer.flush();
                    }
                    return body.toString();
                }
            });
            MailProperties properties = new MailProperties(); properties.setMode("smtp");
            properties.setSmtpHost("127.0.0.1"); properties.setSmtpPort(server.getLocalPort()); properties.setSmtpStarttls(false); properties.setFrom("sender@example.com");
            ObjectMapper mapper = new ObjectMapper(); var payload = mapper.createObjectNode();
            payload.put("email", "recipient@example.com"); payload.put("type", type);
            if (type.equals("password_reset_code")) payload.put("code", "003917");
            else payload.put("inviteUrl", "http://127.0.0.1/#/admin-invite?token=fixture");
            payload.put("expiresAt", "2027-01-01T00:00:00Z");
            var transport = new MailTransport(properties, new MailSettings(properties, new MockEnvironment()), mapper);
            assertThat(transport.send("smtp", "mail_fixture", payload)).isEqualTo("provider_accepted");
            String mail = received.get(10, TimeUnit.SECONDS);
            assertThat(mail).contains("Message-ID: <mail_fixture@cryptobench.local>").contains("recipient@example.com");
            var parsed = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()),
                    new ByteArrayInputStream(mail.getBytes(StandardCharsets.US_ASCII)));
            if (type.equals("password_reset_code")) {
                assertThat(parsed.getSubject()).contains("验证码");
                assertThat((String) parsed.getContent()).contains("003917", "2027-01-01T00:00:00Z").doesNotContain("reset-password", "token=");
            } else assertThat((String) parsed.getContent()).contains("admin-invite?token=fixture");
        }
    }
}

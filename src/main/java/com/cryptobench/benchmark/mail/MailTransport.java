package com.cryptobench.benchmark.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class MailTransport {
    private final MailProperties properties;
    private final MailSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public MailTransport(MailProperties properties, MailSettings settings, ObjectMapper mapper) {
        this.properties = properties; this.settings = settings; this.mapper = mapper;
    }
    public String send(String mode, String messageId, ObjectNode payload) throws DeliveryException {
        if (!mode.equals(settings.mode()) || settings.configurationError() != null) throw new DeliveryException("delivery_not_configured", false);
        try {
            return switch (mode) {
                case "http" -> http(messageId, payload);
                case "smtp" -> smtp(messageId, payload);
                case "outbox" -> outbox(messageId, payload);
                default -> throw new DeliveryException("delivery_disabled", false);
            };
        } catch (DeliveryException e) { throw e; }
        catch (MailAuthenticationException e) { throw new DeliveryException("smtp_authentication_failed", false); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DeliveryException("delivery_interrupted", true); }
        catch (Exception e) { throw classify(e); }
    }
    /** JavaMail links failures through both causes and nextException; Spring also aggregates per-message failures. */
    static DeliveryException classify(Throwable exception) {
        var pending = new ArrayDeque<Throwable>(); pending.add(exception);
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Integer temporary = null; boolean invalidRecipient = false;
        while (!pending.isEmpty()) {
            Throwable failure = pending.removeFirst(); if (!seen.add(failure)) continue;
            if (failure instanceof MailAuthenticationException) return new DeliveryException("smtp_authentication_failed", false);
            Integer code = failure instanceof SMTPAddressFailedException address ? Integer.valueOf(address.getReturnCode())
                    : failure instanceof SMTPSendFailedException send ? Integer.valueOf(send.getReturnCode()) : null;
            if (code != null && code >= 500 && code < 600) return new DeliveryException("smtp_" + code, false);
            if (code != null && code >= 400 && code < 500) temporary = code;
            if (failure instanceof SendFailedException failed && failed.getInvalidAddresses() != null && failed.getInvalidAddresses().length > 0) invalidRecipient = true;
            if (failure.getCause() != null) pending.add(failure.getCause());
            if (failure instanceof MessagingException mail && mail.getNextException() != null) pending.add(mail.getNextException());
            if (failure instanceof MailSendException aggregate) aggregate.getFailedMessages().values().forEach(value -> { if (value != null) pending.add(value); });
        }
        if (temporary != null) return new DeliveryException("smtp_" + temporary, true);
        return invalidRecipient ? new DeliveryException("invalid_recipient", false) : new DeliveryException("transport_unavailable", true);
    }
    private String http(String id, ObjectNode payload) throws Exception {
        ObjectNode body = payload.deepCopy(); body.put("messageId", id);
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.deliveryUrl())).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Idempotency-Key", id)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status >= 200 && status < 300) return "provider_accepted";
        throw new DeliveryException(status == 401 || status == 403 ? "gateway_authentication_failed" : "gateway_http_" + status,
                status == 408 || status == 429 || status >= 500);
    }
    private String smtp(String id, ObjectNode payload) throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getSmtpHost()); sender.setPort(properties.getSmtpPort());
        if (!properties.getSmtpUsername().isBlank()) sender.setUsername(properties.getSmtpUsername());
        if (!properties.getSmtpPassword().isBlank()) sender.setPassword(properties.getSmtpPassword());
        Properties options = sender.getJavaMailProperties();
        options.setProperty("mail.smtp.auth", String.valueOf(!properties.getSmtpUsername().isBlank()));
        boolean starttls = properties.isSmtpStarttls() && !properties.isSmtpSsl();
        options.setProperty("mail.smtp.starttls.enable", String.valueOf(starttls));
        options.setProperty("mail.smtp.starttls.required", String.valueOf(starttls));
        options.setProperty("mail.smtp.ssl.enable", String.valueOf(properties.isSmtpSsl()));
        options.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        options.setProperty("mail.smtp.connectiontimeout", "10000");
        options.setProperty("mail.smtp.timeout", "10000");
        options.setProperty("mail.smtp.writetimeout", "10000");
        MimeMessage message = new MimeMessage(sender.getSession()) {
            @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID", "<" + id + "@cryptobench.local>"); }
        };
        MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.getFrom()); helper.setTo(payload.path("email").asText());
        boolean invite = "admin_invite".equals(payload.path("type").asText());
        boolean code = "password_reset_code".equals(payload.path("type").asText());
        helper.setSubject(invite ? "CryptoBench 管理员邀请" : "CryptoBench 找回密码验证码");
        String content = code ? "你的 CryptoBench 找回密码验证码是：\n" + payload.path("code").asText()
                + "\n请在找回密码页面输入此验证码。验证码仅能使用一次，请勿提供给他人。"
                : "你收到了一份 CryptoBench 管理员邀请。\n" + payload.path("inviteUrl").asText();
        helper.setText(content + "\n有效期至：" + payload.path("expiresAt").asText()
                + "\n如果你未请求此操作，请忽略这封邮件。", false);
        sender.send(message);
        return "provider_accepted";
    }
    private synchronized String outbox(String id, ObjectNode payload) throws Exception {
        Path file = Path.of(settings.outbox());
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        ObjectNode body = payload.deepCopy(); body.put("messageId", id);
        Files.writeString(file, mapper.writeValueAsString(body) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return "local_outbox_written";
    }
    public static class DeliveryException extends Exception {
        private final String code; private final boolean retryable;
        public DeliveryException(String code, boolean retryable) { super(code); this.code = code; this.retryable = retryable; }
        public String code() { return code; } public boolean retryable() { return retryable; }
    }
}

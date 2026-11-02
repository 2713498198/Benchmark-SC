package com.cryptobench.benchmark.mail;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class MailSettings {
    private final MailProperties properties;
    private final Environment environment;
    public MailSettings(MailProperties properties, Environment environment) { this.properties = properties; this.environment = environment; }
    public String deliveryUrl() { return fallback(properties.getDeliveryUrl(), "benchmark.password-reset.delivery-url", ""); }
    public String outbox() { return fallback(properties.getOutbox(), "benchmark.password-reset.outbox", ""); }
    public String baseUrl() { return fallback(properties.getBaseUrl(), "benchmark.password-reset.base-url", "http://127.0.0.1:4173").replaceAll("/+$", ""); }
    public String mode() {
        String requested = properties.getMode() == null ? "auto" : properties.getMode().trim().toLowerCase(java.util.Locale.ROOT);
        if (!"auto".equals(requested)) return requested;
        if (!deliveryUrl().isBlank()) return "http";
        if (!outbox().isBlank()) return "outbox";
        return "disabled";
    }
    public String configurationError() {
        if (!validHttp(baseUrl())) return "invalid_base_url";
        return switch (mode()) {
            case "disabled" -> "delivery_disabled";
            case "http" -> validHttp(deliveryUrl()) ? null : "invalid_gateway_url";
            case "smtp" -> properties.getSmtpHost().isBlank() || properties.getFrom().isBlank()
                    || properties.getSmtpPort() < 1 || properties.getSmtpPort() > 65535 ? "smtp_not_configured" : null;
            case "outbox" -> outbox().isBlank() ? "outbox_not_configured" : null;
            default -> "invalid_delivery_mode";
        };
    }
    private String fallback(String value, String oldName, String defaultValue) {
        return value == null || value.isBlank() ? environment.getProperty(oldName, defaultValue).trim() : value.trim();
    }
    private boolean validHttp(String value) {
        try { URI uri = URI.create(value); return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null; }
        catch (IllegalArgumentException e) { return false; }
    }
}

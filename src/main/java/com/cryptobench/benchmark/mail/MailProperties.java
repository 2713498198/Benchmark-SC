package com.cryptobench.benchmark.mail;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Delivery settings are separate from Spring's optional default mail sender. */
@ConfigurationProperties("benchmark.mail")
public class MailProperties {
    private String mode = "auto", deliveryUrl = "", outbox = "", baseUrl = "";
    private String key = "", keyFile = "var/secrets/mail.key";
    private Map<String, String> previousKeys = new LinkedHashMap<>();
    private boolean workerEnabled = true;
    private String smtpHost = "", smtpUsername = "", smtpPassword = "", from = "";
    private int smtpPort = 587;
    private boolean smtpStarttls = true, smtpSsl = false;
    public String getMode() { return mode; } public void setMode(String v) { mode = v; }
    public String getDeliveryUrl() { return deliveryUrl; } public void setDeliveryUrl(String v) { deliveryUrl = v; }
    public String getOutbox() { return outbox; } public void setOutbox(String v) { outbox = v; }
    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String v) { baseUrl = v; }
    public String getKey() { return key; } public void setKey(String v) { key = v; }
    public String getKeyFile() { return keyFile; } public void setKeyFile(String v) { keyFile = v; }
    public Map<String, String> getPreviousKeys() { return previousKeys; } public void setPreviousKeys(Map<String, String> v) { previousKeys = v; }
    public boolean isWorkerEnabled() { return workerEnabled; } public void setWorkerEnabled(boolean v) { workerEnabled = v; }
    public String getSmtpHost() { return smtpHost; } public void setSmtpHost(String v) { smtpHost = v; }
    public String getSmtpUsername() { return smtpUsername; } public void setSmtpUsername(String v) { smtpUsername = v; }
    public String getSmtpPassword() { return smtpPassword; } public void setSmtpPassword(String v) { smtpPassword = v; }
    public String getFrom() { return from; } public void setFrom(String v) { from = v; }
    public int getSmtpPort() { return smtpPort; } public void setSmtpPort(int v) { smtpPort = v; }
    public boolean isSmtpStarttls() { return smtpStarttls; } public void setSmtpStarttls(boolean v) { smtpStarttls = v; }
    public boolean isSmtpSsl() { return smtpSsl; } public void setSmtpSsl(boolean v) { smtpSsl = v; }
}

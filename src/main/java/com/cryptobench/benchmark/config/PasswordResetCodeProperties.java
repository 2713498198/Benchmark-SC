package com.cryptobench.benchmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits use persistent rolling windows; changing them never clears stored attempts. */
@ConfigurationProperties("benchmark.password-reset-code")
public class PasswordResetCodeProperties {
    private int codeLifetimeSeconds = 600;
    private int grantLifetimeSeconds = 300;
    private int resendSeconds = 60;
    private int sendsPerEmailPerHour = 5;
    private int sendsPerIpPerHour = 20;
    private int failuresPerChallenge = 5;
    private int failuresPerEmailPerQuarterHour = 10;
    private int verificationsPerIpPerFiveMinutes = 50;
    public int getCodeLifetimeSeconds() { return codeLifetimeSeconds; }
    public void setCodeLifetimeSeconds(int value) { codeLifetimeSeconds = positive(value); }
    public int getGrantLifetimeSeconds() { return grantLifetimeSeconds; }
    public void setGrantLifetimeSeconds(int value) { grantLifetimeSeconds = positive(value); }
    public int getResendSeconds() { return resendSeconds; }
    public void setResendSeconds(int value) { resendSeconds = positive(value); }
    public int getSendsPerEmailPerHour() { return sendsPerEmailPerHour; }
    public void setSendsPerEmailPerHour(int value) { sendsPerEmailPerHour = positive(value); }
    public int getSendsPerIpPerHour() { return sendsPerIpPerHour; }
    public void setSendsPerIpPerHour(int value) { sendsPerIpPerHour = positive(value); }
    public int getFailuresPerChallenge() { return failuresPerChallenge; }
    public void setFailuresPerChallenge(int value) { failuresPerChallenge = positive(value); }
    public int getFailuresPerEmailPerQuarterHour() { return failuresPerEmailPerQuarterHour; }
    public void setFailuresPerEmailPerQuarterHour(int value) { failuresPerEmailPerQuarterHour = positive(value); }
    public int getVerificationsPerIpPerFiveMinutes() { return verificationsPerIpPerFiveMinutes; }
    public void setVerificationsPerIpPerFiveMinutes(int value) { verificationsPerIpPerFiveMinutes = positive(value); }
    private static int positive(int value) {
        if (value < 1 || value > 86_400) throw new IllegalArgumentException("Password reset limit outside supported range");
        return value;
    }
}

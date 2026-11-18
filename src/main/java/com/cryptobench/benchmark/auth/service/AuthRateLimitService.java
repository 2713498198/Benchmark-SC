package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.repository.AuthRateLimitRepository;
import com.cryptobench.benchmark.config.AuthRateLimitProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Login is limited per normalized email so credential stuffing cannot bypass
 * an IP-only limit. Registration is limited per client address.
 */
@Service
public class AuthRateLimitService {
    private static final String LOGIN_EMAIL = "login_email";
    private static final String REGISTER_IP = "register_ip";

    private final AuthRateLimitRepository rates;
    private final AuthRateLimitProperties properties;

    public AuthRateLimitService(AuthRateLimitRepository rates, AuthRateLimitProperties properties) {
        this.rates = rates;
        this.properties = properties;
    }

    public void beforeLogin(String email) {
        long now = System.currentTimeMillis();
        rates.cleanup(now, properties.loginWindowSeconds() * 1000L);
        long retry = rates.retryAfter(LOGIN_EMAIL, hash(email.trim().toLowerCase()), now,
                properties.loginWindowSeconds() * 1000L, properties.loginMaxAttempts());
        if (retry > 0) throw new AuthRateLimitException(retry);
    }

    public void loginFailed(String email) {
        rates.event(LOGIN_EMAIL, hash(email.trim().toLowerCase()), System.currentTimeMillis());
    }

    public void loginSucceeded(String email) {
        rates.clear(LOGIN_EMAIL, hash(email.trim().toLowerCase()));
    }

    public void beforeRegister(String remoteAddr) {
        long now = System.currentTimeMillis();
        rates.cleanup(now, properties.registerWindowSeconds() * 1000L);
        long retry = rates.retryAfter(REGISTER_IP, hash(remoteAddr == null ? "" : remoteAddr.trim()), now,
                properties.registerWindowSeconds() * 1000L, properties.registerMaxAttempts());
        if (retry > 0) throw new AuthRateLimitException(retry);
    }

    public void registerAttempted(String remoteAddr) {
        rates.event(REGISTER_IP, hash(remoteAddr == null ? "" : remoteAddr.trim()), System.currentTimeMillis());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}

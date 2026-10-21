package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.SessionRepository;
import com.cryptobench.benchmark.config.BenchmarkProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    private static final String COOKIE_NAME = "cb_session";
    private static final Duration LIFETIME = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SessionRepository sessions;
    private final BenchmarkProperties properties;

    public SessionService(SessionRepository sessions, BenchmarkProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    public Optional<BenchmarkUser> currentUser(HttpServletRequest request) {
        return token(request).flatMap(value -> sessions.findUser(hash(value)));
    }

    @Transactional
    public void signIn(BenchmarkUser user, HttpServletRequest request, HttpServletResponse response) {
        token(request).ifPresent(value -> sessions.delete(hash(value)));
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String value = HexFormat.of().formatHex(random);
        sessions.insert(hash(value), user.id(), System.currentTimeMillis() + LIFETIME.toMillis());
        setCookie(response, value, LIFETIME);
    }

    @Transactional
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        token(request).ifPresent(value -> sessions.delete(hash(value)));
        setCookie(response, "", Duration.ZERO);
    }

    private Optional<String> token(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies()).filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue).filter(value -> value.matches("[a-f0-9]{64}")).findFirst();
    }

    private void setCookie(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true).secure(properties.secureCookie()).sameSite("Lax").path("/")
                .maxAge(maxAge).build().toString());
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

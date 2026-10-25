package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.repository.PasswordResetRepository;
import com.cryptobench.benchmark.auth.repository.SessionRepository;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本地可用的密码重置流程；投递由可配置的邮件网关或安全 outbox 适配器负责。 */
@Service
public class PasswordResetService {
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final PasswordService passwords;
    private final SessionRepository sessions;
    private final PasswordResetRepository tokens;
    private final PasswordResetNotifier notifier;

    public PasswordResetService(UserRepository users, PasswordService passwords, SessionRepository sessions,
                                PasswordResetRepository tokens, PasswordResetNotifier notifier) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.tokens = tokens;
        this.notifier = notifier;
    }

    /** 无论邮箱是否存在都返回成功，防止账号枚举。 */
    @Transactional
    public void request(String email) {
        if (email == null || email.isBlank()) return;
        Optional<BenchmarkUser> user = users.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT));
        if (user.isEmpty()) return;
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = HexFormat.of().formatHex(bytes);
        tokens.insert(hash(raw), user.get().id(), System.currentTimeMillis() + LIFETIME.toMillis(), Instant.now().toString());
        notifier.send(user.get(), raw, Instant.ofEpochMilli(System.currentTimeMillis() + LIFETIME.toMillis()));
    }

    @Transactional
    public void reset(String rawToken, String newPassword) {
        if (rawToken == null || !rawToken.matches("[a-f0-9]{64}") || newPassword == null || newPassword.length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "重置令牌或新密码格式无效");
        }
        PasswordResetRepository.Token token = tokens.find(hash(rawToken)).orElse(null);
        if (token == null || token.usedAt() != null || token.expiresAt() <= System.currentTimeMillis()
                || tokens.consume(token.hash(), System.currentTimeMillis()) == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "重置令牌无效或已过期");
        }
        users.findById(token.userId()).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "重置令牌无效"));
        users.updatePassword(token.userId(), passwords.encode(newPassword));
        sessions.deleteByUser(token.userId());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

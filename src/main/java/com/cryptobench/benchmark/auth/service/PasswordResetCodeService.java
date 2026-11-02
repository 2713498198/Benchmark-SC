package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.repository.PasswordResetCodeRepository;
import com.cryptobench.benchmark.auth.repository.PasswordResetRepository;
import com.cryptobench.benchmark.auth.repository.SessionRepository;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.config.PasswordResetCodeProperties;
import com.cryptobench.benchmark.mail.MailCipher;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A code proves mailbox access; only a separate one-use high-entropy grant permits changing a password. */
@Service
public class PasswordResetCodeService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long HOUR = 3_600_000L, QUARTER_HOUR = 900_000L, FIVE_MINUTES = 300_000L;
    private final PasswordResetCodeRepository repository;
    private final PasswordResetRepository legacy;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordService passwords;
    private final PasswordResetNotifier notifier;
    private final MailCipher cipher;
    private final PasswordResetCodeProperties properties;

    public PasswordResetCodeService(PasswordResetCodeRepository repository, PasswordResetRepository legacy,
            UserRepository users, SessionRepository sessions, PasswordService passwords, PasswordResetNotifier notifier,
            MailCipher cipher, PasswordResetCodeProperties properties) {
        this.repository = repository; this.legacy = legacy; this.users = users; this.sessions = sessions;
        this.passwords = passwords; this.notifier = notifier; this.cipher = cipher; this.properties = properties;
    }

    @Transactional(noRollbackFor = PasswordResetFlowException.class)
    public Requested request(String emailInput, String ip) {
        String email = normalizeEmail(emailInput), emailKey = hash(email), ipKey = hash(ip == null ? "unknown" : ip);
        if (!notifier.ready()) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "reset_delivery_unavailable", "邮件服务暂不可用，请稍后重试");
        notifier.guard();
        long now = System.currentTimeMillis(); repository.cleanup(now, properties.getResendSeconds() * 1000L);
        long retry = Math.max(repository.retryAfter("send_email", emailKey, now, properties.getResendSeconds() * 1000L, 1),
                repository.retryAfter("send_email", emailKey, now, HOUR, properties.getSendsPerEmailPerHour()));
        retry = Math.max(retry, repository.retryAfter("send_ip", ipKey, now, HOUR, properties.getSendsPerIpPerHour()));
        retry = Math.max(retry, repository.retryAfter("failed_email", emailKey, now, QUARTER_HOUR, properties.getFailuresPerEmailPerQuarterHour()));
        if (retry > 0) throw limited(retry);
        repository.event("send_email", emailKey, now); repository.event("send_ip", ipKey, now);
        var user = users.findByEmail(email).orElse(null);
        repository.invalidateEmail(emailKey);
        if (user != null) invalidateUser(user.id(), now);
        String id = randomToken(), code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String userId = user == null ? null : user.id();
        var digest = cipher.codeDigest(id, userId, code);
        long expires = now + properties.getCodeLifetimeSeconds() * 1000L;
        repository.insertChallenge(id, userId, emailKey, digest.value(), digest.keyId(), now, expires);
        if (user != null) notifier.sendCode(email, id, code, Instant.ofEpochMilli(expires), user.id());
        return new Requested(true, id, properties.getCodeLifetimeSeconds(), properties.getResendSeconds());
    }

    /** Expected failures commit counters; infrastructure failures still roll the transaction back. */
    @Transactional(noRollbackFor = PasswordResetFlowException.class)
    public Verified verify(String challengeId, String code, String ip) {
        notifier.guard();
        long now = System.currentTimeMillis(); repository.cleanup(now, properties.getResendSeconds() * 1000L);
        String ipKey = hash(ip == null ? "unknown" : ip);
        long ipRetry = repository.retryAfter("verify_ip", ipKey, now, FIVE_MINUTES, properties.getVerificationsPerIpPerFiveMinutes());
        if (ipRetry > 0) throw limited(ipRetry);
        repository.event("verify_ip", ipKey, now);
        var challenge = validToken(challengeId) ? repository.challenge(challengeId).orElse(null) : null;
        if (challenge == null) throw challengeExpired();
        long emailRetry = repository.retryAfter("failed_email", challenge.emailKey(), now, QUARTER_HOUR, properties.getFailuresPerEmailPerQuarterHour());
        if (emailRetry > 0) {
            if (challenge.userId() != null) invalidateUser(challenge.userId(), now);
            repository.invalidateEmail(challenge.emailKey());
            throw limited(emailRetry);
        }
        if ("locked".equals(challenge.status())) throw challengeLocked();
        if (!"active".equals(challenge.status()) || challenge.expiresAt() <= now) throw challengeExpired();
        boolean matches;
        try { matches = cipher.verifyCode(challenge.id(), challenge.userId(), code, challenge.digest(), challenge.keyId()); }
        catch (MailCipher.CodeKeyUnavailableException e) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "reset_delivery_unavailable", "验证服务暂不可用，请稍后重试");
        }
        if (!matches || challenge.userId() == null) {
            boolean locked = challenge.failedAttempts() + 1 >= properties.getFailuresPerChallenge();
            repository.failChallenge(challenge.id(), locked);
            repository.event("failed_email", challenge.emailKey(), now);
            long retry = repository.retryAfter("failed_email", challenge.emailKey(), now, QUARTER_HOUR, properties.getFailuresPerEmailPerQuarterHour());
            if (locked && challenge.userId() != null) notifier.cancelResets(challenge.userId());
            if (retry > 0) {
                if (challenge.userId() != null) invalidateUser(challenge.userId(), now);
                repository.invalidateEmail(challenge.emailKey());
                throw limited(retry);
            }
            if (locked) throw challengeLocked();
            throw failure(HttpStatus.BAD_REQUEST, "reset_code_invalid", "验证码无效或已过期，请检查或重新获取");
        }
        if (repository.verifyChallenge(challenge.id(), now) != 1) throw challengeExpired();
        notifier.cancelResets(challenge.userId());
        String token = randomToken(); long expires = now + properties.getGrantLifetimeSeconds() * 1000L;
        repository.insertGrant(hash(token), challenge.userId(), challenge.id(), now, expires);
        return new Verified(token, properties.getGrantLifetimeSeconds());
    }

    @Transactional(readOnly = true)
    public Status status(String token) {
        var grant = validToken(token) ? repository.validGrant(hash(token), System.currentTimeMillis()).orElse(null) : null;
        if (grant == null) return new Status(false, null, null, null);
        var user = users.findById(grant.userId()).orElse(null);
        return user == null ? new Status(false, null, null, null)
                : new Status(true, grant.challengeId(), maskEmail(user.email()), Instant.ofEpochMilli(grant.expiresAt()).toString());
    }

    @Transactional
    public void complete(String token, String challengeId, String password, String confirmation) {
        if (password == null || password.length() < 8 || password.length() > 24)
            throw failure(HttpStatus.BAD_REQUEST, "reset_password_invalid", "密码长度应为 8 至 24 位");
        if (!password.equals(confirmation)) throw failure(HttpStatus.BAD_REQUEST, "reset_password_mismatch", "两次输入的密码不一致");
        notifier.guard(); long now = System.currentTimeMillis();
        var grant = validToken(token) ? repository.validGrant(hash(token), now).orElse(null) : null;
        if (grant == null || users.findById(grant.userId()).isEmpty()) throw grantExpired();
        if (!grant.challengeId().equals(challengeId)) throw failure(HttpStatus.BAD_REQUEST, "reset_challenge_mismatch", "当前验证与页面不匹配，请刷新页面后重试");
        // Expensive password work precedes the atomic consume; transaction failures never spend the grant.
        String encoded = passwords.encode(password);
        now = System.currentTimeMillis();
        if (repository.consumeGrant(grant.hash(), challengeId, now) != 1) throw grantExpired();
        users.updatePassword(grant.userId(), encoded);
        sessions.deleteByUser(grant.userId());
        invalidateUser(grant.userId(), now);
    }

    private void invalidateUser(String userId, long now) {
        repository.invalidateUser(userId, now); legacy.invalidateByUser(userId, now); notifier.cancelResets(userId);
    }
    private static String normalizeEmail(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.isBlank() || normalized.length() > 254 || !normalized.contains("@") || normalized.contains("\r") || normalized.contains("\n")) throw new AddressException();
            InternetAddress parsed = new InternetAddress(normalized, true); parsed.validate();
            if (!normalized.equals(parsed.getAddress())) throw new AddressException();
            return normalized;
        } catch (AddressException e) { throw failure(HttpStatus.BAD_REQUEST, "reset_email_invalid", "请输入有效邮箱"); }
    }
    private static String maskEmail(String email) {
        int at = email.lastIndexOf('@');
        return at > 0 ? email.substring(0, 1) + "***" + email.substring(at) : "***";
    }
    public static boolean validToken(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static String randomToken() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static PasswordResetFlowException failure(HttpStatus status, String code, String message) { return new PasswordResetFlowException(status, code, message); }
    private static PasswordResetFlowException limited(long seconds) { return new PasswordResetFlowException(HttpStatus.TOO_MANY_REQUESTS, "reset_rate_limited", "操作过于频繁，请稍后重试", seconds); }
    private static PasswordResetFlowException challengeExpired() { return failure(HttpStatus.BAD_REQUEST, "reset_challenge_expired", "验证码无效或已过期，请检查或重新获取"); }
    private static PasswordResetFlowException challengeLocked() { return failure(HttpStatus.BAD_REQUEST, "reset_challenge_locked", "验证码尝试次数已用完，请重新获取"); }
    private static PasswordResetFlowException grantExpired() { return failure(HttpStatus.BAD_REQUEST, "reset_grant_expired", "验证已失效，请重新获取验证码"); }
    public record Requested(boolean ok, String challengeId, int expiresInSeconds, int retryAfterSeconds) {}
    /** The raw token is internal and is only written to an HttpOnly Cookie by the controller. */
    public record Verified(String token, int maxAgeSeconds) {
        @Override public String toString() { return "Verified[token=<redacted>, maxAgeSeconds=" + maxAgeSeconds + "]"; }
    }
    public record Status(boolean canReset, String challengeId, String maskedEmail, String expiresAt) {}
}

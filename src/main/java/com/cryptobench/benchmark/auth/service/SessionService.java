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

/**
 * 管理自定义数据库会话：浏览器持有随机令牌，数据库仅保存其 SHA-256 摘要。
 * 会话有效期为 7 天，不依赖 Servlet HttpSession。
 */
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

    /**
     * 提取格式有效的 Cookie 令牌，计算摘要后查询未过期的会话用户。
     */
    public Optional<BenchmarkUser> currentUser(HttpServletRequest request) {
        return token(request).flatMap(value -> sessions.findUser(hash(value)));
    }

    /**
     * 在事务中撤销当前旧会话并创建新令牌，避免登录后继续沿用原令牌。
     */
    @Transactional
    public void signIn(BenchmarkUser user, HttpServletRequest request, HttpServletResponse response) {
        if (!"active".equals(user.accountStatus())) {
            throw new IllegalStateException("Cannot create a session for a disabled account");
        }
        token(request).ifPresent(value -> sessions.delete(hash(value)));
        // 使用安全随机源生成 256 位令牌；十六进制编码后得到 64 个字符。
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String value = HexFormat.of().formatHex(random);
        // 原始令牌只发送给浏览器，数据库中的摘要不能直接作为会话 Cookie 使用。
        sessions.insert(hash(value), user.id(), System.currentTimeMillis() + LIFETIME.toMillis());
        setCookie(response, value, LIFETIME);
    }

    /**
     * 撤销当前令牌并写入有效期为零的 Cookie，数据库和浏览器两端同时退出。
     */
    @Transactional
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        token(request).ifPresent(value -> sessions.delete(hash(value)));
        setCookie(response, "", Duration.ZERO);
    }

    /**
     * 只接受 cb_session 中的 64 位小写十六进制令牌，缺失或格式错误时返回空值。
     */
    private Optional<String> token(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies()).filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue).filter(value -> value.matches("[a-f0-9]{64}")).findFirst();
    }

    /**
     * HttpOnly 禁止脚本读取会话令牌；Secure 随环境配置，SameSite=Lax 限制跨站发送。
     */
    private void setCookie(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true).secure(properties.secureCookie()).sameSite("Lax").path("/")
                .maxAge(maxAge).build().toString());
    }

    /**
     * 将原始令牌转换为固定长度摘要，用于数据库查询与存储。
     */
    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

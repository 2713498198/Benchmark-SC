package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 密码编码与校验：新账号采用带随机盐的 PBKDF2，旧账号兼容原 Node 服务的 scrypt。
 */
@Service
public class PasswordService {
    private final Pbkdf2PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    /**
     * 用算法前缀标记新哈希，方便验证时选择对应算法；相同密码的多次编码结果可不同。
     */
    public String encode(String password) {
        return "{pbkdf2}" + encoder.encode(password);
    }

    /**
     * 根据哈希前缀选择算法；旧数据缺盐或哈希格式无效时返回验证失败。
     */
    public boolean matches(String password, BenchmarkUser user) {
        if (user.passwordHash().startsWith("{pbkdf2}")) {
            return encoder.matches(password, user.passwordHash().substring("{pbkdf2}".length()));
        }
        // 旧 Node 实现将十六进制盐值本身作为 UTF-8 字符串使用，不能先按十六进制解码。
        try {
            byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8),
                    user.passwordSalt().getBytes(StandardCharsets.UTF_8), 16384, 8, 1, 64);
            // 使用专用摘要比较方法比较派生结果与旧哈希。
            return MessageDigest.isEqual(actual, HexFormat.of().parseHex(user.passwordHash()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}

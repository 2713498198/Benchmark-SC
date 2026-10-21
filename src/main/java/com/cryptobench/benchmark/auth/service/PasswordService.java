package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {
    private final Pbkdf2PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String encode(String password) {
        return "{pbkdf2}" + encoder.encode(password);
    }

    public boolean matches(String password, BenchmarkUser user) {
        if (user.passwordHash().startsWith("{pbkdf2}")) {
            return encoder.matches(password, user.passwordHash().substring("{pbkdf2}".length()));
        }
        // Node's previous scrypt implementation used its hex salt as a UTF-8 string.
        try {
            byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8),
                    user.passwordSalt().getBytes(StandardCharsets.UTF_8), 16384, 8, 1, 64);
            return MessageDigest.isEqual(actual, HexFormat.of().parseHex(user.passwordHash()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}

package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.dto.LoginRequest;
import com.cryptobench.benchmark.auth.dto.RegisterRequest;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.config.BenchmarkProperties;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordService passwords;
    private final BenchmarkProperties properties;

    public AuthService(UserRepository users, PasswordService passwords, BenchmarkProperties properties) {
        this.users = users;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Transactional
    public BenchmarkUser register(RegisterRequest input) {
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "该邮箱已注册");
        }
        BenchmarkUser user = new BenchmarkUser("user_" + UUID.randomUUID(), email, input.name().trim(),
                "researcher", passwords.encode(input.password()), null, Instant.now().toString());
        users.insert(user);
        return user;
    }

    public BenchmarkUser login(LoginRequest input) {
        BenchmarkUser user = users.findByEmail(input.email().trim().toLowerCase(Locale.ROOT)).orElse(null);
        if (user == null || !passwords.matches(input.password(), user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
        }
        return user;
    }

    @Transactional
    public BenchmarkUser demo(String requestedRole) {
        if (!properties.demoEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前环境未启用体验账号");
        }
        String role = "admin".equals(requestedRole) ? "admin" : "researcher";
        String email = role + "@cryptobench.local";
        return users.findByEmail(email).orElseGet(() -> {
            BenchmarkUser user = new BenchmarkUser("demo_" + role, email,
                    "admin".equals(role) ? "测评集管理员" : "研究者", role,
                    passwords.encode(UUID.randomUUID().toString()), null, Instant.now().toString());
            users.insert(user);
            return user;
        });
    }
}

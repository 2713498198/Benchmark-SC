package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.dto.RegisterRequest;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Invoked only by the native desktop controller, never exposed as an HTTP API. */
@Service
@Profile("desktop")
public class DesktopAdminService {
    private final UserRepository users;
    private final PasswordService passwords;
    private final Validator validator;

    public DesktopAdminService(UserRepository users, PasswordService passwords, Validator validator) {
        this.users = users;
        this.passwords = passwords;
        this.validator = validator;
    }

    public boolean needsSetup() { return users.countRealActiveAdmins() == 0; }

    @Transactional
    public void create(String name, String email, String password) {
        if (!needsSetup()) return;
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        RegisterRequest request = new RegisterRequest(name == null ? "" : name.trim(), normalized, password);
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new IllegalArgumentException(violations.iterator().next().getMessage());
        if (Set.of("admin@cryptobench.local", "researcher@cryptobench.local").contains(normalized)) {
            throw new IllegalArgumentException("请使用真实邮箱，不要使用体验账号地址。");
        }
        if (users.existsByEmail(normalized)) throw new IllegalArgumentException("该邮箱已经注册，请使用未注册的邮箱；现有账号不会被改动。");
        users.insert(new BenchmarkUser("admin_" + UUID.randomUUID(), normalized, request.name(), "admin",
                passwords.encode(password), null, Instant.now().toString()));
    }
}

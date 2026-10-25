package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.config.AdminBootstrapProperties;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first administrator once when deployment supplies explicit bootstrap credentials. */
@Component
public class AdminBootstrapInitializer implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrapInitializer.class);
    private final UserRepository users;
    private final PasswordService passwords;
    private final AdminBootstrapProperties properties;

    public AdminBootstrapInitializer(UserRepository users, PasswordService passwords, AdminBootstrapProperties properties) {
        this.users = users;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = clean(properties.email()).toLowerCase(Locale.ROOT);
        String name = clean(properties.name());
        String password = properties.password() == null ? "" : properties.password();
        if (email.isBlank() || name.isBlank() || password.isBlank()) return;
        if (password.length() < 12) {
            throw new IllegalStateException("CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD must contain at least 12 characters");
        }
        if (users.findByEmail(email).isPresent()) {
            LOG.info("Bootstrap administrator already exists; no account or password was changed");
            return;
        }
        users.insert(new BenchmarkUser("admin_" + UUID.randomUUID(), email, name, "admin",
                passwords.encode(password), null, Instant.now().toString()));
        LOG.info("Bootstrap administrator created for {}", email);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}

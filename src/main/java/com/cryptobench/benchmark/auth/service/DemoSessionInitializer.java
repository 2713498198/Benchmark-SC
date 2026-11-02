package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.repository.SessionRepository;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.config.BenchmarkProperties;
import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Revokes persistent demo sessions when a deployment disables demo login. */
@Component
public class DemoSessionInitializer implements ApplicationRunner {
    private final BenchmarkProperties properties;
    private final UserRepository users;
    private final SessionRepository sessions;

    public DemoSessionInitializer(BenchmarkProperties properties, UserRepository users, SessionRepository sessions) {
        this.properties = properties;
        this.users = users;
        this.sessions = sessions;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.demoEnabled()) return;
        Set<String> userIds = new HashSet<>(Set.of("demo_admin", "demo_researcher"));
        // Demo login also reuses existing accounts at these exact addresses.
        for (String email : Set.of("admin@cryptobench.local", "researcher@cryptobench.local")) {
            users.findByEmail(email).ifPresent(user -> userIds.add(user.id()));
        }
        userIds.forEach(sessions::deleteByUser);
    }
}

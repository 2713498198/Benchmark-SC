package com.cryptobench.benchmark.auth.service;

import static org.assertj.core.api.Assertions.*;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DesktopAdminServiceTest {
    @Test void legacyDemoDoesNotSuppressSetupAndExistingUsersAreNeverPromoted() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:desktop-admin-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        var users = new UserRepository(new JdbcTemplate(source));
        var passwords = new PasswordService();
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var service = new DesktopAdminService(users, passwords, validation.getValidator());
            users.insert(new BenchmarkUser("demo_admin", "admin@cryptobench.local", "Legacy", "admin", "old-hash", null, Instant.now().toString()));
            users.insert(new BenchmarkUser("researcher", "existing@example.test", "Existing", "researcher", "unchanged", null, Instant.now().toString()));
            assertThat(service.needsSetup()).isTrue();
            assertThatThrownBy(() -> service.create("Admin", "admin@cryptobench.local", "valid-password"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.create("Admin", "existing@example.test", "valid-password"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.create("Admin", "bad-address", "short"))
                    .isInstanceOf(IllegalArgumentException.class);
            service.create(" Real admin ", " New-Admin@Example.test ", "valid-password");
            var admin = users.findByEmail("new-admin@example.test").orElseThrow();
            assertThat(admin.role()).isEqualTo("admin");
            assertThat(admin.name()).isEqualTo("Real admin");
            assertThat(passwords.matches("valid-password", admin)).isTrue();
            assertThat(service.needsSetup()).isFalse();
            service.create("Another", "other@example.test", "valid-password");
            assertThat(users.findByEmail("other@example.test")).isEmpty();
            assertThat(users.findByEmail("existing@example.test").orElseThrow().passwordHash()).isEqualTo("unchanged");
            assertThat(users.findByEmail("existing@example.test").orElseThrow().role()).isEqualTo("researcher");
        }
    }
}

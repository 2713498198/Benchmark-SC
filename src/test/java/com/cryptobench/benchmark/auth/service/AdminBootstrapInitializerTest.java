package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.config.AdminBootstrapProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapInitializerTest {
    @Mock UserRepository users;
    @Mock PasswordService passwords;

    @Test
    void missingConfigurationDoesNothing() {
        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(users, passwords,
                new AdminBootstrapProperties("", "", ""));

        initializer.run(null);

        verifyNoInteractions(users, passwords);
    }

    @Test
    void rejectsBootstrapPasswordShorterThanTwelveCharacters() {
        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(users, passwords,
                new AdminBootstrapProperties("admin@example.com", "Admin", "short-password".substring(0, 11)));

        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12 characters");
        verifyNoInteractions(users, passwords);
    }

    @Test
    void existingEmailIsNeverOverwritten() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(
                new BenchmarkUser("existing", "admin@example.com", "Existing", "researcher",
                        "hash", null, "2026-01-01T00:00:00Z")));
        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(users, passwords,
                new AdminBootstrapProperties(" Admin@example.com ", "New Name", "long-enough-password"));

        initializer.run(null);

        verify(users).findByEmail("admin@example.com");
        verify(users, never()).insert(any());
        verifyNoInteractions(passwords);
    }

    @Test
    void createsAdminWithNormalizedEmailAndHashedPassword() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(passwords.encode("long-enough-password")).thenReturn("{pbkdf2}hash");
        AdminBootstrapInitializer initializer = new AdminBootstrapInitializer(users, passwords,
                new AdminBootstrapProperties(" Admin@example.com ", " Admin Name ", "long-enough-password"));

        initializer.run(null);

        ArgumentCaptor<BenchmarkUser> captor = ArgumentCaptor.forClass(BenchmarkUser.class);
        verify(users).insert(captor.capture());
        BenchmarkUser created = captor.getValue();
        assertThat(created.email()).isEqualTo("admin@example.com");
        assertThat(created.name()).isEqualTo("Admin Name");
        assertThat(created.role()).isEqualTo("admin");
        assertThat(created.passwordHash()).isEqualTo("{pbkdf2}hash");
        verify(passwords).encode("long-enough-password");
    }
}

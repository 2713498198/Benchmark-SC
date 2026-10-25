package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.PasswordResetRepository;
import com.cryptobench.benchmark.auth.repository.SessionRepository;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {
    @Mock UserRepository users;
    @Mock PasswordService passwords;
    @Mock SessionRepository sessions;
    @Mock PasswordResetRepository tokens;
    @Mock PasswordResetNotifier notifier;

    @Test
    void issuesOpaqueTokenAndConsumesItOnce() {
        BenchmarkUser user = new BenchmarkUser("u1", "a@example.com", "A", "researcher", "old", null, "now");
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(users.findById("u1")).thenReturn(Optional.of(user));
        when(passwords.encode("new-password")).thenReturn("encoded");
        when(tokens.find(any())).thenReturn(Optional.of(new PasswordResetRepository.Token("hash", "u1",
                System.currentTimeMillis() + 60_000, null)));
        when(tokens.consume(eq("hash"), anyLong())).thenReturn(1);
        PasswordResetService service = new PasswordResetService(users, passwords, sessions, tokens, notifier);

        service.request("a@example.com");
        verify(tokens).insert(anyString(), eq("u1"), anyLong(), anyString());
        service.request("a@example.com");
        verify(notifier, times(2)).send(eq(user), argThat(value -> value.matches("[a-f0-9]{64}")), any());

        service.reset("a".repeat(64), "new-password");
        verify(users).updatePassword("u1", "encoded");
        verify(sessions).deleteByUser("u1");
    }

    @Test
    void rejectsExpiredOrAlreadyUsedToken() {
        when(tokens.find(any())).thenReturn(Optional.of(new PasswordResetRepository.Token("hash", "u1",
                System.currentTimeMillis() - 1, null)));
        PasswordResetService service = new PasswordResetService(users, passwords, sessions, tokens, notifier);
        assertThatThrownBy(() -> service.reset("a".repeat(64), "new-password"))
                .hasMessageContaining("无效或已过期");
        verifyNoInteractions(users, passwords, sessions);
    }
}

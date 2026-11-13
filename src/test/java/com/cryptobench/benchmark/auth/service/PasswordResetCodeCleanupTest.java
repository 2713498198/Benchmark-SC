package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.repository.PasswordResetCodeRepository;
import com.cryptobench.benchmark.auth.repository.SessionRepository;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.config.PasswordResetCodeProperties;
import com.cryptobench.benchmark.mail.MailCipher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResetCodeCleanupTest {
    @Test void aLongerConfiguredResendWindowSurvivesCleanupOnBothPublicOperations() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:reset-cleanup-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("src/main/resources/db/migration/V11__password_reset_codes.sql"));
        }
        var jdbc = new JdbcTemplate(source);
        var repository = new PasswordResetCodeRepository(jdbc);
        var notifier = mock(PasswordResetNotifier.class);
        when(notifier.ready()).thenReturn(true);
        var properties = new PasswordResetCodeProperties(); properties.setResendSeconds(7200);
        var service = new PasswordResetCodeService(repository, mock(UserRepository.class),
                mock(SessionRepository.class), mock(PasswordService.class), notifier, mock(MailCipher.class), properties);
        long now = System.currentTimeMillis();
        repository.event("send_email", "retained", now - 5_400_000L);
        repository.event("send_email", "expired", now - 7_201_000L);
        assertThatThrownBy(() -> service.verify("invalid", "000000", "127.0.0.2")).isInstanceOf(PasswordResetFlowException.class);
        assertThat(repository.retryAfter("send_email", "retained", System.currentTimeMillis(), 7_200_000L, 1)).isBetween(1L, 1800L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_rate_events WHERE subject_hash='expired'", Integer.class)).isZero();

        // An admitted request must consult the same older record rather than quietly sending again.
        String email = "cooldown@example.test";
        String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(email.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        repository.event("send_email", key, now - 5_400_000L);
        assertThatThrownBy(() -> service.request(email, "127.0.0.3"))
                .isInstanceOfSatisfying(PasswordResetFlowException.class, error -> {
                    assertThat(error.code()).isEqualTo("reset_rate_limited");
                    assertThat(error.retryAfterSeconds()).isBetween(1L, 1800L);
                });
        verify(notifier, never()).sendCode(anyString(), anyString(), anyString(), any(), anyString());
        jdbc.execute("SHUTDOWN");
    }
}

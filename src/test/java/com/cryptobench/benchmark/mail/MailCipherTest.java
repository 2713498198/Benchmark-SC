package com.cryptobench.benchmark.mail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class MailCipherTest {
    @Test void codesAreBoundToChallengeAndUserAndSurviveRestartAndKeyRotation() {
        MailProperties original = new MailProperties();
        original.setKey(Base64.getEncoder().encodeToString(new byte[32]));
        MailCipher cipher = new MailCipher(original, new MockEnvironment());
        var digest = cipher.codeDigest("challenge-a", "user-a", "003917");
        assertThat(digest.value()).hasSize(64).doesNotContain("003917");
        assertThat(cipher.verifyCode("challenge-a", "user-a", "003917", digest.value(), digest.keyId())).isTrue();
        assertThat(cipher.verifyCode("challenge-b", "user-a", "003917", digest.value(), digest.keyId())).isFalse();
        assertThat(cipher.verifyCode("challenge-a", "user-b", "003917", digest.value(), digest.keyId())).isFalse();
        assertThat(cipher.verifyCode("challenge-a", "user-a", "3917", digest.value(), digest.keyId())).isFalse();
        assertThat(cipher.verifyCode("challenge-a", "user-a", "003918", digest.value(), digest.keyId())).isFalse();
        assertThat(new MailCipher(original, new MockEnvironment()).verifyCode("challenge-a", "user-a", "003917", digest.value(), digest.keyId())).isTrue();
        byte[] replacement = new byte[32]; replacement[0] = 1;
        MailProperties rotated = new MailProperties(); rotated.setKey(Base64.getEncoder().encodeToString(replacement));
        rotated.getPreviousKeys().put(digest.keyId(), original.getKey());
        var fresh = new MailCipher(rotated, new MockEnvironment());
        assertThat(fresh.verifyCode("challenge-a", "user-a", "003917", digest.value(), digest.keyId())).isTrue();
        assertThat(fresh.codeDigest("challenge-a", "user-a", "003917").value()).isNotEqualTo(digest.value());
        rotated.getPreviousKeys().clear();
        assertThatThrownBy(() -> fresh.verifyCode("challenge-a", "user-a", "003917", digest.value(), digest.keyId()))
                .isInstanceOf(MailCipher.CodeKeyUnavailableException.class).hasMessage("reset_code_key_unavailable");
    }
    @TempDir Path temp;
    @Test void localKeySurvivesRestartAndCiphertextIsBoundToItsMessage() throws Exception {
        MailProperties properties = new MailProperties(); properties.setKeyFile(temp.resolve("private/key").toString());
        MailCipher first = new MailCipher(properties, new MockEnvironment());
        var encrypted = first.encrypt("message1", "admin_invite", "invite1", "secret-token");
        assertThat(encrypted.ciphertext()).doesNotContain("secret-token");
        MailCipher restarted = new MailCipher(properties, new MockEnvironment());
        assertThat(restarted.decrypt("message1", "admin_invite", "invite1", encrypted)).isEqualTo("secret-token");
        assertThatThrownBy(() -> restarted.decrypt("message2", "admin_invite", "invite1", encrypted)).hasMessage("mail_decryption_failed");
        assertThat(Files.readString(Path.of(properties.getKeyFile()))).doesNotContain("secret-token");
    }
    @Test void productionRequiresAnExistingSecretAndCanReadRotatedKeys() {
        MockEnvironment production = new MockEnvironment(); production.setActiveProfiles("prod");
        MailProperties missing = new MailProperties(); missing.setKeyFile(temp.resolve("missing").toString());
        assertThatThrownBy(() -> new MailCipher(missing, production).checkReady()).hasMessage("mail_key_unavailable");
        assertThat(temp.resolve("missing")).doesNotExist();
        MailProperties old = new MailProperties(); old.setKey(Base64.getEncoder().encodeToString(new byte[32]));
        var message = new MailCipher(old, production).encrypt("message1", "password_reset_code", "reset1", "token");
        MailProperties fresh = new MailProperties(); byte[] next = new byte[32]; next[0] = 1;
        fresh.setKey(Base64.getEncoder().encodeToString(next));
        fresh.getPreviousKeys().put(message.keyId(), old.getKey());
        assertThat(new MailCipher(fresh, production).decrypt("message1", "password_reset_code", "reset1", message)).isEqualTo("token");
        fresh.getPreviousKeys().clear();
        assertThatThrownBy(() -> new MailCipher(fresh, production).decrypt("message1", "password_reset_code", "reset1", message)).hasMessage("mail_decryption_failed");
    }
    @Test void autoOnlyMigratesExplicitLegacyGatewayOrOutboxSettings() {
        MailProperties properties = new MailProperties(); properties.setSmtpHost("smtp.example.com");
        MockEnvironment environment = new MockEnvironment();
        MailSettings settings = new MailSettings(properties, environment);
        assertThat(settings.mode()).isEqualTo("disabled");
        environment.setProperty("benchmark.password-reset.outbox", "local.jsonl");
        assertThat(settings.mode()).isEqualTo("outbox");
        environment.setProperty("benchmark.password-reset.delivery-url", "http://127.0.0.1:1234/mail");
        assertThat(settings.mode()).isEqualTo("http");
        properties.setMode("smtp"); assertThat(settings.mode()).isEqualTo("smtp");
    }
}

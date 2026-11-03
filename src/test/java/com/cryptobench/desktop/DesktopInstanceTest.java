package com.cryptobench.desktop;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopInstanceTest {
    @TempDir Path temporary;

    @Test void ownershipActivationAndReacquisition() throws Exception {
        DesktopPaths paths = new DesktopPaths(temporary);
        paths.prepare();
        try (var owner = DesktopInstance.acquire(paths.run)) {
            assertThat(owner).isNotNull();
            assertThat(DesktopInstance.acquire(paths.run)).isNull();
            assertThat(DesktopInstance.request(paths.run, "status", Duration.ofSeconds(1))).isEqualTo("STARTING");
            owner.onCommand(command -> command.equals("open") ? "OPENED" : "READY");
            assertThat(DesktopInstance.request(paths.run, "open", Duration.ofSeconds(1))).isEqualTo("OPENED");
        }
        assertThat(paths.run.resolve("instance.properties")).doesNotExist();
        try (var next = DesktopInstance.acquire(paths.run)) { assertThat(next).isNotNull(); }
    }

    @Test void staleDescriptorDoesNotPreventRestartAndWrongTokenCannotControlOwner() throws Exception {
        DesktopPaths paths = new DesktopPaths(temporary);
        paths.prepare();
        Files.writeString(paths.run.resolve("instance.properties"), "port=1\ntoken=stale\n");
        try (var owner = DesktopInstance.acquire(paths.run)) {
            owner.onCommand(command -> "OPENED");
            Properties properties = new Properties();
            try (var input = Files.newInputStream(paths.run.resolve("instance.properties"))) { properties.load(input); }
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(properties.getProperty("port")))) {
                var output = new DataOutputStream(socket.getOutputStream());
                output.writeUTF("wrong-token"); output.writeUTF("stop"); output.flush();
                assertThat(new DataInputStream(socket.getInputStream()).readUTF()).isEqualTo("DENIED");
            }
            assertThat(DesktopInstance.request(paths.run, "open", Duration.ofSeconds(1))).isEqualTo("OPENED");
        }
    }

    @Test void pathsAreIndependentOfWorkingDirectoryAndLogsRedactSecrets() throws Exception {
        DesktopPaths paths = new DesktopPaths(temporary.resolve("中文 空格"));
        paths.prepare();
        assertThat(paths.logs).isEqualTo(temporary.resolve("中文 空格/var/logs"));
        assertThat(DesktopRedactionConverter.redact("password=abc123 token: secret-value {\"api_key\":\"sensitive\"}"))
                .doesNotContain("abc123", "secret-value", "sensitive").contains("[REDACTED]");
        assertThat(DesktopRedactionConverter.redact("Authorization: Bearer private-auth-token"))
                .doesNotContain("private-auth-token");
    }

    @Test void resolvesUserConfiguredRelativePathsBeforeLoggingAndKeepsDesktopLocal() {
        DesktopPaths paths = new DesktopPaths(temporary.resolve("application"));
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("logging.file.name", "var/logs/custom.log")
                .withProperty("benchmark.mail.key-file", "var/secrets/mail.key")
                .withProperty("spring.datasource.url", "jdbc:h2:file:./var/data/custom;MODE=MySQL")
                .withProperty("server.address", "0.0.0.0").withProperty("benchmark.demo-enabled", "true");
        DesktopEnvironment.normalize(environment, paths);
        assertThat(environment.getProperty("logging.file.name")).isEqualTo(paths.home.resolve("var/logs/custom.log").toString());
        assertThat(environment.getProperty("benchmark.mail.key-file")).isEqualTo(paths.home.resolve("var/secrets/mail.key").toString());
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:file:" + paths.home.resolve("var/data/custom").toString().replace('\\', '/') + ";MODE=MySQL");
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("benchmark.demo-enabled")).isEqualTo("false");
    }
}

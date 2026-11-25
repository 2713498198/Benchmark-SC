package com.cryptobench.benchmark;

import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.config.RunnerPackageProperties;
import com.cryptobench.benchmark.runner.service.RunnerPackageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerPackageServiceTest {
    @TempDir
    Path directory;

    @Test
    void loadsManifestAndArchiveFromConfiguredDirectory() throws Exception {
        byte[] archive = "portable-runner".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(directory.resolve("CryptoBench-Runner-windows-x64-1.1.0.zip"), archive);
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(archive));
        Files.writeString(directory.resolve("runner-package-manifest.json"), """
                {
                  "schema": "cryptobench-runner-package-v1",
                  "runnerVersion": "1.1.0",
                  "platform": "windows-x64",
                  "nodeVersion": "24.22.0",
                  "sourceCommit": "abc",
                  "filename": "CryptoBench-Runner-windows-x64-1.1.0.zip",
                  "bytes": 15,
                  "sha256": "%s"
                }
                """.formatted(hash));

        RunnerPackageService service = new RunnerPackageService(
                new RunnerPackageProperties(directory.toString()), new ObjectMapper());

        assertThat(service.manifest().path("runnerVersion").asText()).isEqualTo("1.1.0");
        assertThat(service.artifact().bytes()).isEqualTo(archive.length);
        assertThat(service.artifact().sha256()).isEqualTo(hash);
    }

    @Test
    void rejectsTraversalAndMissingPackages() throws Exception {
        Files.writeString(directory.resolve("runner-package-manifest.json"), """
                {
                  "schema": "cryptobench-runner-package-v1",
                  "filename": "../escape.zip",
                  "bytes": 1,
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
                """);
        RunnerPackageService service = new RunnerPackageService(
                new RunnerPackageProperties(directory.toString()), new ObjectMapper());

        assertThatThrownBy(service::artifact)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("尚未构建");
        RunnerPackageService missing = new RunnerPackageService(
                new RunnerPackageProperties(directory.resolve("missing").toString()), new ObjectMapper());
        assertThatThrownBy(missing::manifest).isInstanceOf(ApiException.class);
    }
}

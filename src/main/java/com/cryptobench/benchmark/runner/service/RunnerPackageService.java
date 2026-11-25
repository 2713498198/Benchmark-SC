package com.cryptobench.benchmark.runner.service;

import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.config.RunnerPackageProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Loads the release-built Runner package without exposing arbitrary server paths. */
@Service
public class RunnerPackageService {
    private static final String SCHEMA = "cryptobench-runner-package-v1";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private final RunnerPackageProperties properties;
    private final ObjectMapper mapper;
    private volatile CachedHash cachedHash;

    public RunnerPackageService(RunnerPackageProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public ObjectNode manifest() {
        return artifact().manifest();
    }

    public Artifact artifact() {
        String configured = properties.directory();
        if (configured == null || configured.isBlank()) {
            throw unavailable();
        }
        Path root = Path.of(configured.trim()).toAbsolutePath().normalize();
        Path manifestPath = root.resolve("runner-package-manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw unavailable();
        }
        try {
            JsonNode value = mapper.readTree(manifestPath.toFile());
            if (!value.isObject() || !SCHEMA.equals(value.path("schema").asText())) {
                throw unavailable();
            }
            String filename = value.path("filename").asText("");
            String sha256 = value.path("sha256").asText("").toLowerCase();
            long bytes = value.path("bytes").asLong(-1);
            if (filename.isBlank() || !Path.of(filename).getFileName().toString().equals(filename)
                    || filename.contains("..") || !SHA256.matcher(sha256).matches() || bytes < 1) {
                throw unavailable();
            }
            Path archive = root.resolve(filename).normalize();
            if (!archive.startsWith(root) || !Files.isRegularFile(archive)
                    || Files.size(archive) != bytes) {
                throw unavailable();
            }
            FileTime modified = Files.getLastModifiedTime(archive);
            if (!hashMatches(archive, bytes, modified, sha256)) {
                throw unavailable();
            }
            return new Artifact(archive, filename, sha256, bytes, ((ObjectNode) value).deepCopy());
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "当前部署尚未构建 Runner 安装包");
    }

    private boolean hashMatches(Path path, long bytes, FileTime modified, String expected) throws IOException {
        CachedHash cached = cachedHash;
        if (cached != null && cached.path().equals(path) && cached.bytes() == bytes
                && cached.modified().equals(modified)) {
            return cached.sha256().equals(expected);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (var input = Files.newInputStream(path)) {
            for (byte[] chunk = new byte[1024 * 1024]; ; ) {
                int count = input.read(chunk);
                if (count < 0) break;
                digest.update(chunk, 0, count);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        cachedHash = new CachedHash(path, bytes, modified, actual);
        return actual.equals(expected);
    }

    public record Artifact(Path path, String filename, String sha256, long bytes, ObjectNode manifest) {
    }

    private record CachedHash(Path path, long bytes, FileTime modified, String sha256) {
    }
}

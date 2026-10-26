package com.cryptobench.benchmark.execution.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import com.cryptobench.benchmark.config.DockerProperties;
import org.springframework.stereotype.Component;

/**
 * 启动受限的 Agent 容器。容器镜像需要在 8000/tcp 提供同一套 Agent HTTP 协议。
 * 每次运行创建一个 internal Docker network，允许宿主机通过端口映射访问 Agent，
 * 同时阻止容器访问外部网络；任务结束后容器和网络都被清理。
 */
@Component
public class DockerSandboxExecutor {
    private final DockerProperties properties;

    public DockerSandboxExecutor(DockerProperties properties) {
        this.properties = properties;
    }

    public DockerRuntimeStatus status() {
        List<DockerRuntimeStatus.ImageStatus> images = new ArrayList<>();
        if (!properties.enabled()) {
            properties.allowedImagesOrEmpty().forEach(image -> images.add(new DockerRuntimeStatus.ImageStatus(image, false)));
            return new DockerRuntimeStatus(false, false, "Docker 执行来源未启用", images);
        }
        try {
            command(List.of("docker", "info", "--format", "{{.ServerVersion}}"), Duration.ofSeconds(8));
            for (String image : properties.allowedImagesOrEmpty()) {
                boolean present;
                try {
                    command(List.of("docker", "image", "inspect", image), Duration.ofSeconds(8));
                    present = true;
                } catch (Exception ignored) {
                    present = false;
                }
                images.add(new DockerRuntimeStatus.ImageStatus(image, present));
            }
            return new DockerRuntimeStatus(true, true, "Docker 引擎可用", images);
        } catch (Exception exception) {
            properties.allowedImagesOrEmpty().forEach(image -> images.add(new DockerRuntimeStatus.ImageStatus(image, false)));
            return new DockerRuntimeStatus(true, false, "Docker 引擎不可用", images);
        }
    }

    public Sandbox start(String image) throws IOException, InterruptedException {
        return start(image, "untracked");
    }

    public Sandbox start(String image, String runId) throws IOException, InterruptedException {
        if (!properties.enabled()) throw new IOException("Docker 执行来源未启用");
        if (!properties.allows(image)) throw new IOException("Docker 镜像不在允许列表中");
        if (image == null || !image.matches("[a-zA-Z0-9][a-zA-Z0-9._:/@-]{1,180}")) {
            throw new IllegalArgumentException("Docker 镜像名称无效");
        }
        String network = "cryptobench-run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String containerId = null;
        try {
            command(List.of("docker", "network", "create", "--driver", "bridge", "--internal",
                            "--label", "com.cryptobench.owner=cryptobench", "--label", "com.cryptobench.run-id=" + safeLabel(runId), network),
                    Duration.ofSeconds(20));
            String id = command(List.of("docker", "run", "-d", "--rm", "--network", network,
                    "--publish", "127.0.0.1::8000", "--cpus", "1", "--memory", "512m",
                    "--pids-limit", "128", "--read-only", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                    "--label", "com.cryptobench.owner=cryptobench", "--label", "com.cryptobench.sandbox=true",
                    "--label", "com.cryptobench.run-id=" + safeLabel(runId), "--label", "com.cryptobench.network=" + network,
                    image), Duration.ofSeconds(30)).trim();
            containerId = id;
            if (id.isBlank()) throw new IOException("Docker 未返回容器 ID");
            String port = command(List.of("docker", "port", id, "8000/tcp"), Duration.ofSeconds(10)).trim();
            int colon = port.lastIndexOf(':');
            if (colon < 0) throw new IOException("无法读取 Docker Agent 映射端口");
            String endpoint = "http://127.0.0.1:" + port.substring(colon + 1).trim();
            waitForPort(endpoint, Duration.ofSeconds(10));
            return new Sandbox(id, network, endpoint);
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (containerId != null && !containerId.isBlank()) {
                try { command(List.of("docker", "rm", "-f", containerId), Duration.ofSeconds(10)); }
                catch (Exception ignored) { }
            }
            try { command(List.of("docker", "network", "rm", network), Duration.ofSeconds(10)); }
            catch (Exception ignored) { }
            throw exception;
        }
    }

    public void stop(Sandbox sandbox) {
        if (sandbox == null) return;
        try {
            command(List.of("docker", "stop", "-t", "5", sandbox.containerId()), Duration.ofSeconds(15));
        } catch (Exception ignored) {
            try { command(List.of("docker", "rm", "-f", sandbox.containerId()), Duration.ofSeconds(10)); }
            catch (Exception ignoredForce) { }
        }
        try {
            command(List.of("docker", "network", "rm", sandbox.network()), Duration.ofSeconds(10));
        } catch (Exception ignored) {
            // 运行失败时清理是尽力而为。
        }
    }

    /** 保留按容器 ID 清理的兼容入口，供运维或旧调用使用。 */
    public void stop(String containerId) {
        try { command(List.of("docker", "stop", "-t", "5", containerId), Duration.ofSeconds(15)); }
        catch (Exception ignored) { try { command(List.of("docker", "rm", "-f", containerId), Duration.ofSeconds(10)); } catch (Exception ignoredForce) { } }
    }

    /** 只回收本应用标签的遗留资源，避免影响其他 Docker 项目。 */
    public void reapOwnedResources() {
        if (!properties.enabled()) return;
        try {
            String containers = command(List.of("docker", "ps", "-aq", "--filter", "label=com.cryptobench.owner=cryptobench"), Duration.ofSeconds(10));
            for (String id : containers.split("\\R")) if (!id.isBlank()) {
                try { command(List.of("docker", "rm", "-f", id.trim()), Duration.ofSeconds(10)); } catch (Exception ignored) { }
            }
            String networks = command(List.of("docker", "network", "ls", "-q", "--filter", "label=com.cryptobench.owner=cryptobench"), Duration.ofSeconds(10));
            for (String id : networks.split("\\R")) if (!id.isBlank()) {
                try { command(List.of("docker", "network", "rm", id.trim()), Duration.ofSeconds(10)); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
            // Docker may be unavailable during application startup; the next startup can retry.
        }
    }

    private String safeLabel(String value) {
        String label = value == null ? "untracked" : value.replaceAll("[^a-zA-Z0-9_.-]", "-");
        return label.isBlank() ? "untracked" : label.substring(0, Math.min(100, label.length()));
    }

    private String command(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.startVirtualThread(() -> {
            try {
                output.append(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The process exit status remains authoritative for command failures.
            }
        });
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            reader.join(500);
            throw new IOException("Docker 命令超时");
        }
        reader.join(500);
        if (process.exitValue() != 0) throw new IOException(output.toString().trim());
        return output.toString();
    }

    private void waitForPort(String endpoint, Duration timeout) throws IOException, InterruptedException {
        URI uri = URI.create(endpoint);
        int port = uri.getPort();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
                return;
            } catch (IOException exception) {
                Thread.sleep(100);
            }
        }
        throw new IOException("Docker Agent 端口在限定时间内未监听");
    }

    public record Sandbox(String containerId, String network, String endpoint) {
    }
}

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
 * 每次运行创建一个 internal Agent network。宿主机通过单独的、平台自有的
 * TCP 代理容器访问 Agent；Agent 容器本身没有任何可发布端口或外部网络。
 * Docker 29 会忽略 internal network 上的端口发布，因此代理容器只连接到
 * Agent internal network 和一个绑定 127.0.0.1 的代理网络。任务结束后两类
 * 容器和网络都会被清理。
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
            boolean proxyAvailable = imageExists(properties.proxyImageOrDefault());
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
            return new DockerRuntimeStatus(true, proxyAvailable,
                    proxyAvailable ? "Docker 引擎可用" : "Docker 代理镜像未找到：" + properties.proxyImageOrDefault(), images);
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
        validateImage(image);
        if (!properties.allows(image)) throw new IOException("Docker 镜像不在允许列表中");
        String proxyImage = properties.proxyImageOrDefault();
        validateImage(proxyImage);
        if (!imageExists(proxyImage)) throw new IOException("Docker 代理镜像不存在：" + proxyImage);
        String agentNetwork = "cryptobench-run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String proxyNetwork = "cryptobench-proxy-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String agentContainerId = null;
        String proxyContainerId = null;
        try {
            command(List.of("docker", "network", "create", "--driver", "bridge", "--internal",
                            "--label", "com.cryptobench.owner=cryptobench", "--label", "com.cryptobench.run-id=" + safeLabel(runId), agentNetwork),
                    Duration.ofSeconds(20));
            createProxyNetwork(proxyNetwork, runId);
            agentContainerId = command(List.of("docker", "run", "-d", "--rm", "--network", agentNetwork,
                    "--network-alias", "agent", "--cpus", "1", "--memory", "512m",
                    "--memory-swap", "512m", "--ulimit", "nofile=1024:1024",
                    "--pids-limit", "128", "--read-only", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                    "--label", "com.cryptobench.owner=cryptobench", "--label", "com.cryptobench.sandbox=true",
                    "--label", "com.cryptobench.run-id=" + safeLabel(runId), "--label", "com.cryptobench.network=" + agentNetwork,
                    image), Duration.ofSeconds(30)).trim();
            if (agentContainerId.isBlank()) throw new IOException("Docker 未返回 Agent 容器 ID");
            proxyContainerId = command(List.of("docker", "create", "--rm", "--network", proxyNetwork,
                    "--publish", "127.0.0.1::8000", "--cpus", "0.25", "--memory", "128m",
                    "--memory-swap", "128m", "--ulimit", "nofile=1024:1024", "--pids-limit", "32",
                    "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m", "--env", "TARGET_HOST=agent",
                    "--env", "TARGET_PORT=8000", "--label", "com.cryptobench.owner=cryptobench",
                    "--label", "com.cryptobench.sandbox-proxy=true", "--label", "com.cryptobench.run-id=" + safeLabel(runId),
                    "--label", "com.cryptobench.network=" + proxyNetwork, proxyImage), Duration.ofSeconds(30)).trim();
            if (proxyContainerId.isBlank()) throw new IOException("Docker 未返回代理容器 ID");
            command(List.of("docker", "network", "connect", agentNetwork, proxyContainerId), Duration.ofSeconds(15));
            command(List.of("docker", "start", proxyContainerId), Duration.ofSeconds(15));
            String port = command(List.of("docker", "port", proxyContainerId, "8000/tcp"), Duration.ofSeconds(10)).trim();
            int colon = port.lastIndexOf(':');
            if (colon < 0) throw new IOException("无法读取 Docker Agent 映射端口");
            String endpoint = "http://127.0.0.1:" + port.substring(colon + 1).trim();
            waitForPort(endpoint, Duration.ofSeconds(10));
            return new Sandbox(agentContainerId, proxyContainerId, agentNetwork, proxyNetwork, endpoint);
        } catch (IOException | InterruptedException | RuntimeException exception) {
            removeContainer(proxyContainerId);
            removeContainer(agentContainerId);
            removeNetwork(proxyNetwork);
            removeNetwork(agentNetwork);
            throw exception;
        }
    }

    public void stop(Sandbox sandbox) {
        if (sandbox == null) return;
        try {
            removeContainer(sandbox.proxyContainerId());
            removeContainer(sandbox.agentContainerId());
            removeNetwork(sandbox.proxyNetwork());
            removeNetwork(sandbox.agentNetwork());
        } catch (Exception ignored) { }
    }

    /** 保留按容器 ID 清理的兼容入口，供运维或旧调用使用。 */
    public void stop(String containerId) {
        removeContainer(containerId);
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

    private boolean imageExists(String image) {
        try {
            command(List.of("docker", "image", "inspect", image), Duration.ofSeconds(8));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Docker 28+ supports routed gateway mode and an explicit no-masquerade
     * bridge. Older Docker engines reject those options. The Agent remains on
     * the internal network in either case; the fallback only changes the
     * platform-owned proxy network, which never contains the Agent directly.
     */
    private void createProxyNetwork(String network, String runId) throws IOException, InterruptedException {
        String owner = safeLabel(runId);
        if ("compatibility".equalsIgnoreCase(System.getenv("CRYPTOBENCH_DOCKER_PROXY_NETWORK_MODE"))) {
            command(List.of("docker", "network", "create", "--driver", "bridge",
                            "--label", "com.cryptobench.owner=cryptobench", "--label", "com.cryptobench.run-id=" + owner,
                            network), Duration.ofSeconds(20));
            return;
        }
        List<String> advanced = List.of("docker", "network", "create", "--driver", "bridge",
                "--opt", "com.docker.network.bridge.gateway_mode_ipv4=routed",
                "--opt", "com.docker.network.bridge.enable_ip_masquerade=false",
                "--label", "com.cryptobench.owner=cryptobench", "--label", "com.cryptobench.run-id=" + owner,
                network);
        try {
            command(advanced, Duration.ofSeconds(20));
            String options = command(List.of("docker", "network", "inspect", "--format", "{{json .Options}}", network),
                    Duration.ofSeconds(10)).trim();
            if (!options.contains("com.docker.network.bridge.gateway_mode_ipv4\":\"routed")
                    || !options.contains("com.docker.network.bridge.enable_ip_masquerade\":\"false")) {
                throw new IOException("Docker 未保留代理网络选项");
            }
        } catch (IOException unsupported) {
            removeNetwork(network);
            command(List.of("docker", "network", "create", "--driver", "bridge",
                            "--label", "com.cryptobench.owner=cryptobench", "--label", "com.cryptobench.run-id=" + owner,
                            network), Duration.ofSeconds(20));
        }
    }

    private void validateImage(String image) {
        if (image == null || !image.matches("[a-zA-Z0-9][a-zA-Z0-9._:/@-]{1,180}")) {
            throw new IllegalArgumentException("Docker 镜像名称无效");
        }
    }

    private void removeContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) return;
        try { command(List.of("docker", "rm", "-f", containerId), Duration.ofSeconds(10)); }
        catch (Exception ignored) { }
    }

    private void removeNetwork(String network) {
        if (network == null || network.isBlank()) return;
        try { command(List.of("docker", "network", "rm", network), Duration.ofSeconds(10)); }
        catch (Exception ignored) { }
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

    public record Sandbox(String agentContainerId, String proxyContainerId, String agentNetwork,
                          String proxyNetwork, String endpoint) {
    }
}

package com.cryptobench.benchmark.gateway;

import com.cryptobench.benchmark.config.DockerProperties;
import com.cryptobench.benchmark.execution.service.DockerSandboxExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Starts a run-scoped L7 gateway. Provider credentials travel only over attached stdin and process memory. */
@Component
public class ModelGatewayService {
    private final GatewayProperties properties;
    private final DockerProperties docker;
    private final ObjectMapper mapper = new ObjectMapper();
    public ModelGatewayService(GatewayProperties properties, DockerProperties docker) { this.properties = properties; this.docker = docker; }

    public Session openRun(String runId, DockerSandboxExecutor.Sandbox sandbox, String providerId, String apiKey)
            throws IOException, InterruptedException {
        if (!properties.enabled()) throw new IOException("模型网关未启用");
        if (sandbox == null) throw new IOException("模型网关需要本次运行的 Docker 沙箱");
        GatewayProperties.Provider provider = properties.providersOrEmpty().stream().filter(p -> p.id().equals(providerId))
                .findFirst().orElseThrow(() -> new IOException("供应商不在管理员允许列表中"));
        validateProvider(provider);
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 16000 || apiKey.contains("\n") || apiKey.contains("\r")) throw new IOException("请输入有效的供应商 API Key");
        String image = properties.imageOrDefault();
        if (!image.matches("[a-zA-Z0-9][a-zA-Z0-9._:/@-]{1,180}")) throw new IOException("模型网关镜像无效");
        command(List.of("docker", "image", "inspect", image), Duration.ofSeconds(8));
        var agentInspection = mapper.readTree(command(List.of("docker", "inspect", sandbox.agentContainerId()), Duration.ofSeconds(8)));
        String instance = agentInspection.path(0).path("Config").path("Labels").path("com.cryptobench.instance").asText();
        if (!instance.matches("[a-zA-Z0-9_.-]{1,100}")) throw new IOException("沙箱实例标识缺失");
        String network = "cryptobench-model-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String container = null; Process attached = null;
        try {
            command(List.of("docker", "network", "create", "--driver", "bridge", "--label", "com.cryptobench.owner=cryptobench",
                    "--label", "com.cryptobench.instance=" + instance, "--label", "com.cryptobench.run-id=" + label(runId), network), Duration.ofSeconds(20));
            container = command(List.of("docker", "create", "-i", "--rm", "--network", network,
                    "--publish", docker.proxyBindAddressOrDefault() + "::8011", "--cpus", "0.5", "--memory", "192m",
                    "--memory-swap", "192m", "--pids-limit", "32", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m", "--label", "com.cryptobench.owner=cryptobench",
                    "--label", "com.cryptobench.instance=" + instance, "--label", "com.cryptobench.model-gateway=true",
                    "--label", "com.cryptobench.run-id=" + label(runId), image), Duration.ofSeconds(20)).trim();
            command(List.of("docker", "network", "connect", "--alias", "model-gateway", sandbox.agentNetwork(), container), Duration.ofSeconds(15));
            String controlToken = token();
            ObjectNode bootstrap = mapper.createObjectNode(); bootstrap.put("controlToken", controlToken);
            ObjectNode upstream = bootstrap.putObject("provider"); upstream.put("id", provider.id()); upstream.put("baseUrl", provider.baseUrl());
            upstream.put("apiKey", apiKey); upstream.set("models", mapper.valueToTree(provider.modelsOrEmpty()));
            upstream.set("priceModelIds", mapper.valueToTree(provider.pricingOrEmpty()));
            attached = new ProcessBuilder("docker", "start", "-ai", container).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var input = attached.getOutputStream()) { input.write((mapper.writeValueAsString(bootstrap) + "\n").getBytes(StandardCharsets.UTF_8)); }
            upstream.remove("apiKey"); bootstrap.removeAll();
            String port = "";
            for (int i = 0; i < 30 && port.isBlank(); i++) {
                if (!attached.isAlive()) throw new IOException("模型网关启动失败");
                try { port = command(List.of("docker", "port", container, "8011/tcp"), Duration.ofSeconds(4)).trim(); }
                catch (IOException starting) { if (i == 29) throw starting; }
                if (port.isBlank()) Thread.sleep(100);
            }
            int colon = port.lastIndexOf(':');
            if (colon < 0) throw new IOException("无法读取网关管理端口");
            Session session = new Session(container, network, attached, "http://" + docker.endpointHostOrDefault() + ":" + port.substring(colon + 1).trim(), controlToken);
            for (int i = 0; i < 30; i++) {
                try { session.request("GET", "/control/health", null); return session; }
                catch (IOException notReady) { if (i == 29) throw new IOException("模型网关未就绪"); Thread.sleep(100); }
            }
            throw new IOException("模型网关未就绪");
        } catch (IOException | InterruptedException | RuntimeException exception) {
            remove("container", container); remove("network", network); if (attached != null) attached.destroyForcibly(); throw exception;
        }
    }

    public final class Session implements AutoCloseable {
        private final String container, network, endpoint;
        private String controlToken;
        private final Process attached;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        private volatile boolean closed;
        private Session(String container, String network, Process attached, String endpoint, String controlToken) {
            this.container=container;this.network=network;this.attached=attached;this.endpoint=endpoint;this.controlToken=controlToken;
        }
        public ObjectNode beginTask(String taskId, String model, int maxCalls, Instant deadline) throws IOException, InterruptedException {
            if (closed) throw new IOException("模型网关已关闭");
            ObjectNode body = mapper.createObjectNode().put("taskId", taskId).put("model", model).put("maxCalls", maxCalls).put("deadline", deadline.toString());
            return request("POST", "/control/begin", body);
        }
        public ObjectNode finishTask(String taskId) throws IOException, InterruptedException {
            if (closed) throw new IOException("模型网关已关闭");
            return request("POST", "/control/finish", mapper.createObjectNode().put("taskId", taskId));
        }
        private ObjectNode request(String method, String path, ObjectNode input) throws IOException, InterruptedException {
            var builder = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + controlToken);
            if (input == null) builder.GET(); else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(input.toString()));
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("模型网关请求失败（HTTP " + response.statusCode() + "）");
            var parsed = mapper.readTree(response.body());
            if (!(parsed instanceof ObjectNode object)) throw new IOException("模型网关响应无效");
            return object;
        }
        @Override public synchronized void close() {
            if (closed) return;closed=true;
            try { request("POST", "/control/close", mapper.createObjectNode()); } catch (Exception ignored) { }
            remove("container", container); remove("network", network); attached.destroyForcibly(); controlToken="";
        }
    }

    private static void validateProvider(GatewayProperties.Provider provider) throws IOException {
        try {
            URI uri=URI.create(provider.baseUrl());
            boolean localHttp="http".equals(uri.getScheme()) && List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
            if ((!"https".equals(uri.getScheme())&&!localHttp)||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                    ||provider.modelsOrEmpty().isEmpty()||provider.modelsOrEmpty().stream().anyMatch(m->m==null||m.isBlank()||m.length()>160)
                    ||provider.pricingOrEmpty().entrySet().stream().anyMatch(e -> !provider.modelsOrEmpty().contains(e.getKey()) || e.getValue()==null || e.getValue().isBlank() || e.getValue().length()>160)) throw new IllegalArgumentException();
        } catch (RuntimeException exception) { throw new IOException("管理员模型网关配置无效"); }
    }
    private static String token() { byte[] value=new byte[32];new SecureRandom().nextBytes(value);return HexFormat.of().formatHex(value); }
    private static String label(String value) { return value == null ? "untracked" : value.replaceAll("[^a-zA-Z0-9_.-]", "-").substring(0, Math.min(100,value.length())); }
    private static void remove(String kind,String id) { if(id==null||id.isBlank())return;try{command(kind.equals("container")?List.of("docker","rm","-f",id):List.of("docker","network","rm",id),Duration.ofSeconds(10));}catch(Exception ignored){} }
    private static String command(List<String> args, Duration timeout) throws IOException, InterruptedException {
        Process process=new ProcessBuilder(new ArrayList<>(args)).redirectErrorStream(true).start();
        var bytes=new java.io.ByteArrayOutputStream();Thread reader=Thread.startVirtualThread(()->{try{process.getInputStream().transferTo(bytes);}catch(IOException ignored){}});
        if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){process.destroyForcibly();throw new IOException("Docker 网关操作超时");}
        reader.join(500);if(process.exitValue()!=0)throw new IOException("Docker 网关操作失败");return bytes.toString(StandardCharsets.UTF_8);
    }
}

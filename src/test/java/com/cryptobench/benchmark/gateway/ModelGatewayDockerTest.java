package com.cryptobench.benchmark.gateway;

import com.cryptobench.benchmark.config.DockerProperties;
import com.cryptobench.benchmark.execution.service.DockerSandboxExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in, disposable Docker resources only. Provider is a fake loopback server inside the gateway. */
@EnabledIfEnvironmentVariable(named = "CRYPTOBENCH_GATEWAY_DOCKER_TEST", matches = "1")
class ModelGatewayDockerTest {
    @Test
    void forwardsThroughInternalNetworkWithoutPersistingProviderKeyAndCleansOnlyItsOwnInstance() throws Exception {
        var mapper = new ObjectMapper();
        String instance = "gateway-test-" + UUID.randomUUID(), runId = "run-" + UUID.randomUUID();
        var docker = new DockerProperties(true, List.of("cryptobench-mock-agent:local"), "cryptobench-agent-proxy:local", "127.0.0.1", "127.0.0.1");
        var executor = new DockerSandboxExecutor(docker, instance);
        var other = new DockerSandboxExecutor(docker, instance + "-other");
        var provider = new GatewayProperties.Provider("fake", "Test only", "http://127.0.0.1:18081/v1", List.of("fixture-model"), Map.of("fixture-model", "priced-fixture"));
        var service = new ModelGatewayService(new GatewayProperties(true, "cryptobench-model-gateway:local", List.of(provider)), docker);
        DockerSandboxExecutor.Sandbox sandbox = null;
        ModelGatewayService.Session session = null;
        try {
            sandbox = executor.start("cryptobench-mock-agent:local", runId);
            session = service.openRun(runId, sandbox, "fake", "dummy-provider-key-for-test");
            String gateway = docker(List.of("ps", "-q", "--filter", "label=com.cryptobench.instance=" + instance,
                    "--filter", "label=com.cryptobench.model-gateway=true"), null).trim();
            assertThat(gateway).isNotBlank();
            String inspect = docker(List.of("inspect", gateway), null);
            assertThat(inspect).doesNotContain("dummy-provider-key-for-test");
            docker(List.of("exec", "-d", gateway, "node", "-e",
                    "require('http').createServer((q,s)=>{q.resume();q.on('end',()=>{s.setHeader('content-type','application/json');s.end(JSON.stringify({choices:[{message:{content:'ok'}}],usage:{prompt_tokens:11,completion_tokens:7}}));});}).listen(18081,'127.0.0.1')"), null);
            other.reapOwnedResources();
            executor.reapInactiveResources(java.util.Set.of(runId));
            assertThat(docker(List.of("inspect", "--format", "{{.State.Running}}", gateway), null).trim()).isEqualTo("true");
            var grant = session.beginTask("task-a", "fixture-model", 20, Instant.now().plusSeconds(30));
            assertThat(grant.path("priceModelId").asText()).isEqualTo("priced-fixture");
            String script = "(async()=>{const g=" + grant + ";const r=await fetch(g.baseUrl+'/chat/completions',{method:'POST',headers:{authorization:'Bearer '+g.token,'content-type':'application/json'},body:JSON.stringify({model:g.model,messages:[{role:'user',content:'fixture'}]})});if(r.status!==200)process.exit(3);console.log(await r.text());})().catch(()=>process.exit(4));";
            var response = mapper.readTree(docker(List.of("exec", "-i", sandbox.agentContainerId(), "node", "-"), script));
            assertThat(response.path("choices").get(0).path("message").path("content").asText()).isEqualTo("ok");
            var usage = session.finishTask("task-a");
            assertThat(usage.path("usageComplete").asBoolean()).isTrue();
            assertThat(usage.path("inputTokens").asLong()).isEqualTo(11);
            assertThat(usage.path("outputTokens").asLong()).isEqualTo(7);
            var network = mapper.readTree(docker(List.of("network", "inspect", sandbox.agentNetwork()), null));
            assertThat(network.get(0).path("Internal").asBoolean()).isTrue();
            var container = mapper.readTree(docker(List.of("inspect", sandbox.agentContainerId()), null));
            assertThat(container.get(0).path("NetworkSettings").path("Networks").size()).isEqualTo(1);
            executor.reapInactiveResources(java.util.Set.of());
            assertThat(docker(List.of("ps", "-aq", "--filter", "label=com.cryptobench.instance=" + instance), null).trim()).isEmpty();
            assertThat(docker(List.of("network", "ls", "-q", "--filter", "label=com.cryptobench.instance=" + instance), null).trim()).isEmpty();
        } finally {
            if (session != null) session.close();
            executor.stop(sandbox);
            executor.reapOwnedResources();
        }
        assertThat(docker(List.of("ps", "-aq", "--filter", "label=com.cryptobench.instance=" + instance), null).trim()).isEmpty();
        assertThat(docker(List.of("network", "ls", "-q", "--filter", "label=com.cryptobench.instance=" + instance), null).trim()).isEmpty();
    }

    private static String docker(List<String> args, String stdin) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("docker"); command.addAll(args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var output = process.getOutputStream()) { if (stdin != null) output.write(stdin.getBytes(StandardCharsets.UTF_8)); }
        var bytes = new java.io.ByteArrayOutputStream();
        Thread reader = Thread.startVirtualThread(() -> { try { process.getInputStream().transferTo(bytes); } catch (Exception ignored) { } });
        assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue(); reader.join(1000);
        assertThat(process.exitValue()).as("Docker fixture command succeeded").isZero();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}

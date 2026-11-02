package com.cryptobench.benchmark;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.dataset.service.DatasetService;
import com.cryptobench.benchmark.execution.repository.RunRepository;
import com.cryptobench.benchmark.execution.service.AgentHttpClient;
import com.cryptobench.benchmark.execution.service.DockerSandboxExecutor;
import com.cryptobench.benchmark.execution.service.RunService;
import com.cryptobench.benchmark.gateway.ModelGatewayService;
import com.cryptobench.benchmark.pricing.service.PricingService;
import com.cryptobench.benchmark.report.service.ReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises real run persistence/scoring/accounting, with no Docker engine or paid provider calls. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gateway-run-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "benchmark.demo-enabled=false", "benchmark.mail.worker-enabled=false", "benchmark.mail.mode=disabled",
        "benchmark.gateway.enabled=true",
        "benchmark.gateway.providers-json=[{\"id\":\"fixture\",\"name\":\"Test provider\",\"baseUrl\":\"https://provider.example.test/v1\",\"models\":[\"provider-model\"],\"priceModelIds\":{\"provider-model\":\"priced-model\"}}]"
})
@ActiveProfiles("test")
class GatewayRunIntegrationTest {
    @Autowired RunService runs;
    @Autowired RunRepository repository;
    @Autowired UserRepository users;
    @Autowired DatasetService datasets;
    @Autowired PricingService prices;
    @Autowired ReportService reports;
    @Autowired ObjectMapper mapper;
    @MockitoBean DockerSandboxExecutor docker;
    @MockitoBean ModelGatewayService gateways;
    @MockitoBean AgentHttpClient agent;

    @ParameterizedTest
    @ValueSource(strings = {"standard", "extended"})
    void gatewayUsageReplacesAgentClaimsAndUsesMappedModelFromOriginalPriceSnapshot(String budget) throws Exception {
        try (Fixture fixture = new Fixture(true, true, budget)) {
            ObjectNode created = fixture.create();
            assertThat(fixture.entered.await(10, TimeUnit.SECONDS)).isTrue();
            JsonNode snapshot = created.path("config").path("pricingSnapshot").deepCopy();
            assertThat(created.path("config").path("gateway").path("priceModels").path("main").asText()).isEqualTo("priced-model");
            assertThat(created.path("config").path("gateway").has("apiKey")).isFalse();
            assertThat(created.toString()).doesNotContain(fixture.apiKey, fixture.taskToken);

            String changed = "after-gateway-" + UUID.randomUUID();
            prices.create(fixture.owner, changed, List.of(price("priced-model", "2000", "8000")));
            prices.activate(changed);
            fixture.release.countDown();
            ObjectNode report = fixture.completedReport();

            assertThat(report.path("pricingSnapshot")).isEqualTo(snapshot);
            assertThat(report.path("pricingSnapshotHash")).isEqualTo(snapshot.path("snapshotHash"));
            assertThat(report.path("usageSource").asText()).isEqualTo("provider_gateway");
            assertThat(report.path("usage").path("inputTokens").asLong()).isEqualTo(11);
            assertThat(report.path("usage").path("outputTokens").asLong()).isEqualTo(7);
            assertThat(report.path("usage").path("totalTokens").asLong()).isEqualTo(18);
            assertThat(report.path("cost").asDouble()).isEqualTo(0.000078d);
            assertThat(report.path("results").get(0).path("pricingModel").asText()).isEqualTo("priced-model");
            assertThat(report.path("results").get(0).path("costStatus").asText()).isEqualTo("calculated");
            assertThat(repository.findTasks(fixture.id).getFirst().costUsd()).isEqualTo(0.000078d);

            Grant grant = fixture.grant.get();
            int calls = "extended".equals(budget) ? 40 : 20;
            int seconds = "extended".equals(budget) ? 600 : 300;
            assertThat(grant.maxCalls()).isEqualTo(calls);
            assertThat(Duration.between(grant.receivedAt(), grant.deadline()).toSeconds()).isBetween((long) seconds - 1, (long) seconds + 1);
            ObjectNode request = fixture.request.get();
            assertThat(request.path("budget").path("maxAgentCalls").asInt()).isEqualTo(calls);
            assertThat(request.path("budget").path("timeoutSeconds").asInt()).isEqualTo(seconds);
            assertThat(Duration.between(Instant.parse(request.path("deadline").asText()), grant.deadline()).abs()).isLessThan(Duration.ofSeconds(2));
            assertThat(request.path("modelGateway").path("token").asText()).isEqualTo(fixture.taskToken);
            assertThat(request.toString()).doesNotContain(fixture.apiKey);
            verify(fixture.session).beginTask(eq("main::task-1"), eq("provider-model"), eq(calls), any(Instant.class));
            verify(fixture.session).finishTask("main::task-1");
            fixture.assertSecretsAbsent(report);
        }
    }

    @Test
    void missingGatewayUsageKeepsCostUnknownDespiteAgentProvidingTokenCounts() throws Exception {
        try (Fixture fixture = new Fixture(false, false, "standard")) {
            fixture.create();
            ObjectNode report = fixture.completedReport();
            assertThat(report.path("score").asDouble()).isEqualTo(100);
            assertThat(report.path("cost").isNull()).isTrue();
            assertThat(report.path("costStatus").asText()).isEqualTo("missing_usage");
            assertThat(report.path("usage").path("knownTasks").asInt()).isZero();
            assertThat(report.path("usage").path("totalTokens").isNull()).isTrue();
            assertThat(repository.findTasks(fixture.id).getFirst().costUsd()).isNull();
            assertThat(repository.findTasks(fixture.id).getFirst().agentResponse().path("usage")).isEmpty();
            fixture.assertSecretsAbsent(report);
        }
    }

    @Test
    void cancelClosesGatewayAndSandboxWithoutAcceptingLateAccounting() throws Exception {
        try (Fixture fixture = new Fixture(true, true, "standard")) {
            fixture.create();
            assertThat(fixture.entered.await(10, TimeUnit.SECONDS)).isTrue();
            runs.cancel(fixture.owner, fixture.id);
            fixture.awaitCleanup();
            ObjectNode cancelled = runs.get(fixture.owner, fixture.id);
            assertThat(cancelled.path("status").asText()).isEqualTo("cancelled");
            assertThat(cancelled.path("reportId").isNull()).isTrue();
            assertThat(cancelled.path("progress").path("completed").asInt()).isZero();
            verify(fixture.session, never()).finishTask(anyString());
            fixture.assertSecretsAbsent(cancelled);
        }
    }

    private static PricingService.ModelPriceInput price(String model, String input, String output) {
        return new PricingService.ModelPriceInput(model, new BigDecimal(input), new BigDecimal(output), "USD");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).as("run reached expected state before deadline").isTrue();
    }

    private final class Fixture implements AutoCloseable {
        final BenchmarkUser owner;
        final ObjectNode input;
        final String apiKey = "test-provider-secret-" + UUID.randomUUID();
        final String taskToken = "test-task-token-" + UUID.randomUUID();
        final ModelGatewayService.Session session = mock(ModelGatewayService.Session.class);
        final DockerSandboxExecutor.Sandbox sandbox = new DockerSandboxExecutor.Sandbox("fixture-agent", "fixture-proxy", "fixture-internal", "fixture-proxy-net", "http://127.0.0.1:18000");
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1), stopped = new CountDownLatch(1);
        final AtomicBoolean opened = new AtomicBoolean();
        final AtomicReference<ObjectNode> request = new AtomicReference<>();
        final AtomicReference<Grant> grant = new AtomicReference<>();
        String id;

        Fixture(boolean completeUsage, boolean block, String budget) throws Exception {
            String key = UUID.randomUUID().toString();
            owner = new BenchmarkUser("gateway-" + key, key + "@example.test", "Gateway fixture", "admin", "test-hash", null, Instant.now().toString());
            users.insert(owner);
            String datasetId = "gateway-" + key;
            datasets.publish(owner, CustomDatasetRulesTest.fixture(mapper, datasetId));
            String version = "gateway-price-" + key;
            prices.create(owner, version, List.of(price("priced-model", "2", "8"), price("provider-model", "200", "800")));
            prices.activate(version);
            when(docker.start(eq("fixture-agent:local"), anyString())).thenReturn(sandbox);
            doAnswer(invocation -> { stopped.countDown(); return null; }).when(docker).stop(sandbox);
            when(gateways.openRun(anyString(), eq(sandbox), eq("fixture"), eq(apiKey))).thenAnswer(invocation -> {
                opened.set(true);
                return session;
            });
            when(session.beginTask(anyString(), eq("provider-model"), anyInt(), any(Instant.class))).thenAnswer(invocation -> {
                int calls = invocation.getArgument(2);
                Instant deadline = invocation.getArgument(3);
                grant.set(new Grant(calls, deadline, Instant.now()));
                return mapper.createObjectNode().put("baseUrl", "http://model-gateway:8010/v1").put("token", taskToken)
                        .put("provider", "fixture").put("model", "provider-model").put("priceModelId", "priced-model")
                        .put("maxAgentCalls", calls).put("deadline", deadline.toString());
            });
            ObjectNode accounting = mapper.createObjectNode().put("usageComplete", completeUsage).put("calls", 1).put("providerReported", true);
            if (completeUsage) accounting.put("inputTokens", 11).put("outputTokens", 7);
            else accounting.put("inputTokens", 11); // Partial counters must not fall back to Agent claims.
            when(session.finishTask(anyString())).thenAnswer(invocation -> accounting.deepCopy());
            doAnswer(invocation -> { closed.countDown(); return null; }).when(session).close();
            when(agent.checkSandbox(sandbox.endpoint())).thenReturn(mapper.createObjectNode().put("status", "ok").put("version", "gateway-fixture-1"));
            ObjectNode capabilities = mapper.createObjectNode();
            capabilities.putArray("models").add("provider-model");
            when(agent.capabilitiesSandbox(sandbox.endpoint())).thenReturn(capabilities);
            when(agent.analyzeSandbox(eq(sandbox.endpoint()), any(ObjectNode.class), any(Duration.class))).thenAnswer(invocation -> {
                request.set(((ObjectNode) invocation.getArgument(1)).deepCopy());
                entered.countDown();
                if (block && !release.await(20, TimeUnit.SECONDS)) throw new java.io.IOException("fixture release timed out");
                ObjectNode response = mapper.createObjectNode().put("status", "completed");
                response.putObject("artifact").put("answer", 4);
                response.putObject("usage").put("inputTokens", 1000).put("outputTokens", 2000);
                response.put("echo", apiKey + " " + taskToken);
                response.put(apiKey, taskToken);
                return response;
            });
            input = mapper.createObjectNode().put("source", "docker").put("image", "fixture-agent:local")
                    .put("datasetId", datasetId).put("version", "1.0.0").put("model", "provider-model")
                    .put("scope", "full").put("budget", budget).put("seed", 987);
            input.putObject("gateway").put("providerId", "fixture").put("apiKey", apiKey);
        }

        ObjectNode create() {
            ObjectNode created = runs.create(owner, input);
            id = created.path("runId").asText();
            return created;
        }

        ObjectNode completedReport() throws Exception {
            await(() -> "completed".equals(runs.get(owner, id).path("status").asText()));
            awaitCleanup();
            return reports.get(owner, runs.get(owner, id).path("reportId").asText());
        }

        void awaitCleanup() throws Exception {
            assertThat(closed.await(5, TimeUnit.SECONDS)).as("gateway session closed").isTrue();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).as("Docker sandbox stopped").isTrue();
            verify(session, times(1)).close();
            verify(docker, times(1)).stop(sandbox);
            verify(gateways).openRun(id, sandbox, "fixture", apiKey);
        }

        void assertSecretsAbsent(ObjectNode report) {
            assertThat(repository.find(id, false).orElseThrow().config().toString()
                    + repository.findTasks(id) + runs.logs(owner, id, 0) + report).doesNotContain(apiKey, taskToken);
        }

        @Override public void close() throws Exception {
            if (id != null && List.of("queued", "running", "paused").contains(runs.get(owner, id).path("status").asText())) runs.cancel(owner, id);
            release.countDown();
            if (opened.get()) awaitCleanup();
        }
    }

    private record Grant(int maxCalls, Instant deadline, Instant receivedAt) { }
}

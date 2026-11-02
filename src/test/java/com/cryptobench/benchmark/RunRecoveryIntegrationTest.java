package com.cryptobench.benchmark;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.dataset.service.DatasetService;
import com.cryptobench.benchmark.execution.repository.RunRepository;
import com.cryptobench.benchmark.execution.service.RunService;
import com.cryptobench.benchmark.pricing.service.PricingService;
import com.cryptobench.benchmark.report.service.ReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

/** Uses actual local HTTP requests, persisted claims and transactions; no paid model or real account. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:run-recovery-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RunRecoveryIntegrationTest {
    @Autowired RunService runs;
    @Autowired RunRepository repository;
    @Autowired UserRepository users;
    @Autowired DatasetService datasets;
    @Autowired PricingService prices;
    @Autowired ReportService reports;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void retryKeepsSeedZeroAndPricesDespiteActivationAndClientOverrides() throws Exception {
        try (Fixture f = new Fixture(3, 1, true)) {
            String id = f.create(f.input.deepCopy().put("seed", 0));
            await(() -> f.calls.size() == 1);
            JsonNode snapshot = runs.get(f.owner, id).path("config").path("pricingSnapshot").deepCopy();
            String version = "new-" + UUID.randomUUID();
            prices.create(f.owner, version, List.of(new PricingService.ModelPriceInput("GPT-4.1", new BigDecimal("123"), new BigDecimal("321"), "USD")));
            prices.activate(version);
            f.release.countDown();
            ObjectNode done = terminal(f, id, "completed");
            assertThat(reports.get(f.owner, done.path("reportId").asText()).path("pricingSnapshot")).isEqualTo(snapshot);
            ObjectNode malicious = mapper.createObjectNode().put("seed", 999).put("budget", "extended").put("model", "changed");
            malicious.putObject("pricingSnapshot").put("version", "client");
            ObjectNode retried = runs.retry(f.owner, id, malicious);
            String retryId = retried.path("runId").asText(); f.ids.add(retryId);
            assertThat(retried.path("seed").asLong()).isZero();
            assertThat(retried.path("budget").asText()).isEqualTo("standard");
            assertThat(retried.path("config").path("pricingSnapshot")).isEqualTo(snapshot);
            assertThat(retried.path("retryOf").asText()).isEqualTo(id);
            terminal(f, retryId, "completed");
            String freshId = f.create(f.input.deepCopy().put("seed", 987));
            assertThat(runs.get(f.owner, freshId).path("config").path("pricingSnapshot").path("version").asText()).isEqualTo(version);
            terminal(f, freshId, "completed");
            assertThat(f.calls.stream().filter(n -> retryId.equals(n.path("runId").asText())).map(n -> n.path("seed").asLong())).containsOnly(0L);
        }
    }

    @Test void resumePreservesTwoCommittedResultsRequiresCredentialsAndFencesOldAttempt() throws Exception {
        try (Fixture f = new Fixture(3, 3, true)) {
            String id = f.create(f.input.deepCopy().put("seed", 987).put("token", "run-only-test-secret"));
            await(() -> f.calls.size() == 3);
            long oldGeneration = repository.generation(id);
            var third = repository.findTasks(id).get(2);
            repository.pause(id, oldGeneration, "worker_restarted", "Test worker lost");
            credentials().remove(id); // models the loss of process-local credentials on restart
            f.release.countDown();
            await(() -> !activeJobs().containsKey(id));
            ObjectNode paused = runs.get(f.owner, id);
            assertThat(paused.path("progress").path("completed").asInt()).isEqualTo(2);
            assertThat(paused.path("credentialsRequired").toString()).contains("main");
            assertThatThrownBy(() -> runs.resume(f.owner, id, mapper.createObjectNode())).isInstanceOf(ApiException.class);
            ObjectNode supplied = mapper.createObjectNode(); supplied.putObject("credentials").putObject("main").put("token", "replacement-secret");
            runs.resume(f.owner, id, supplied);
            runs.resume(f.owner, id, supplied);
            assertThat(repository.finishTask(id, third.taskId(), oldGeneration, third.attempt(), "passed", mapper.createObjectNode(), mapper.createObjectNode(), true, 1d, 99d, 1L)).isFalse();
            ObjectNode done = terminal(f, id, "completed");
            assertThat(done.path("seed").asLong()).isEqualTo(987);
            assertThat(f.calls.stream().filter(n -> "task-1".equals(n.path("taskId").asText())).count()).isEqualTo(1);
            assertThat(f.calls.stream().filter(n -> "task-2".equals(n.path("taskId").asText())).count()).isEqualTo(1);
            assertThat(f.calls.stream().filter(n -> "task-3".equals(n.path("taskId").asText())).count()).isEqualTo(2);
            ObjectNode report = reports.get(f.owner, done.path("reportId").asText());
            assertThat(report.path("score").asDouble()).isEqualTo(100);
            var finalTasks = repository.findTasks(id);
            double finalElapsed = finalTasks.stream().mapToDouble(task -> task.elapsedMs() / 1000d).sum();
            assertThat(finalTasks).hasSize(3);
            assertThat(report.path("totalTimeSeconds").asDouble()).isCloseTo(finalElapsed, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(report.path("averageTimeSeconds").asDouble()).isCloseTo(finalElapsed / 3, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_reports WHERE id=?", Integer.class, report.path("id").asText())).isEqualTo(1);
            assertThat(repository.find(id, false).orElseThrow().config().toString()).doesNotContain("run-only-test-secret", "replacement-secret");
            assertThat(repository.findTasks(id).toString()).doesNotContain("run-only-test-secret", "replacement-secret");
        }
    }

    @Test void oldAgentCannotClaimExactResumeAndCancellationRejectsLateResults() throws Exception {
        try (Fixture f = new Fixture(3, 2, false)) {
            String id = f.create(f.input); await(() -> f.calls.size() == 2);
            long generation = repository.generation(id);
            repository.pause(id, generation, "worker_restarted", "interrupted"); f.release.countDown();
            await(() -> !activeJobs().containsKey(id));
            runs.resume(f.owner, id, mapper.createObjectNode());
            ObjectNode paused = terminal(f, id, "paused");
            assertThat(paused.path("pauseReason").asText()).isEqualTo("agent_changed");
            assertThat(paused.path("canResume").asBoolean()).isFalse();
            runs.cancel(f.owner, id);
            var task = repository.findTasks(id).get(1);
            assertThat(repository.finishTask(id, task.taskId(), generation, task.attempt(), "passed", mapper.createObjectNode(), mapper.createObjectNode(), true, 1d, 0d, 1L)).isFalse();
            assertThat(repository.finish(id, generation, "completed", null, null, "late-report")).isFalse();
            assertThat(runs.get(f.owner, id).path("status").asText()).isEqualTo("cancelled");
            assertThat(runs.get(f.owner, id).path("reportId").isNull()).isTrue();
        }
    }

    @Test void moreThanOldMemoryQueueCapacityRemainsDurableAndCancelable() throws Exception {
        try (Fixture f = new Fixture(1, -1, true)) {
            for (int i = 0; i < 26; i++) f.create(f.input.deepCopy().put("seed", i));
            await(() -> f.calls.size() >= 2);
            long queued = f.ids.stream().filter(id -> "queued".equals(runs.get(f.owner, id).path("status").asText())).count();
            assertThat(queued).isGreaterThanOrEqualTo(24);
            for (String id : f.ids) {
                assertThat(runs.get(f.owner, id).path("status").asText()).isIn("queued", "running");
                runs.cancel(f.owner, id);
            }
            f.release.countDown();
            await(() -> f.ids.stream().noneMatch(id -> activeJobs().containsKey(id)));
            for (String id : f.ids) assertThat(runs.get(f.owner, id).path("status").asText()).isEqualTo("cancelled");
        }
    }

    @Test void queuedHistoricalDeviceRunPausesWithoutInvokingTheLocalAgent() throws Exception {
        try (Fixture f = new Fixture(1, 0, true)) {
            String original = f.create(f.input);
            terminal(f, original, "completed");
            assertThat(f.calls).hasSize(1);
            ObjectNode config = repository.find(original, false).orElseThrow().config().deepCopy();
            config.put("executorType", "bridge").put("bridgeId", "removed-device").put("localAgentId", "removed-agent");
            String id = "retired-queued-" + UUID.randomUUID();
            repository.insert(new com.cryptobench.benchmark.execution.model.Run(id, f.owner.id(), "evaluation", "local",
                    f.input.path("datasetId").asText(), "1.0.0", "standard", "full", "GPT-4.1", null, 42,
                    "queued", 1, 0, 0, 0, config, null, null, null, null, false, Instant.now().toString(), null, null));
            repository.insertTask(id, "main:task-1", 1);
            f.ids.add(id);
            ObjectNode paused = terminal(f, id, "paused");
            assertThat(paused.path("pauseReason").asText()).isEqualTo("executor_removed");
            assertThat(paused.path("canResume").asBoolean()).isFalse();
            assertThat(f.calls).hasSize(1);
            assertThat(repository.findTasks(id).getFirst().status()).isEqualTo("queued");
            assertThat(repository.find(id, false).orElseThrow().reportId()).isNull();
        }
    }

    @Test void legacyRetryKeepsUnknownPricesAndPkRequestsSeparateCredentials() throws Exception {
        try (Fixture f = new Fixture(1, 0, true)) {
            String id = f.create(f.input); terminal(f, id, "completed");
            ObjectNode legacy = repository.find(id, false).orElseThrow().config().deepCopy(); legacy.remove("pricingSnapshot");
            repository.updateConfig(id, legacy);
            ObjectNode retry = runs.retry(f.owner, id, mapper.createObjectNode()); String newId = retry.path("runId").asText(); f.ids.add(newId);
            ObjectNode done = terminal(f, newId, "completed");
            ObjectNode report = reports.get(f.owner, done.path("reportId").asText());
            assertThat(report.path("cost").isNull()).isTrue(); assertThat(report.path("costStatus").asText()).isEqualTo("missing_snapshot");
            ObjectNode pk = f.input.deepCopy().put("kind", "pk");
            pk.putArray("players").addObject().put("model", "GPT-4.1").put("token", "secret-a");
            pk.withArray("players").addObject().put("model", "GPT-4.1").put("token", "secret-b");
            String pkId = f.create(pk); terminal(f, pkId, "completed");
            assertThatThrownBy(() -> runs.retry(f.owner, pkId, mapper.createObjectNode())).isInstanceOf(ApiException.class).hasMessageContaining("a, b");
            assertThat(f.auth).contains("Bearer secret-a", "Bearer secret-b");
        }
    }

    @SuppressWarnings("unchecked") private Map<String, ?> activeJobs() { return (Map<String, ?>)ReflectionTestUtils.getField(runs, "activeJobs"); }
    @SuppressWarnings("unchecked") private Map<String, ?> credentials() { return (Map<String, ?>)ReflectionTestUtils.getField(runs, "credentials"); }
    private ObjectNode terminal(Fixture f, String id, String status) throws Exception {
        await(() -> status.equals(runs.get(f.owner, id).path("status").asText())); return runs.get(f.owner, id);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(30);
        assertThat(condition.getAsBoolean()).as("condition before deadline").isTrue();
    }
    private final class Fixture implements AutoCloseable {
        final BenchmarkUser owner;
        final ObjectNode input;
        final HttpServer agent;
        final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final List<JsonNode> calls = new CopyOnWriteArrayList<>();
        final List<String> ids = new CopyOnWriteArrayList<>(), auth = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        Fixture(int taskCount, int blockCall, boolean resumable) throws Exception {
            String key = UUID.randomUUID().toString();
            owner = new BenchmarkUser("recovery-"+key, key+"@example.test", "Recovery fixture", "admin", "test-hash", null, Instant.now().toString()); users.insert(owner);
            String datasetId = "recovery-"+key;
            ObjectNode pack = CustomDatasetRulesTest.fixture(mapper, datasetId); pack.putArray("tasks");
            for (int i=1;i<=taskCount;i++) {
                ObjectNode task = pack.withArray("tasks").addObject().put("id", "task-"+i).put("question", "Return rounds");
                task.putObject("input").put("rounds", i); task.putObject("verification").put("type", "exact-json-v1").put("expected", i);
            }
            datasets.publish(owner, pack);
            agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); agent.setExecutor(executor);
            agent.createContext("/", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization"); if (authorization != null) auth.add(authorization);
                ObjectNode response = mapper.createObjectNode();
                switch (exchange.getRequestURI().getPath()) {
                    case "/health" -> response.put("status", "ok").put("version", "recovery-1");
                    case "/capabilities" -> { response.put("independentTasks", resumable).put("idempotency", resumable); response.putArray("models").add("GPT-4.1"); }
                    case "/analyze" -> {
                        JsonNode request = mapper.readTree(exchange.getRequestBody()); calls.add(request);
                        if (blockCall == -1 || calls.size() == blockCall) try { release.await(25, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        response.put("status", "completed"); response.putObject("artifact").set("answer", request.path("task").path("input").path("rounds"));
                        response.putObject("usage").put("inputTokens", 10).put("outputTokens", 10);
                        response.put("echo", authorization == null ? "none" : authorization);
                    }
                }
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                try { exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            }); agent.start();
            input = mapper.createObjectNode().put("source", "local").put("endpoint", "http://127.0.0.1:"+agent.getAddress().getPort())
                    .put("datasetId", datasetId).put("version", "1.0.0").put("model", "GPT-4.1").put("scope", "full").put("budget", "standard");
        }
        String create(ObjectNode request) { String id = runs.create(owner, request).path("runId").asText(); ids.add(id); return id; }
        public void close() throws Exception {
            for (String id:ids) if (List.of("queued", "running", "paused").contains(runs.get(owner,id).path("status").asText())) runs.cancel(owner,id);
            release.countDown(); await(() -> ids.stream().noneMatch(id -> activeJobs().containsKey(id))); agent.stop(0); executor.shutdownNow();
        }
    }
}

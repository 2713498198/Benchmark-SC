package com.cryptobench.benchmark;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.dataset.service.DatasetService;
import com.cryptobench.benchmark.execution.model.RunTask;
import com.cryptobench.benchmark.execution.repository.RunRepository;
import com.cryptobench.benchmark.execution.service.RunService;
import com.cryptobench.benchmark.pricing.service.PricingService;
import com.cryptobench.benchmark.report.service.ReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real application lifecycle and durable H2 database, isolated from normal accounts and data. */
class RunApplicationRestartIntegrationTest {
    @TempDir Path temporary;

    @Test
    void closingAndReopeningApplicationPreservesCommittedResultsAndResumesOnlyUnfinishedTask() throws Exception {
        Path database = temporary.resolve("restart-database");
        String ownerId = "restart-owner";
        String runId;
        JsonNode snapshot;
        List<RunTask> completed;
        RunService firstService;

        try (RestartAgent agent = new RestartAgent()) {
            try (ConfigurableApplicationContext first = application(database)) {
                ObjectMapper mapper = first.getBean(ObjectMapper.class);
                BenchmarkUser owner = new BenchmarkUser(ownerId, "restart@example.test", "Restart fixture", "admin",
                        "test-only-hash", null, Instant.now().toString());
                first.getBean(UserRepository.class).insert(owner);
                ObjectNode pack = CustomDatasetRulesTest.fixture(mapper, "restart-dataset");
                pack.putArray("tasks");
                for (int i = 1; i <= 3; i++) {
                    ObjectNode task = pack.withArray("tasks").addObject().put("id", "task-" + i).put("question", "Return rounds");
                    task.putObject("input").put("rounds", i);
                    task.putObject("verification").put("type", "exact-json-v1").put("expected", i);
                }
                first.getBean(DatasetService.class).publish(owner, pack);
                PricingService prices = first.getBean(PricingService.class);
                prices.create(owner, "before-restart", List.of(price("2", "8")));
                prices.activate("before-restart");

                ObjectNode input = mapper.createObjectNode().put("source", "local").put("endpoint", agent.endpoint())
                        .put("datasetId", "restart-dataset").put("version", "1.0.0").put("model", "GPT-4.1")
                        .put("scope", "full").put("budget", "standard").put("seed", 987).put("token", "before-restart-secret");
                firstService = first.getBean(RunService.class);
                runId = firstService.create(owner, input).path("runId").asText();
                assertThat(agent.thirdStarted.await(15, TimeUnit.SECONDS)).as("third task reached real HTTP Agent").isTrue();
                RunRepository repository = first.getBean(RunRepository.class);
                List<RunTask> tasks = repository.findTasks(runId);
                assertThat(tasks).extracting(RunTask::status).containsExactly("passed", "passed", "running");
                completed = List.copyOf(tasks.subList(0, 2));
                snapshot = firstService.get(owner, runId).path("config").path("pricingSnapshot").deepCopy();
                assertThat(snapshot.path("version").asText()).isEqualTo("before-restart");
                assertThat(snapshot.path("snapshotHash").asText()).isNotBlank();
                assertThat(agent.thirdFinished.getCount()).isEqualTo(1);
                // Closing really destroys the worker and datasource; no repository pause or reflection is used.
            }

            assertThat(Files.isRegularFile(Path.of(database + ".mv.db"))).isTrue();
            agent.releaseThird.countDown();
            assertThat(agent.thirdFinished.await(5, TimeUnit.SECONDS)).as("old HTTP attempt released after shutdown").isTrue();

            try (ConfigurableApplicationContext second = application(database)) {
                ObjectMapper mapper = second.getBean(ObjectMapper.class);
                BenchmarkUser owner = second.getBean(UserRepository.class).findById(ownerId).orElseThrow();
                RunService restoredService = second.getBean(RunService.class);
                RunRepository repository = second.getBean(RunRepository.class);
                assertThat(restoredService).isNotSameAs(firstService);
                ObjectNode paused = restoredService.get(owner, runId);
                assertThat(paused.path("status").asText()).isEqualTo("paused");
                assertThat(paused.path("pauseReason").asText()).isEqualTo("worker_restarted");
                assertThat(paused.path("progress").path("completed").asInt()).isEqualTo(2);
                assertThat(paused.path("seed").asLong()).isEqualTo(987);
                assertThat(paused.path("config").path("pricingSnapshot")).isEqualTo(snapshot);
                assertThat(repository.findTasks(runId).subList(0, 2)).isEqualTo(completed);
                assertThat(repository.findTasks(runId).get(2).status()).isEqualTo("interrupted");
                assertThat(paused.path("credentialsRequired")).hasSize(1);
                assertThat(paused.path("credentialsRequired").get(0).asText()).isEqualTo("main");
                assertThatThrownBy(() -> restoredService.resume(owner, runId, mapper.createObjectNode()))
                        .isInstanceOf(ApiException.class).hasMessageContaining("凭证");
                assertThat(agent.calls).hasSize(3);

                // A newly active price must not replace the durable snapshot of the interrupted experiment.
                PricingService prices = second.getBean(PricingService.class);
                prices.create(owner, "after-restart", List.of(price("200", "800")));
                prices.activate("after-restart");
                ObjectNode supplied = mapper.createObjectNode();
                supplied.putObject("credentials").putObject("main").put("token", "after-restart-secret");
                restoredService.resume(owner, runId, supplied);
                await(() -> "completed".equals(restoredService.get(owner, runId).path("status").asText()));

                ObjectNode done = restoredService.get(owner, runId);
                assertThat(done.path("progress").path("completed").asInt()).isEqualTo(3);
                assertThat(done.path("seed").asLong()).isEqualTo(987);
                assertThat(done.path("config").path("pricingSnapshot")).isEqualTo(snapshot);
                List<RunTask> finalTasks = repository.findTasks(runId);
                assertThat(finalTasks.subList(0, 2)).isEqualTo(completed);
                assertThat(finalTasks.get(2).attempt()).isEqualTo(2);
                assertThat(finalTasks).extracting(RunTask::status).containsOnly("passed");
                assertThat(finalTasks).extracting(RunTask::costUsd).containsOnly(0.0001d);
                assertThat(agent.calls.stream().map(call -> call.request().path("taskId").asText()).toList())
                        .containsExactly("task-1", "task-2", "task-3", "task-3");
                assertThat(agent.calls.stream().map(call -> call.request().path("seed").asLong()).toList()).containsOnly(987L);
                assertThat(agent.calls.getLast().authorization()).isEqualTo("Bearer after-restart-secret");

                ObjectNode report = second.getBean(ReportService.class).get(owner, done.path("reportId").asText());
                assertThat(report.path("seed").asLong()).isEqualTo(987);
                assertThat(report.path("pricingSnapshot")).isEqualTo(snapshot);
                assertThat(report.path("pricingSnapshotHash")).isEqualTo(snapshot.path("snapshotHash"));
                assertThat(report.path("score").asDouble()).isEqualTo(100);
                assertThat(report.path("results")).hasSize(3);
                JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_reports", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForList("SELECT status FROM benchmark_run_task_attempts WHERE run_id=? AND task_id=? ORDER BY attempt",
                        String.class, runId, finalTasks.get(2).taskId())).containsExactly("interrupted", "passed");
                assertThat(done.toString() + finalTasks + report).doesNotContain("before-restart-secret", "after-restart-secret");
            }
        }
    }

    private ConfigurableApplicationContext application(Path database) {
        return new SpringApplicationBuilder(CryptoBenchApplication.class).profiles("test").registerShutdownHook(false).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--spring.profiles.active=test",
                "--spring.datasource.url=jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/')
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--benchmark.demo-enabled=false", "--benchmark.legacy-import-enabled=false",
                "--benchmark.docker.enabled=false", "--benchmark.gateway.enabled=false",
                "--benchmark.gateway.providers-json=[]",
                "--benchmark.mail.worker-enabled=false", "--benchmark.mail.mode=disabled",
                "--benchmark.mail.key-file=" + temporary.resolve("mail.key").toAbsolutePath(),
                "--benchmark.admin.bootstrap.email=", "--benchmark.admin.bootstrap.name=", "--benchmark.admin.bootstrap.password=");
    }

    private static PricingService.ModelPriceInput price(String input, String output) {
        return new PricingService.ModelPriceInput("GPT-4.1", new BigDecimal(input), new BigDecimal(output), "USD");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(30);
        assertThat(condition.getAsBoolean()).as("resumed run completed before deadline").isTrue();
    }

    private static final class RestartAgent implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final CountDownLatch thirdStarted = new CountDownLatch(1), releaseThird = new CountDownLatch(1), thirdFinished = new CountDownLatch(1);

        private RestartAgent() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) throws IOException {
            boolean blocked = false;
            try {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (!List.of("Bearer before-restart-secret", "Bearer after-restart-secret").contains(authorization == null ? "" : authorization)) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                ObjectNode response = mapper.createObjectNode();
                switch (exchange.getRequestURI().getPath()) {
                    case "/health" -> response.put("status", "ok").put("version", "restart-fixture-1");
                    case "/capabilities" -> {
                        response.put("independentTasks", true).put("idempotency", true);
                        response.putArray("models").add("GPT-4.1");
                    }
                    case "/analyze" -> {
                        JsonNode request = mapper.readTree(exchange.getRequestBody());
                        calls.add(new Call(request, authorization));
                        if (calls.size() == 3) {
                            blocked = true;
                            thirdStarted.countDown();
                            try {
                                if (!releaseThird.await(45, TimeUnit.SECONDS)) throw new IOException("restart fixture timed out");
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        response.put("status", "completed");
                        response.putObject("artifact").set("answer", request.path("task").path("input").path("rounds"));
                        response.putObject("usage").put("inputTokens", 10).put("outputTokens", 10);
                        response.put("echo", authorization);
                    }
                    default -> { exchange.sendResponseHeaders(404, -1); return; }
                }
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException interruptedRequest) {
                if (!blocked) throw interruptedRequest;
                // The old client has been destroyed with its application context.
            } finally {
                exchange.close();
                if (blocked) thirdFinished.countDown();
            }
        }

        @Override public void close() {
            releaseThird.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
        private record Call(JsonNode request, String authorization) { }
    }
}

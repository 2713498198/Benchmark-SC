package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.execution.model.Run;
import com.cryptobench.benchmark.execution.model.RunLog;
import com.cryptobench.benchmark.execution.model.RunTask;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.cryptobench.benchmark.execution.repository.RunRepository;
import com.cryptobench.benchmark.report.service.ReportService;
import com.cryptobench.benchmark.gateway.GatewayProperties;
import com.cryptobench.benchmark.gateway.ModelGatewayService;
import com.cryptobench.benchmark.runner.model.RunnerJob;
import com.cryptobench.benchmark.runner.service.RunnerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 创建和执行服务端运行；凭证只在当前 worker 的内存中短暂存在，永不持久化。 */
@Service
public class RunService {
    private final RunRepository runs;
    private final TaskCatalogService catalog;
    private final AgentHttpClient agent;
    private final ServerCostCalculator costs;
    private final DockerSandboxExecutor docker;
    private final ReportService reports;
    private final ModelGatewayService gateways;
    private final GatewayProperties gatewayProperties;
    private final RunnerService runnerService;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final String workerId = UUID.randomUUID().toString();
    private volatile boolean closing;
    private final ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cryptobench-run-dispatcher"); thread.setDaemon(true); return thread;
    });
    private final ConcurrentHashMap<String, Map<String, String>> credentials = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(20), runnable -> {
                Thread thread = new Thread(runnable, "cryptobench-run-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<String, Future<?>> activeJobs = new ConcurrentHashMap<>();

    public RunService(RunRepository runs, TaskCatalogService catalog, AgentHttpClient agent,
                      ServerCostCalculator costs, ReportService reports,
                      com.fasterxml.jackson.databind.ObjectMapper mapper, DockerSandboxExecutor docker,
                      PlatformTransactionManager transactionManager,
                      ModelGatewayService gateways, GatewayProperties gatewayProperties,
                      RunnerService runnerService) {
        this.runs = runs;
        this.catalog = catalog;
        this.agent = agent;
        this.costs = costs;
        this.reports = reports;
        this.mapper = mapper;
        this.docker = docker;
        this.transactions = new TransactionTemplate(transactionManager);
        this.gateways = gateways;
        this.gatewayProperties = gatewayProperties;
        this.runnerService = runnerService;
    }

    @PostConstruct
    void recoverInterruptedRuns() {
        runs.recoverInterruptedRuns();
        docker.reapInactiveResources(runs.activeClaimIds());
        dispatcher.scheduleWithFixedDelay(this::dispatch, 100, 500, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdownWorkers() {
        closing = true;
        dispatcher.shutdownNow();
        for (String id : activeJobs.keySet()) {
            try { runs.pause(id, "worker_restarted", "执行服务关闭，已保存完成题目"); } catch (RuntimeException ignored) { }
        }
        workers.shutdownNow();
        try {
            dispatcher.awaitTermination(5, TimeUnit.SECONDS);
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        credentials.clear();
    }

    private void dispatch() {
        if (closing) return;
        try {
            for (String id : activeJobs.keySet()) runs.renewLease(id, workerId, System.currentTimeMillis() + 90_000);
            runs.recoverInterruptedRuns();
            for (Run run : runs.findQueued(100)) {
                if (closing) break;
                if (activeJobs.containsKey(run.id())) continue;
                if (!isLocalExecution(run.config())) {
                    runs.pause(run.id(), "executor_removed", "原执行方式已移除，请新建本机测评"); continue;
                }
                if (activeJobs.size() >= 2) continue;
                if (!"cryptobench-execution-plan-v1".equals(run.config().path("schema").asText())) {
                    runs.pause(run.id(), "catalog_changed", "旧运行缺少完整执行快照，请基于原配置重新测评"); continue;
                }
                if (missingCredentials(run, credentials.getOrDefault(run.id(), Map.of())).size() > 0) {
                    runs.pause(run.id(), "credentials_required", "请补录 Agent 凭证后继续"); continue;
                }
                FutureTask<Void> job = new FutureTask<>(() -> { execute(run.id()); return null; }) {
                    @Override public void run() {
                        try { super.run(); }
                        finally {
                            // Also runs when cancellation wins before the callable starts.
                            activeJobs.remove(run.id(), this);
                            Run latest = runs.find(run.id(), false).orElse(null);
                            if (latest == null || !List.of("paused", "queued", "running").contains(latest.status())) credentials.remove(run.id());
                        }
                    }
                };
                if (activeJobs.putIfAbsent(run.id(), job) != null) continue;
                try { workers.execute(job); }
                catch (RejectedExecutionException busy) { activeJobs.remove(run.id(), job); break; }
            }
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(RunService.class).warn("Run dispatch will retry after a database/scheduler failure", exception);
        }
    }

    public ObjectNode checkAgent(BenchmarkUser user, ObjectNode input) {
        requireLocalExecution(input, HttpStatus.BAD_REQUEST);
        String source = input.path("source").asText("local").trim().toLowerCase(Locale.ROOT);
        if ("docker".equals(source)) return checkDockerAgent(input);
        String endpoint;
        try {
            endpoint = AgentHttpClient.normalize(requireText(input, "endpoint"));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        String token = optionalText(input, "token");
        try {
            ObjectNode health = agent.check(endpoint, token);
            ObjectNode capabilities = agent.capabilities(endpoint, token);
            ObjectNode result = mapper.createObjectNode();
            result.put("reachable", true);
            result.set("health", health);
            result.set("capabilities", capabilities);
            return result;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "无法连接本地 Agent：" + safeMessage(exception));
        }
    }

    private ObjectNode checkDockerAgent(ObjectNode input) {
        String image = optionalText(input, "image");
        if (image == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Docker 执行需要 image");
        DockerSandboxExecutor.Sandbox sandbox = null;
        try {
            sandbox = docker.start(image);
            ObjectNode health = agent.checkSandbox(sandbox.endpoint());
            ObjectNode capabilities = agent.capabilitiesSandbox(sandbox.endpoint());
            ObjectNode result = mapper.createObjectNode();
            result.put("reachable", true);
            result.put("source", "docker");
            result.put("image", image);
            result.set("health", health);
            result.set("capabilities", capabilities);
            return result;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Docker Agent 检查失败：" + safeMessage(exception));
        } finally {
            if (sandbox != null) docker.stop(sandbox);
        }
    }

    public ObjectNode runtimeStatus() {
        DockerRuntimeStatus status = docker.status();
        ObjectNode result = mapper.createObjectNode();
        result.put("dockerEnabled", status.enabled());
        result.put("dockerAvailable", status.available());
        result.put("message", status.message());
        ArrayNode images = result.putArray("allowedImages");
        status.images().forEach(image -> {
            ObjectNode item = images.addObject();
            item.put("name", image.name());
            item.put("available", image.available());
        });
        ObjectNode limits = result.putObject("limits");
        limits.put("cpus", 1);
        limits.put("memory", "512m");
        limits.put("pids", 128);
        limits.put("timeoutSeconds", 300);
        return result;
    }

    public ObjectNode create(BenchmarkUser user, ObjectNode input) {
        return create(user, input, costs.snapshot(), null);
    }

    private ObjectNode create(BenchmarkUser user, ObjectNode input, ObjectNode pricingSnapshot, String retryOf) {
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        requireLocalExecution(input, HttpStatus.BAD_REQUEST);
        String source = input.path("source").asText("local").trim().toLowerCase(Locale.ROOT);
        String kind = input.path("kind").asText("evaluation").trim().toLowerCase(Locale.ROOT);
        if (!List.of("local", "docker", "runner").contains(source) || !List.of("evaluation", "pk").contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "运行类型或执行来源无效");
        }
        String endpoint = optionalText(input, "endpoint");
        String image = optionalText(input, "image");
        try {
            if ("local".equals(source)) endpoint = AgentHttpClient.normalize(requireText(input, "endpoint"));
            if ("docker".equals(source) && image == null) throw new IllegalArgumentException("Docker 执行需要 image");
            if ("runner".equals(source) && optionalText(input, "runnerId") == null) throw new IllegalArgumentException("runnerId is required for Runner execution");
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        String datasetId = requireText(input, "datasetId"), version = requireText(input, "version");
        String scope = input.path("scope").asText("sample"), budget = input.path("budget").asText("standard");
        if (!List.of("sample", "full").contains(scope) || !List.of("standard", "extended").contains(budget)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "任务范围或预算方案无效");
        }
        if (input.has("seed") && (!input.get("seed").isIntegralNumber() || !input.get("seed").canConvertToLong())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "随机种子必须为有效整数");
        }
        long seed = input.path("seed").asLong(42);
        var catalogSnapshot = catalog.snapshot(datasetId, version);
        List<TaskDefinition> all = catalogSnapshot.tasks();
        List<TaskDefinition> selected = new ArrayList<>("sample".equals(scope) ? all.subList(0, Math.min(8, all.size())) : all);
        if (selected.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "测评集没有可执行任务");
        if ("runner".equals(source)) runnerService.requireOwnedActive(user.id(), requireText(input, "runnerId"));
        List<Participant> participants = participants(input, kind, endpoint,
                "runner".equals(source) ? null : optionalText(input, "token"));
        String model = modelFor(participants), id = "run_" + UUID.randomUUID();
        ObjectNode config = mapper.createObjectNode();
        config.put("schema", "cryptobench-execution-plan-v1");
        config.put("datasetId", datasetId); config.put("version", version);
        config.put("seed", seed); config.put("budget", budget); config.put("scope", scope);
        config.put("model", model); config.put("kind", kind); config.put("source", source);
        config.put("executorType", "runner".equals(source) ? "runner" : "server");
        config.put("agentName", participants.getFirst().agent());
        config.put("evaluatorVersion", catalogSnapshot.evaluatorVersion());
        config.put("catalogSha256", catalogSnapshot.catalogSha256());
        config.set("taskIds", mapper.valueToTree(selected.stream().map(TaskDefinition::id).toList()));
        if (pricingSnapshot != null) config.set("pricingSnapshot", pricingSnapshot.deepCopy());
        if (endpoint != null) config.put("endpoint", endpoint);
        if (image != null) config.put("image", image);
        if ("runner".equals(source)) config.put("runnerId", requireText(input, "runnerId"));
        config.set("participants", publicParticipants(participants));
        Map<String, String> secrets = new LinkedHashMap<>();
        participants.forEach(participant -> {
            if (participant.token() != null) secrets.put(participant.key(), participant.token());
        });
        JsonNode gateway = input.path("gateway");
        if (gateway.isObject() && !gateway.path("providerId").asText().isBlank()) {
            if (!"docker".equals(source) || !gatewayProperties.enabled()) throw new ApiException(HttpStatus.BAD_REQUEST, "模型网关仅用于已启用网关的 Docker 测评");
            GatewayProperties.Provider provider = gatewayProperties.providersOrEmpty().stream()
                    .filter(p -> p.id().equals(gateway.path("providerId").asText())).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "模型供应商不在允许列表中"));
            ObjectNode gatewayConfig = config.putObject("gateway");
            gatewayConfig.put("providerId", provider.id()); gatewayConfig.put("baseUrl", provider.baseUrl());
            gatewayConfig.put("credentialRequired", true);
            ObjectNode priceModels = gatewayConfig.putObject("priceModels");
            for (Participant participant : participants) {
                if (!provider.modelsOrEmpty().contains(participant.model())) throw new ApiException(HttpStatus.BAD_REQUEST, "模型不在供应商允许列表中");
                priceModels.put(participant.key(), provider.pricingOrEmpty().getOrDefault(participant.model(), participant.model()));
            }
            if (retryOf != null && gateway.path("priceModels").isObject()) gatewayConfig.set("priceModels", gateway.path("priceModels").deepCopy());
            String key = optionalText(gateway, "apiKey");
            if (key == null || key.length() > 16000 || key.contains("\n") || key.contains("\r")) throw new ApiException(HttpStatus.BAD_REQUEST, "请提供本次运行使用的模型 API Key");
            secrets.put("gateway", key);
        }
        credentials.put(id, Map.copyOf(secrets));
        Run run = new Run(id, user.id(), kind, source, datasetId, version, budget, scope, model, null,
                seed, "queued", selected.size() * participants.size(), 0, 0, 0, config,
                null, null, null, retryOf, false, Instant.now().toString(), null, null);
        try {
            transactions.executeWithoutResult(transaction -> {
                runs.insert(run);
                int ordinal = 1;
                for (Participant participant : participants) for (TaskDefinition task : selected) {
                    runs.insertTask(id, taskKey(participant.key(), task.id()), ordinal++);
                }
                log(id, "INFO", "运行已排队，共 " + run.totalTasks() + " 个任务");
            });
        } catch (RuntimeException error) { credentials.remove(id); throw error; }
        return view(runs.find(id, false).orElseThrow());
    }

    public ObjectNode get(BenchmarkUser user, String id) {
        Run run = owned(user, id, false);
        ObjectNode result = view(run);
        ArrayNode tasks = result.putArray("tasks");
        for (RunTask task : runs.findTasks(id)) {
            ObjectNode node = mapper.createObjectNode();
            node.put("taskId", task.taskId());
            node.put("status", task.status());
            if (task.passed() != null) node.put("passed", task.passed());
            if (task.score() != null) node.put("score", task.score());
            if (task.costUsd() != null) node.put("costUsd", task.costUsd());
            if (task.elapsedMs() != null) node.put("elapsedMs", task.elapsedMs());
            tasks.add(node);
        }
        return result;
    }

    public List<ObjectNode> list(BenchmarkUser user) {
        return runs.findByOwner(user.id()).stream().map(this::view).toList();
    }

    public List<ObjectNode> list(BenchmarkUser user, String status, int page, int size) {
        List<String> statuses = status == null || status.isBlank()
                ? List.of()
                : java.util.Arrays.stream(status.split(","))
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .filter(value -> !value.isBlank())
                        .toList();
        List<String> allowed = List.of("queued", "running", "paused", "completed", "failed", "timeout", "cancelled");
        if (statuses.stream().anyMatch(value -> !allowed.contains(value))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "运行状态筛选条件无效");
        }
        return runs.findByOwner(user.id(), statuses, page, size).stream().map(this::view).toList();
    }

    public List<RunLog> logs(BenchmarkUser user, String id, long after) {
        owned(user, id, false);
        return runs.logs(id, Math.max(0, after), 100);
    }

    public ObjectNode cancel(BenchmarkUser user, String id) {
        Run run = owned(user, id, false);
        if ("cancelled".equals(run.status())) return get(user, id);
        if (!List.of("queued", "running", "paused").contains(run.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "当前运行已经结束，不能取消");
        }
        if (!runs.cancel(id)) return get(user, id);
        if ("runner".equals(run.source())) runnerService.cancelRun(id);
        Future<?> job = activeJobs.get(id);
        if (job != null) job.cancel(true);
        credentials.remove(id);
        log(id, "WARN", "已取消运行");
        return get(user, id);
    }

    public ObjectNode retry(BenchmarkUser user, String id, ObjectNode input) {
        Run old = owned(user, id, false);
        requireLocalExecution(old.config(), HttpStatus.CONFLICT);
        if (List.of("queued", "running", "paused").contains(old.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "请先取消或结束原运行，再重新测评");
        }
        if ("cryptobench-execution-plan-v1".equals(old.config().path("schema").asText())) selectedTasks(old);
        if (old.config().has("gateway")) gatewayPolicy(old.config());
        ObjectNode next = old.config().deepCopy();
        next.put("datasetId", old.datasetId()); next.put("version", old.version());
        next.put("scope", old.scope()); next.put("budget", old.budget()); next.put("kind", old.kind());
        next.put("source", old.source()); next.put("model", old.model()); next.put("seed", old.seed());
        Map<String, String> supplied = suppliedCredentials(input, old);
        List<String> missing = missingCredentials(old, supplied);
        if (!missing.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "请补录凭证：" + String.join(", ", missing));
        if ("pk".equals(old.kind())) {
            ArrayNode players = next.putArray("players");
            for (JsonNode previous : old.config().path("participants")) {
                ObjectNode player = ((ObjectNode) previous).deepCopy();
                player.remove("token");
                String token = supplied.get(player.path("key").asText());
                if (token != null) player.put("token", token);
                players.add(player);
            }
        } else if (supplied.containsKey("main")) next.put("token", supplied.get("main"));
        if (supplied.containsKey("gateway")) next.withObject("/gateway").put("apiKey", supplied.get("gateway"));
        ObjectNode snapshot = old.config().get("pricingSnapshot") instanceof ObjectNode node ? node.deepCopy() : null;
        return create(user, next, snapshot, old.id());
    }

    public ObjectNode resume(BenchmarkUser user, String id, ObjectNode input) {
        Run initial = owned(user, id, false);
        requireLocalExecution(initial.config(), HttpStatus.CONFLICT);
        if (List.of("queued", "running").contains(initial.status())) return get(user, id);
        if (!"paused".equals(initial.status())) throw new ApiException(HttpStatus.CONFLICT, "只有暂停的运行可以继续");
        selectedTasks(initial);
        if ("agent_changed".equals(initial.errorCode())) {
            throw new ApiException(HttpStatus.CONFLICT, "该 Agent 不支持恢复原实验，请基于原配置新建实验");
        }
        Map<String, String> supplied = new LinkedHashMap<>(credentials.getOrDefault(id, Map.of()));
        supplied.putAll(suppliedCredentials(input, initial));
        if (!missingCredentials(initial, supplied).isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "请补录所需 Agent 凭证");
        transactions.executeWithoutResult(transaction -> {
            Run current = owned(user, id, true);
            if (List.of("queued", "running").contains(current.status())) return;
            if (!"paused".equals(current.status())) throw new ApiException(HttpStatus.CONFLICT, "运行状态已改变，请刷新");
            credentials.put(id, Map.copyOf(supplied));
            if (!runs.resume(id)) throw new ApiException(HttpStatus.CONFLICT, "运行状态已改变，请刷新");
            log(id, "INFO", "用户确认继续实验，保留已保存的任务结果");
        });
        return get(user, id);
    }

    private static boolean isLocalExecution(ObjectNode config) {
        return ("server".equals(config.path("executorType").asText("server"))
                || "runner".equals(config.path("executorType").asText()))
                && !config.hasNonNull("bridgeId") && !config.hasNonNull("localAgentId");
    }

    private static void requireLocalExecution(ObjectNode config, HttpStatus status) {
        if (!isLocalExecution(config)) {
            throw new ApiException(status, "仅支持本机执行，原设备执行方式已移除，请新建本机测评");
        }
    }

    private Map<String, String> suppliedCredentials(ObjectNode input, Run run) {
        Map<String, String> values = new LinkedHashMap<>();
        if (input == null) return values;
        Set<String> allowed = new java.util.HashSet<>();
        run.config().path("participants").forEach(p -> allowed.add(p.path("key").asText()));
        if (run.config().path("gateway").path("credentialRequired").asBoolean()) allowed.add("gateway");
        JsonNode node = input.get("credentials");
        if (node != null) {
            if (!node.isObject()) throw new ApiException(HttpStatus.BAD_REQUEST, "credentials 格式无效");
            node.fields().forEachRemaining(entry -> {
                if (!allowed.contains(entry.getKey()) || !entry.getValue().isObject()) throw new ApiException(HttpStatus.BAD_REQUEST, "凭证参赛方无效");
                String token = optionalText(entry.getValue(), "token");
                if (token != null) {
                    if (token.length() > 16000 || token.contains("\n") || token.contains("\r")) throw new ApiException(HttpStatus.BAD_REQUEST, "凭证格式无效");
                    values.put(entry.getKey(), token);
                }
            });
        }
        String legacy = optionalText(input, "token");
        if (legacy != null && "evaluation".equals(run.kind())) values.put("main", legacy);
        return values;
    }

    private List<String> missingCredentials(Run run, Map<String, String> supplied) {
        List<String> result = new ArrayList<>();
        run.config().path("participants").forEach(p -> {
            String key = p.path("key").asText();
            if (p.path("credentialRequired").asBoolean() && !supplied.containsKey(key)) result.add(key);
        });
        if (run.config().path("gateway").path("credentialRequired").asBoolean() && !supplied.containsKey("gateway")) result.add("gateway");
        return result;
    }

    private List<TaskDefinition> selectedTasks(Run run) {
        if (!"cryptobench-execution-plan-v1".equals(run.config().path("schema").asText())) {
            throw new ApiException(HttpStatus.CONFLICT, "旧运行缺少执行快照，请基于原配置新建实验");
        }
        var current = catalog.snapshot(run.datasetId(), run.version());
        if (!current.catalogSha256().equals(run.config().path("catalogSha256").asText())
                || !current.evaluatorVersion().equals(run.config().path("evaluatorVersion").asText())) {
            throw new ApiException(HttpStatus.CONFLICT, "题库或评分器版本已改变，不能继续原实验");
        }
        Map<String, TaskDefinition> byId = new LinkedHashMap<>();
        current.tasks().forEach(task -> byId.put(task.id(), task));
        List<TaskDefinition> selected = new ArrayList<>();
        for (JsonNode id : run.config().path("taskIds")) {
            TaskDefinition task = byId.get(id.asText());
            if (task == null) throw new ApiException(HttpStatus.CONFLICT, "原实验任务已不可用");
            selected.add(task);
        }
        if (selected.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "原实验缺少任务快照");
        return selected;
    }

    private void execute(String id) {
        DockerSandboxExecutor.Sandbox sandbox = null;
        ModelGatewayService.Session gateway = null;
        long generation = -1;
        try {
            if (closing) return;
            generation = runs.claim(id, workerId, System.currentTimeMillis() + 90_000);
            if (generation < 0) return;
            Run run = runs.find(id, false).orElseThrow();
            if (!isLocalExecution(run.config())) {
                runs.pause(id, generation, "executor_removed", "原执行方式已移除，请新建本机测评"); return;
            }
            List<TaskDefinition> tasks;
            try { tasks = selectedTasks(run); }
            catch (RuntimeException unavailable) { runs.pause(id, generation, "catalog_changed", "题库快照无法匹配，请基于原配置新建实验"); return; }
            // Runner execution keeps private Agent access on the user's machine.
            if ("runner".equals(run.source())) {
                executeRunnerTasks(id, generation, run, tasks);
                return;
            }
            List<Participant> participants = recoveredParticipants(run.config(), credentials.getOrDefault(id, Map.of()));
            String endpoint = optionalText(run.config(), "endpoint");
            String source = run.source();
            if ("docker".equals(source)) {
                if (run.config().has("gateway")) gatewayPolicy(run.config());
                sandbox = docker.start(optionalText(run.config(), "image"), id);
                endpoint = sandbox.endpoint();
                log(id, "INFO", "Docker Agent 容器已启动");
                if (run.config().has("gateway")) gateway = gateways.openRun(id, sandbox,
                        run.config().path("gateway").path("providerId").asText(), credentials.getOrDefault(id, Map.of()).get("gateway"));
            }
            ObjectNode price = run.config().get("pricingSnapshot") instanceof ObjectNode node ? node : null;
            List<ParticipantRuntime> runtime = new ArrayList<>();
            boolean continuing = runs.findTasks(id).stream().anyMatch(t -> !"queued".equals(t.status()) || t.attempt() > 1)
                    || run.config().path("hasStarted").asBoolean();
            for (Participant participant : participants) {
                if (!runs.isCurrent(id, generation)) return;
                String target = endpointFor(source, endpoint, participant.endpoint());
                ObjectNode health = "docker".equals(source) ? agent.checkSandbox(target) : agent.check(target, participant.token());
                ObjectNode capabilities = "docker".equals(source) ? agent.capabilitiesSandbox(target) : agent.capabilities(target, participant.token());
                try {
                    validateModel(participant.model(), capabilities);
                    validateDataset(run.datasetId(), run.version(), capabilities);
                    validateTaskCapabilities(tasks, capabilities);
                } catch (IllegalArgumentException changed) {
                    if (!continuing) throw changed;
                    runs.pause(id, generation, "agent_changed", "Agent 的模型、题库或工具能力已改变，请基于原配置新建实验"); return;
                }
                String version = health.path("version").asText(capabilities.path("agentVersion").asText(participant.agentVersion()));
                ObjectNode savedCapabilities = run.config().withObject("/agentCapabilities");
                JsonNode previous = savedCapabilities.path(participant.key());
                if (continuing && (!capabilities.path("independentTasks").asBoolean() || !capabilities.path("idempotency").asBoolean()
                        || (!previous.path("version").asText("").isBlank() && !version.equals(previous.path("version").asText())))) {
                    runs.pause(id, generation, "agent_changed", "Agent 未声明独立任务和幂等恢复能力，或版本已改变；请基于原配置新建实验"); return;
                }
                ObjectNode capability = savedCapabilities.putObject(participant.key());
                capability.put("version", version);
                capability.put("independentTasks", capabilities.path("independentTasks").asBoolean());
                capability.put("idempotency", capabilities.path("idempotency").asBoolean());
                run.config().put("hasStarted", true);
                if (!runs.updateConfig(id, generation, run.config())) return;
                runtime.add(new ParticipantRuntime(participant, version));
                for (TaskDefinition task : tasks) {
                    if (!runs.isCurrent(id, generation)) return;
                    String key = taskKey(participant.key(), task.id());
                    RunTask stored = runs.findTasks(id).stream().filter(t -> t.taskId().equals(key)).findFirst().orElseThrow();
                    if (List.of("passed", "failed").contains(stored.status())) continue;
                    int attempt = runs.beginTask(id, key, generation);
                    if (attempt < 0) return;
                    long nano = System.nanoTime();
                    try {
                        ObjectNode request = mapper.createObjectNode();
                        request.put("runId", id); request.put("taskId", task.id()); request.put("participant", participant.key());
                        request.put("attempt", attempt); request.put("idempotencyKey", id + ":" + key);
                        request.put("model", participant.model()); request.put("seed", run.seed());
                        ObjectNode dataset = request.putObject("dataset");
                        dataset.put("id", run.datasetId()); dataset.put("version", run.version());
                        ObjectNode publicTask = request.putObject("task");
                        publicTask.put("question", task.question()); publicTask.set("input", task.input());
                        publicTask.put("category", task.category()); publicTask.set("publicFiles", mapper.valueToTree(task.publicFiles()));
                        int timeout = "extended".equals(run.budget()) ? 600 : 300;
                        request.put("deadline", Instant.now().plusSeconds(timeout).toString());
                        ObjectNode limits = request.putObject("budget");
                        limits.put("timeoutSeconds", timeout); limits.put("maxAgentCalls", "extended".equals(run.budget()) ? 40 : 20);
                        ObjectNode gatewayTask = gateway == null ? null : gateway.beginTask(key, participant.model(), "extended".equals(run.budget()) ? 40 : 20, Instant.now().plusSeconds(timeout));
                        if (gatewayTask != null) request.set("modelGateway", gatewayTask);
                        ObjectNode response = "docker".equals(source) ? agent.analyzeSandbox(target, request, Duration.ofSeconds(timeout))
                                : agent.analyze(target, participant.token(), request, Duration.ofSeconds(timeout));
                        if (gateway != null) {
                            ObjectNode accounting = gateway.finishTask(key);
                            response.set("gatewayAccounting", accounting);
                            response.set("usage", accounting.path("usageComplete").asBoolean() ? accounting.deepCopy() : mapper.createObjectNode());
                            response.put("usageSource", "provider_gateway");
                        }
                        else response.put("usageSource", "agent_reported");
                        response = redactResponse(response, credentials.getOrDefault(id, Map.of()).values());
                        if (gatewayTask != null) response = redactResponse(response, List.of(gatewayTask.path("token").asText()));
                        ObjectNode verification = catalog.evaluate(run.datasetId(), run.version(), task, response);
                        String status = verification.path("status").asText("invalid");
                        if (!List.of("passed", "failed").contains(status)) status = "invalid";
                        String pricingModel = run.config().path("gateway").path("priceModels").path(participant.key()).asText(participant.model());
                        Double cost = costs.calculate(price, pricingModel, response);
                        if (!runs.finishTask(id, key, generation, attempt, status, response, verification,
                                verification.path("passed").asBoolean(false), verification.path("score").asDouble(0), cost, elapsedMillis(nano))) return;
                        log(id, "INFO", participant.label() + " / " + task.id() + " 完成：" + status);
                        if ("invalid".equals(status)) { runs.finish(id, generation, "failed", "agent_execution_failed", "Agent 未生成有效结果", null); return; }
                    } catch (HttpTimeoutException timeout) {
                        runs.finishTask(id, key, generation, attempt, "timeout", null, errorResult("timeout", "Agent 响应超时"), false, 0d, null, elapsedMillis(nano));
                        runs.finish(id, generation, "timeout", "agent_timeout", "Agent 未在单题预算内完成", null); return;
                    }
                }
            }
            long claimedGeneration = generation;
            transactions.executeWithoutResult(transaction -> {
                Run current = runs.find(id, true).orElseThrow();
                if (!runs.isCurrent(id, claimedGeneration)) return;
                runs.recount(id);
                current = runs.find(id, false).orElseThrow();
                ObjectNode report = report(id, current.kind(), current.source(), current.datasetId(), current.version(), tasks, runtime,
                        current.passedTasks(), current.completedTasks(), current.scope(), current.budget(), current.seed(), current.model());
                String reportId = reports.createVerified(current.ownerId(), id, report);
                if (!runs.finish(id, claimedGeneration, "completed", null, null, reportId)) throw new IllegalStateException("Run completion lost its claim");
                log(id, "INFO", "运行完成，报告已保存");
            });
        } catch (InterruptedException interrupted) {
            if (generation >= 0) runs.pause(id, generation, "worker_restarted", "执行被中断，已完成题目已保存");
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (generation >= 0) {
                if (runs.cancelRequested(id)) runs.cancel(id);
                else if (error instanceof ExecutionPaused paused) runs.pause(id, generation, paused.reason, paused.getMessage());
                else if (error instanceof java.io.IOException) {
                    boolean authentication = safeMessage(error).contains("HTTP 401") || safeMessage(error).contains("HTTP 403");
                    if (authentication) credentials.remove(id);
                    boolean started = runs.find(id, false).orElseThrow().config().path("hasStarted").asBoolean();
                    if (authentication || started) runs.pause(id, generation, authentication ? "credentials_required" : "worker_restarted",
                            authentication ? "Agent 凭证已失效，请补录后继续" : "Agent 连接中断，请确认后继续或重新测评");
                    else runs.finish(id, generation, "failed", "agent_unreachable", "无法启动或连接 Agent，请检查本机服务或 Docker 配置", null);
                }
                else runs.finish(id, generation, "failed", "agent_execution_failed", redactText(safeMessage(error), credentials.getOrDefault(id, Map.of()).values()), null);
            }
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (gateway != null) gateway.close();
                if (sandbox != null) docker.stop(sandbox);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    /** Execute selected tasks through the Runner and keep scoring inside the website. */
    private void executeRunnerTasks(String id, long generation, Run run, List<TaskDefinition> tasks) throws Exception {
        String runnerId = run.config().path("runnerId").asText("");
        if (runnerId.isBlank()) throw new IllegalStateException("Runner configuration is missing runnerId");
        runnerService.requireOwnedActive(run.ownerId(), runnerId);
        List<Participant> participants = recoveredParticipants(run.config(), Map.of());
        List<ParticipantRuntime> runtime = new ArrayList<>();
        int timeout = "extended".equals(run.budget()) ? 600 : 300;
        for (Participant participant : participants) {
            runtime.add(new ParticipantRuntime(participant, participant.agentVersion()));
            for (TaskDefinition task : tasks) {
                if (!runs.isCurrent(id, generation)) return;
                String key = taskKey(participant.key(), task.id());
                RunTask stored = runs.findTasks(id).stream().filter(item -> item.taskId().equals(key)).findFirst().orElseThrow();
                if (List.of("passed", "failed").contains(stored.status())) continue;
                int attempt = runs.beginTask(id, key, generation);
                if (attempt < 0) return;
                long nano = System.nanoTime();
                ObjectNode request = runnerRequest(id, run, participant, task, key, attempt, timeout);
                String jobId = runnerService.enqueue(run.ownerId(), runnerId, id, generation, key, attempt, request);
                log(id, "INFO", "Runner task dispatched: " + task.id());
                RunnerJob job = runnerService.await(jobId, (timeout + 30L) * 1000L);
                if (job == null || List.of("queued", "leased").contains(job.status())) {
                    runs.finishTask(id, key, generation, attempt, "timeout", null,
                            errorResult("timeout", "Runner did not return before the task budget"), false, 0d, null, elapsedMillis(nano));
                    runs.finish(id, generation, "timeout", "runner_timeout", "Runner did not complete within the task budget", null);
                    return;
                }
                ObjectNode response = job.result() == null || !job.result().path("response").isObject()
                        ? null : (ObjectNode) job.result().path("response");
                if (response == null || !"completed".equals(job.status())) {
                    ObjectNode error = errorResult(job.status(), job.errorMessage() == null ? "Runner returned no valid result" : job.errorMessage());
                    runs.finishTask(id, key, generation, attempt, "failed", null, error, false, 0d, null, elapsedMillis(nano));
                    runs.finish(id, generation, "failed", "runner_execution_failed", error.path("reason").asText(), null);
                    return;
                }
                ObjectNode verification = catalog.evaluate(run.datasetId(), run.version(), task, response);
                String status = verification.path("status").asText("invalid");
                if (!List.of("passed", "failed").contains(status)) status = "invalid";
                Double cost = costs.calculate(run.config().get("pricingSnapshot") instanceof ObjectNode price ? price : null,
                        participant.model(), response);
                if (!runs.finishTask(id, key, generation, attempt, status, response, verification,
                        verification.path("passed").asBoolean(false), verification.path("score").asDouble(0), cost,
                        elapsedMillis(nano))) return;
                log(id, "INFO", participant.label() + " / " + task.id() + " complete: " + status);
                if ("invalid".equals(status)) {
                    runs.finish(id, generation, "failed", "agent_execution_failed", "Runner result failed validation", null);
                    return;
                }
            }
        }
        finishRunnerRun(id, generation, run, tasks, runtime);
    }

    /** Build a public task envelope; evaluator answers never enter this payload. */
    private ObjectNode runnerRequest(String id, Run run, Participant participant, TaskDefinition task,
                                     String key, int attempt, int timeout) {
        ObjectNode request = mapper.createObjectNode();
        request.put("runId", id); request.put("taskId", task.id()); request.put("participant", participant.key());
        request.put("attempt", attempt); request.put("idempotencyKey", id + ":" + key);
        request.put("agentName", participant.agent()); request.put("model", participant.model()); request.put("seed", run.seed());
        ObjectNode dataset = request.putObject("dataset"); dataset.put("id", run.datasetId()); dataset.put("version", run.version());
        ObjectNode publicTask = request.putObject("task"); publicTask.put("question", task.question());
        publicTask.set("input", task.input()); publicTask.put("category", task.category());
        publicTask.set("publicFiles", mapper.valueToTree(task.publicFiles()));
        request.put("deadline", Instant.now().plusSeconds(timeout).toString());
        ObjectNode budget = request.putObject("budget"); budget.put("timeoutSeconds", timeout);
        budget.put("maxAgentCalls", "extended".equals(run.budget()) ? 40 : 20);
        return request;
    }

    private void finishRunnerRun(String id, long generation, Run run, List<TaskDefinition> tasks,
                                 List<ParticipantRuntime> runtime) {
        transactions.executeWithoutResult(transaction -> {
            Run current = runs.find(id, true).orElseThrow();
            if (!runs.isCurrent(id, generation)) return;
            runs.recount(id); current = runs.find(id, false).orElseThrow();
            ObjectNode report = report(id, current.kind(), current.source(), current.datasetId(), current.version(), tasks,
                    runtime, current.passedTasks(), current.completedTasks(), current.scope(), current.budget(), current.seed(), current.model());
            String reportId = reports.createVerified(current.ownerId(), id, report);
            if (!runs.finish(id, generation, "completed", null, null, reportId)) throw new IllegalStateException("Run completion lost its claim");
            log(id, "INFO", "Runner run completed; report saved");
        });
    }

    private void gatewayPolicy(ObjectNode config) {
        JsonNode saved = config.path("gateway");
        GatewayProperties.Provider provider = gatewayProperties.providersOrEmpty().stream()
                .filter(p -> p.id().equals(saved.path("providerId").asText())).findFirst()
                .orElseThrow(() -> new ExecutionPaused("agent_changed", "原模型供应商配置已不可用"));
        if (!gatewayProperties.enabled() || !provider.baseUrl().equals(saved.path("baseUrl").asText(provider.baseUrl()))) {
            throw new ExecutionPaused("agent_changed", "模型网关配置已改变，请基于原配置新建实验");
        }
    }

    private static final class ExecutionPaused extends RuntimeException {
        private final String reason;
        ExecutionPaused(String reason, String message) { super(message); this.reason = reason; }
    }

    private ObjectNode redactResponse(ObjectNode response, java.util.Collection<String> secrets) {
        ObjectNode copy = response.deepCopy();
        redactNode(copy, secrets);
        return copy;
    }

    private void redactNode(JsonNode node, java.util.Collection<String> secrets) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> keys = new ArrayList<>(); object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = object.get(key);
                if (List.of("token", "apiKey", "api_key", "authorization", "accessToken").contains(key)) object.put(key, "[redacted]");
                else if (value.isTextual()) {
                    String text = value.asText();
                    for (String secret : secrets) if (!secret.isBlank()) text = text.replace(secret, "[redacted]");
                    object.put(key, text);
                } else redactNode(value, secrets);
                String publicKey = redactText(key, secrets);
                if (!publicKey.equals(key)) object.set(publicKey, object.remove(key));
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isTextual()) {
                    String text = array.get(i).asText();
                    for (String secret : secrets) if (!secret.isBlank()) text = text.replace(secret, "[redacted]");
                    array.set(i, mapper.getNodeFactory().textNode(text));
                } else redactNode(array.get(i), secrets);
            }
        }
    }

    private String redactText(String text, java.util.Collection<String> secrets) {
        for (String secret : secrets) if (secret != null && !secret.isBlank()) text = text.replace(secret, "[redacted]");
        return text;
    }

    private List<Participant> recoveredParticipants(ObjectNode config, Map<String, String> supplied) {
        List<Participant> result = new ArrayList<>();
        for (JsonNode item : config.path("participants")) {
            String key = item.path("key").asText();
            result.add(new Participant(key, item.path("label").asText("Agent"), item.path("agent").asText("Agent"),
                    item.path("model").asText("unknown"), item.path("agentVersion").asText("unknown"), strings(item.get("tools")),
                    optionalText(item, "endpoint"), supplied.get(key)));
        }
        if (result.isEmpty()) throw new IllegalStateException("运行配置缺少参赛方");
        return result;
    }

    private ObjectNode report(String id, String kind, String source, String datasetId, String version,
                              List<TaskDefinition> tasks, List<ParticipantRuntime> participants,
                              int passed, int completed, String scope, String budget, long seed, String model) {
        ObjectNode result = mapper.createObjectNode();
        result.put("id", "report_" + id.substring(4));
        result.put("schema", "cryptobench-run-v1");
        result.put("kind", kind);
        result.put("runId", id);
        result.put("datasetId", datasetId);
        result.put("version", version);
        result.put("budget", budget);
        result.put("scope", scope);
        result.put("source", source);
        result.put("model", model);
        result.put("seed", seed);
        result.put("total", kind.equals("pk") ? tasks.size() : completed);
        List<RunTask> stored = runs.findTasks(id);
        result.put("passed", kind.equals("pk") ? participants.get(0).passed(stored) : passed);
        result.put("score", kind.equals("pk") ? participants.get(0).score(stored, tasks.size())
                : (completed == 0 ? 0 : Math.round(passed * 1000d / completed) / 10d));
        result.put("verified", true);
        result.put("verificationStatus", "verified");
        ObjectNode plan = runs.find(id, false).orElseThrow().config();
        ObjectNode price = plan.get("pricingSnapshot") instanceof ObjectNode snapshot ? snapshot : null;
        result.put("evaluatorVersion", plan.path("evaluatorVersion").asText());
        result.put("catalogSha256", plan.path("catalogSha256").asText());
        result.put("pricingVersion", price == null ? null : price.path("version").asText());
        result.put("pricingSnapshotHash", price == null ? null : price.path("snapshotHash").asText());
        result.put("costCurrency", "USD");
        if (price != null) result.set("pricingSnapshot", price.deepCopy());
        result.put("executorType", plan.path("executorType").asText("server"));
        List<String> usageSources = stored.stream().map(t -> t.agentResponse() == null ? "unknown"
                : t.agentResponse().path("usageSource").asText("agent_reported")).distinct().toList();
        result.put("usageSource", usageSources.size() == 1 ? usageSources.getFirst() : "mixed");
        result.put("executionClaimsVerified", false);
        result.put("demo", false);
        result.put("createdAt", Instant.now().toString());
        result.put("date", Instant.now().toString());
        result.set("usage", usageSummary(stored));
        if (kind.equals("pk")) {
            addPkSummary(result, participants, stored, tasks);
            result.put("agent", participants.stream().map(p -> p.participant().agent()).reduce((a, b) -> a + " vs " + b).orElse("Agent"));
        } else {
            ParticipantRuntime p = participants.get(0);
            result.put("agent", p.participant().agent());
            result.put("agentVersion", p.agentVersion());
            result.put("time", totalElapsed(stored) / 1000d);
            Double cost = completeCost(stored);
            if (cost == null) result.putNull("cost"); else result.put("cost", cost);
            ReportMetrics.add(result, tasks, participantTasks(p, stored));
        }
        ArrayNode records = result.putArray("results");
        java.util.Set<String> costStatuses = new java.util.LinkedHashSet<>();
        for (RunTask task : stored) {
            ObjectNode item = records.addObject();
            item.put("taskId", task.taskId());
            String participantKey = task.taskId().split("::", 2)[0];
            item.put("participantKey", participantKey);
            tasks.stream().filter(selected -> selected.id().equals(ReportMetrics.taskId(task)))
                    .findFirst().ifPresent(selected -> item.put("category", ReportMetrics.category(selected)));
            String taskModel = participants.stream().filter(p -> p.participant().key().equals(participantKey))
                    .map(p -> p.participant().model()).findFirst().orElse(model);
            String pricingModel = plan.path("gateway").path("priceModels").path(participantKey).asText(taskModel);
            String costStatus = costs.costStatus(price, pricingModel, task.agentResponse());
            item.put("pricingModel", pricingModel);
            item.put("usageSource", task.agentResponse() == null ? "unknown" : task.agentResponse().path("usageSource").asText("agent_reported"));
            item.put("costStatus", costStatus);
            costStatuses.add(costStatus);
            item.put("status", task.status());
            if (task.passed() != null) item.put("passed", task.passed());
            if (task.verificationResult() != null) item.set("verification", task.verificationResult());
            if (task.costUsd() != null) item.put("costUsd", task.costUsd());
            if (task.elapsedMs() != null) item.put("elapsedMs", task.elapsedMs());
            ObjectNode usage = usageView(task.agentResponse());
            if (usage != null) item.set("usage", usage);
        }
        result.set("costStatuses", mapper.valueToTree(costStatuses));
        result.put("costStatus", price == null ? "missing_snapshot" : costStatuses.size() == 1 ? costStatuses.iterator().next() : "partial");
        return result;
    }

    private void addPkSummary(ObjectNode result, List<ParticipantRuntime> participants,
                              List<RunTask> stored, List<TaskDefinition> tasks) {
        int total = tasks.size();
        ArrayNode competitors = result.putArray("competitors");
        List<Double> scores = new ArrayList<>();
        for (ParticipantRuntime runtime : participants) {
            int participantPassed = runtime.passed(stored);
            double score = total == 0 ? 0 : Math.round(participantPassed * 1000d / total) / 10d;
            scores.add(score);
            ObjectNode item = competitors.addObject();
            item.put("participantKey", runtime.participant().key());
            item.put("label", runtime.participant().label());
            item.put("agent", runtime.participant().agent());
            item.put("model", runtime.participant().model());
            item.put("agentVersion", runtime.agentVersion());
            item.set("tools", mapper.valueToTree(runtime.participant().tools()));
            item.put("passed", participantPassed);
            item.put("total", total);
            item.put("score", score);
            item.put("time", runtime.elapsed(stored) / 1000d);
            List<RunTask> ownTasks = participantTasks(runtime, stored);
            ReportMetrics.add(item, tasks, ownTasks);
            item.set("usage", usageSummary(ownTasks));
            Double cost = runtime.cost(stored);
            if (cost == null) item.putNull("cost"); else item.put("cost", cost);
        }
        String winner = scores.get(0).doubleValue() == scores.get(1).doubleValue() ? "tie"
                : scores.get(0) > scores.get(1) ? participants.get(0).participant().label()
                : participants.get(1).participant().label();
        result.put("winner", winner);
        // Retain the A-side summary at the top level for existing clients.
        ReportMetrics.add(result, tasks, participantTasks(participants.get(0), stored));
    }

    private void finishCancelled(String id, String started, int completed, int passed, int failed) {
        runs.updateStatus(id, "cancelled", completed, passed, failed, "cancelled", "用户取消运行", null,
                true, started, Instant.now().toString());
        log(id, "WARN", "运行已取消");
    }

    private List<Participant> participants(ObjectNode input, String kind, String endpoint, String topToken) {
        if ("evaluation".equals(kind)) {
            String model = requireText(input, "model");
            String agentName = input.path("agentName").asText("本地 Agent");
            return List.of(new Participant("main", agentName, agentName, model,
                    input.path("agentVersion").asText("unknown"), List.<String>of(), endpoint, topToken));
        }
        JsonNode node = input.get("players");
        if (node == null || !node.isArray() || node.size() != 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PK 必须提供两个参赛方");
        }
        List<Participant> result = new ArrayList<>();
        int index = 0;
        for (JsonNode player : node) {
            if (!player.isObject()) throw new ApiException(HttpStatus.BAD_REQUEST, "PK 参赛方格式无效");
            String key = index++ == 0 ? "a" : "b";
            String model = text(player, "model", "参赛方模型");
            String label = player.path("label").asText(key.toUpperCase(Locale.ROOT));
            String agentName = player.path("agent").asText(label);
            String participantEndpoint = optionalText(player, "endpoint");
            if (participantEndpoint == null) participantEndpoint = endpoint;
            if (participantEndpoint != null && !participantEndpoint.isBlank()) {
                try { participantEndpoint = AgentHttpClient.normalize(participantEndpoint); }
                catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage()); }
            }
            List<String> tools = new ArrayList<>();
            player.path("tools").forEach(tool -> tools.add(tool.asText()));
            String token = optionalText(player, "token");
            if (token == null) token = topToken;
            result.add(new Participant(key, label, agentName, model,
                    player.path("agentVersion").asText("unknown"), tools, participantEndpoint, token));
        }
        return result;
    }

    private ArrayNode publicParticipants(List<Participant> participants) {
        ArrayNode result = mapper.createArrayNode();
        for (Participant participant : participants) {
            ObjectNode item = result.addObject();
            item.put("key", participant.key());
            item.put("label", participant.label());
            item.put("agent", participant.agent());
            item.put("model", participant.model());
            item.put("agentVersion", participant.agentVersion());
            item.put("credentialRequired", participant.token() != null && !participant.token().isBlank());
            item.set("tools", mapper.valueToTree(participant.tools()));
            if (participant.endpoint() != null) item.put("endpoint", participant.endpoint());
        }
        return result;
    }

    private void validateModel(String model, ObjectNode capabilities) {
        JsonNode models = capabilities.get("models");
        if (models != null && models.isArray() && models.size() > 0) {
            boolean supported = false;
            for (JsonNode item : models) if (model.equals(item.asText())) supported = true;
            if (!supported) throw new IllegalArgumentException("Agent 不支持请求的模型：" + model);
        }
    }

    /**
     * A capability document may advertise the dataset versions it can execute.
     * Older agents are allowed to omit this optional field; when it is present,
     * the server refuses a run that the agent explicitly says it cannot handle.
     */
    private void validateDataset(String datasetId, String version, ObjectNode capabilities) {
        JsonNode datasets = capabilities.get("datasets");
        if (datasets == null || !datasets.isArray() || datasets.isEmpty()) return;
        boolean supported = false;
        for (JsonNode item : datasets) {
            if (item.isTextual() && (item.asText().equals(datasetId)
                    || item.asText().equals(datasetId + "@" + version))) {
                supported = true;
            } else if (item.isObject()
                    && datasetId.equals(item.path("id").asText())
                    && version.equals(item.path("version").asText())) {
                supported = true;
            }
        }
        if (!supported) {
            throw new IllegalArgumentException("Agent 不支持测评集 " + datasetId + " v" + version);
        }
    }

    /** Validate task-level tool/capability requirements before sending secrets or task data. */
    private void validateTaskCapabilities(List<TaskDefinition> tasks, ObjectNode capabilities) {
        List<String> tools = strings(capabilities.get("tools"));
        List<String> advertised = strings(capabilities.get("capabilities"));
        for (TaskDefinition task : tasks) {
            for (String required : task.requiredTools()) {
                if (!tools.contains(required)) {
                    throw new IllegalArgumentException("Agent 缺少任务 " + task.id() + " 所需工具：" + required);
                }
            }
            for (String required : task.requiredCapabilities()) {
                if (!advertised.contains(required)) {
                    throw new IllegalArgumentException("Agent 缺少任务 " + task.id() + " 所需能力：" + required);
                }
            }
        }
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText());
        });
        return values;
    }

    private String endpointFor(String source, String runEndpoint, String participantEndpoint) {
        if ("docker".equals(source)) return runEndpoint;
        return AgentHttpClient.normalize(participantEndpoint == null ? runEndpoint : participantEndpoint);
    }

    private String modelFor(List<Participant> participants) {
        return participants.stream().map(Participant::model).reduce((a, b) -> a + " vs " + b).orElse("unknown");
    }

    private String taskKey(String participant, String task) {
        return participant + "::" + task;
    }

    private long elapsedMillis(long nano) {
        return Math.max(0L, (System.nanoTime() - nano) / 1_000_000L);
    }

    private long totalElapsed(List<RunTask> tasks) {
        return tasks.stream().filter(task -> task.elapsedMs() != null).mapToLong(RunTask::elapsedMs).sum();
    }

    private Double completeCost(List<RunTask> tasks) {
        if (tasks.stream().anyMatch(task -> task.costUsd() == null)) return null;
        return tasks.stream().mapToDouble(RunTask::costUsd).sum();
    }

    /** 汇总 Agent 返回的真实 Token 用量；部分任务缺失时保留覆盖数并隐藏不完整总量。 */
    private ObjectNode usageSummary(List<RunTask> tasks) {
        ObjectNode result = mapper.createObjectNode();
        result.put("taskCount", tasks.size());
        long input = 0;
        long output = 0;
        int known = 0;
        for (RunTask task : tasks) {
            JsonNode usage = task.agentResponse() == null ? null : task.agentResponse().get("usage");
            Long inputTokens = usageValue(usage, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens");
            Long outputTokens = usageValue(usage, "outputTokens", "output_tokens", "completionTokens", "completion_tokens");
            if (inputTokens == null || outputTokens == null) continue;
            input += inputTokens;
            output += outputTokens;
            known++;
        }
        result.put("knownTasks", known);
        if (known == tasks.size() && !tasks.isEmpty()) {
            result.put("inputTokens", input);
            result.put("outputTokens", output);
            result.put("totalTokens", input + output);
        } else {
            result.putNull("inputTokens");
            result.putNull("outputTokens");
            result.putNull("totalTokens");
        }
        return result;
    }

    private Long usageValue(JsonNode usage, String... names) {
        if (usage == null || !usage.isObject()) return null;
        for (String name : names) {
            JsonNode value = usage.get(name);
            if (value == null) continue;
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) return null;
            return value.asLong();
        }
        return null;
    }

    /** Expose only verified token counters in a task result; never copy the raw Agent response. */
    private ObjectNode usageView(JsonNode response) {
        JsonNode usage = response == null ? null : response.get("usage");
        Long input = usageValue(usage, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens");
        Long output = usageValue(usage, "outputTokens", "output_tokens", "completionTokens", "completion_tokens");
        if (input == null || output == null) return null;
        ObjectNode result = mapper.createObjectNode();
        result.put("inputTokens", input);
        result.put("outputTokens", output);
        result.put("totalTokens", input + output);
        return result;
    }

    private List<RunTask> participantTasks(ParticipantRuntime runtime, List<RunTask> stored) {
        String prefix = runtime.participant().key() + "::";
        return stored.stream().filter(task -> task.taskId().startsWith(prefix)).toList();
    }

    private ObjectNode view(Run run) {
        ObjectNode node = mapper.createObjectNode();
        node.put("runId", run.id());
        node.put("status", run.status());
        node.put("kind", run.kind());
        node.put("source", run.source());
        node.put("datasetId", run.datasetId());
        node.put("version", run.version());
        node.put("scope", run.scope());
        node.put("budget", run.budget());
        node.put("model", run.model());
        node.put("seed", run.seed());
        node.set("config", run.config().deepCopy());
        node.put("pauseReason", "paused".equals(run.status()) ? run.errorCode() : null);
        boolean localExecution = isLocalExecution(run.config());
        node.set("credentialsRequired", mapper.valueToTree(localExecution
                ? missingCredentials(run, credentials.getOrDefault(run.id(), Map.of())) : List.of()));
        node.put("canResume", localExecution && "paused".equals(run.status()) && !List.of("catalog_changed", "agent_changed").contains(run.errorCode() == null ? "" : run.errorCode()));
        node.put("totalTasks", run.totalTasks());
        ObjectNode progress = node.putObject("progress");
        progress.put("total", run.totalTasks());
        progress.put("completed", run.completedTasks());
        progress.put("passed", run.passedTasks());
        progress.put("failed", run.failedTasks());
        progress.put("percent", run.totalTasks() == 0 ? 0 : Math.round(run.completedTasks() * 10000d / run.totalTasks()) / 100d);
        node.put("reportId", run.reportId());
        node.put("errorCode", run.errorCode());
        node.put("errorMessage", run.errorMessage());
        node.put("retryOf", run.retryOf());
        node.put("createdAt", run.createdAt());
        node.put("startedAt", run.startedAt());
        node.put("finishedAt", run.finishedAt());
        return node;
    }

    private Run owned(BenchmarkUser user, String id, boolean lock) {
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        Run run = runs.find(id, lock).orElse(null);
        if (run == null || !run.ownerId().equals(user.id())) throw new ApiException(HttpStatus.NOT_FOUND, "运行不存在");
        return run;
    }

    private void log(String id, String level, String message) {
        runs.appendLog(id, level, message);
    }

    private ObjectNode errorResult(String status, String reason) {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", status);
        result.put("passed", false);
        result.put("score", 0);
        result.put("reason", reason);
        return result;
    }

    private String requireText(ObjectNode input, String field) {
        String value = input.path(field).asText("").trim();
        if (value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, field + " 不能为空");
        return value;
    }

    private String text(JsonNode input, String field, String label) {
        String value = input.path(field).asText("").trim();
        if (value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, label + " 不能为空");
        return value;
    }

    private String optionalText(ObjectNode input, String field) {
        String value = input.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private String optionalText(JsonNode input, String field) {
        String value = input.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(500, message.length()));
    }

    private record Participant(String key, String label, String agent, String model, String agentVersion,
                               List<String> tools, String endpoint, String token) {}

    private record ParticipantRuntime(Participant participant, String agentVersion) {
        int passed(List<RunTask> stored) {
            String prefix = participant.key() + "::";
            return (int) stored.stream().filter(task -> task.taskId().startsWith(prefix) && Boolean.TRUE.equals(task.passed())).count();
        }

        double score(List<RunTask> stored, int total) {
            return total == 0 ? 0 : Math.round(passed(stored) * 1000d / total) / 10d;
        }

        long elapsed(List<RunTask> stored) {
            String prefix = participant.key() + "::";
            return stored.stream().filter(task -> task.taskId().startsWith(prefix) && task.elapsedMs() != null)
                    .mapToLong(RunTask::elapsedMs).sum();
        }

        Double cost(List<RunTask> stored) {
            String prefix = participant.key() + "::";
            List<RunTask> own = stored.stream().filter(task -> task.taskId().startsWith(prefix)).toList();
            if (own.stream().anyMatch(task -> task.costUsd() == null)) return null;
            return own.stream().mapToDouble(RunTask::costUsd).sum();
        }
    }
}

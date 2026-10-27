package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.execution.model.Run;
import com.cryptobench.benchmark.execution.model.RunLog;
import com.cryptobench.benchmark.execution.model.RunTask;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.cryptobench.benchmark.execution.repository.RunRepository;
import com.cryptobench.benchmark.report.service.ReportService;
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

/** 创建和执行服务端运行；凭证只在当前 worker 的内存中短暂存在，永不持久化。 */
@Service
public class RunService {
    private final RunRepository runs;
    private final TaskCatalogService catalog;
    private final AgentHttpClient agent;
    private final ServerCostCalculator costs;
    private final DockerSandboxExecutor docker;
    private final ReportService reports;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(20), runnable -> {
                Thread thread = new Thread(runnable, "cryptobench-run-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<String, Future<?>> activeJobs = new ConcurrentHashMap<>();

    public RunService(RunRepository runs, TaskCatalogService catalog, AgentHttpClient agent,
                      ServerCostCalculator costs, ReportService reports,
                      com.fasterxml.jackson.databind.ObjectMapper mapper, DockerSandboxExecutor docker) {
        this.runs = runs;
        this.catalog = catalog;
        this.agent = agent;
        this.costs = costs;
        this.reports = reports;
        this.mapper = mapper;
        this.docker = docker;
    }

    @PostConstruct
    void recoverInterruptedRuns() {
        docker.reapOwnedResources();
        for (Run run : runs.recoverInterruptedRuns()) {
            try {
                submitRecovered(run);
                log(run.id(), "WARN", "应用重启后运行已重新排队");
            } catch (Exception exception) {
                runs.updateStatus(run.id(), "failed", 0, 0, 0, "worker_recovery_failed", safeMessage(exception), null,
                        false, null, Instant.now().toString());
                log(run.id(), "ERROR", "应用重启后恢复失败：" + safeMessage(exception));
            }
        }
    }

    @PreDestroy
    void shutdownWorkers() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public ObjectNode checkAgent(ObjectNode input) {
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
        String source = input.path("source").asText("local").trim().toLowerCase(Locale.ROOT);
        String kind = input.path("kind").asText("evaluation").trim().toLowerCase(Locale.ROOT);
        if (!List.of("local", "docker").contains(source)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "执行来源无效");
        }
        if (!List.of("evaluation", "pk").contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "运行类型无效");
        }

        String endpoint = optionalText(input, "endpoint");
        String image = optionalText(input, "image");
        try {
            if ("local".equals(source)) endpoint = AgentHttpClient.normalize(requireText(input, "endpoint"));
            if ("docker".equals(source) && image == null) throw new IllegalArgumentException("Docker 执行需要 image");
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }

        String datasetId = requireText(input, "datasetId");
        String version = requireText(input, "version");
        String scope = input.path("scope").asText("sample");
        String budget = input.path("budget").asText("standard");
        if (!List.of("sample", "full").contains(scope) || !List.of("standard", "extended").contains(budget)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "任务范围或预算方案无效");
        }
        List<TaskDefinition> all = catalog.tasks(datasetId, version);
        List<TaskDefinition> selected = new ArrayList<>("sample".equals(scope)
                ? all.subList(0, Math.min(8, all.size())) : all);
        if (selected.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "测评集没有可执行任务");
        String topToken = optionalText(input, "token");
        List<Participant> participants = participants(input, kind, endpoint, topToken);
        String model = modelFor(participants);
        String id = "run_" + UUID.randomUUID();
        String now = Instant.now().toString();
        ObjectNode config = mapper.createObjectNode();
        config.put("model", model);
        config.put("kind", kind);
        config.put("source", source);
        config.put("agentName", input.path("agentName").asText("本地 Agent"));
        config.put("evaluatorVersion", catalog.evaluatorVersion(datasetId, version));
        if (endpoint != null) config.put("endpoint", endpoint);
        if (image != null) config.put("image", image);
        config.set("participants", publicParticipants(participants));
        int totalTasks = selected.size() * participants.size();
        Run run = new Run(id, user.id(), kind, source, datasetId, version, budget, scope, model, null,
                input.path("seed").asLong(42), "queued", totalTasks, 0, 0, 0, config,
                null, null, null, optionalText(input, "retryOf"), false, now, null, null);
        runs.insert(run);
        int ordinal = 1;
        for (Participant participant : participants) {
            for (TaskDefinition task : selected) {
                runs.insertTask(id, taskKey(participant.key(), task.id()), ordinal++);
            }
        }
        log(id, "INFO", "运行已排队，共 " + totalTasks + " 个任务");
        final String runEndpoint = endpoint;
        final String runImage = image;
        try {
            Future<?> future = workers.submit(() -> execute(id, kind, source, runImage, runEndpoint, datasetId, version,
                    input.path("seed").asLong(42), budget, scope, selected, participants));
            activeJobs.put(id, future);
        } catch (RejectedExecutionException exception) {
            runs.updateStatus(id, "failed", 0, 0, 0, "worker_unavailable", "运行队列暂不可用", null,
                    false, null, Instant.now().toString());
            log(id, "ERROR", "运行队列暂不可用");
        }
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
        List<String> allowed = List.of("queued", "running", "completed", "failed", "timeout", "cancelled");
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
        if (!List.of("queued", "running").contains(run.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "当前运行已经结束，不能取消");
        }
        runs.requestCancel(id);
        Future<?> job = activeJobs.get(id);
        if ("queued".equals(run.status())) {
            runs.updateStatus(id, "cancelled", 0, 0, 0, "cancelled", "用户取消运行", null,
                    true, null, Instant.now().toString());
            if (job != null) job.cancel(false);
            activeJobs.remove(id);
        } else if (job != null) {
            job.cancel(true);
        }
        log(id, "WARN", "已请求取消运行");
        return get(user, id);
    }

    public ObjectNode retry(BenchmarkUser user, String id, ObjectNode input) {
        Run old = owned(user, id, false);
        if (List.of("queued", "running").contains(old.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "运行仍在执行，不能重试");
        }
        ObjectNode next = old.config().deepCopy();
        if (input != null) input.fields().forEachRemaining(entry -> next.set(entry.getKey(), entry.getValue()));
        next.put("datasetId", old.datasetId());
        next.put("version", old.version());
        next.put("scope", old.scope());
        next.put("budget", old.budget());
        next.put("kind", old.kind());
        next.put("source", old.source());
        next.put("model", old.model());
        if ("pk".equals(old.kind()) && old.config().has("participants")) {
            next.set("players", old.config().get("participants").deepCopy());
        }
        next.put("retryOf", old.id());
        return create(user, next);
    }

    private void execute(String id, String kind, String source, String image, String endpoint,
                         String datasetId, String version, long seed, String budget, String scope,
                         List<TaskDefinition> tasks, List<Participant> participants) {
        String started = Instant.now().toString();
        int completed = 0;
        int passed = 0;
        int failed = 0;
        boolean valid = true;
        boolean timedOut = false;
        boolean executionFailed = false;
        DockerSandboxExecutor.Sandbox sandbox = null;
        List<ParticipantRuntime> runtimeParticipants = new ArrayList<>();
        try {
            if (runs.cancelRequested(id)) {
                finishCancelled(id, started, completed, passed, failed);
                return;
            }
            if ("docker".equals(source)) {
                sandbox = docker.start(image, id);
                endpoint = sandbox.endpoint();
                log(id, "INFO", "Docker Agent 容器已启动");
            }
            if (!runs.markRunning(id, started)) {
                if (runs.cancelRequested(id)) {
                    finishCancelled(id, started, completed, passed, failed);
                    return;
                }
                throw new IllegalStateException("运行无法从队列转为执行状态");
            }
            for (Participant participant : participants) {
                String participantEndpoint = endpointFor(source, endpoint, participant.endpoint());
                log(id, "INFO", "检查 " + participant.label() + " 的 Agent 能力");
                ObjectNode health = "docker".equals(source)
                        ? agent.checkSandbox(participantEndpoint)
                        : agent.check(participantEndpoint, participant.token());
                ObjectNode capabilities = "docker".equals(source)
                        ? agent.capabilitiesSandbox(participantEndpoint)
                        : agent.capabilities(participantEndpoint, participant.token());
                validateModel(participant.model(), capabilities);
                validateDataset(datasetId, version, capabilities);
                validateTaskCapabilities(tasks, capabilities);
                String agentVersion = health.path("version").asText(
                        capabilities.path("agentVersion").asText(participant.agentVersion()));
                runtimeParticipants.add(new ParticipantRuntime(participant, agentVersion));
                log(id, "INFO", participant.label() + " 已连接，版本 " + agentVersion);
                for (TaskDefinition task : tasks) {
                    if (runs.cancelRequested(id)) {
                        finishCancelled(id, started, completed, passed, failed);
                        return;
                    }
                    String storageTaskId = taskKey(participant.key(), task.id());
                    String taskStarted = Instant.now().toString();
                    long nano = System.nanoTime();
                    try {
                        ObjectNode request = mapper.createObjectNode();
                        request.put("runId", id);
                        request.put("taskId", task.id());
                        request.put("participant", participant.key());
                        request.put("model", participant.model());
                        request.put("seed", seed);
                        ObjectNode dataset = request.putObject("dataset");
                        dataset.put("id", datasetId);
                        dataset.put("version", version);
                        ObjectNode taskInput = request.putObject("task");
                        taskInput.put("question", task.question());
                        ArrayNode files = taskInput.putArray("publicFiles");
                        task.publicFiles().forEach(files::add);
                        ObjectNode limits = request.putObject("budget");
                        limits.put("timeoutSeconds", "extended".equals(budget) ? 600 : 300);
                        limits.put("maxAgentCalls", "extended".equals(budget) ? 40 : 20);
                        ObjectNode response = "docker".equals(source)
                                ? agent.analyzeSandbox(participantEndpoint, request,
                                        Duration.ofSeconds("extended".equals(budget) ? 600 : 300))
                                : agent.analyze(participantEndpoint, participant.token(), request,
                                        Duration.ofSeconds("extended".equals(budget) ? 600 : 300));
                        long elapsed = elapsedMillis(nano);
                        ObjectNode verification = catalog.evaluate(datasetId, version, task, response);
                        boolean taskPassed = verification.path("passed").asBoolean(false);
                        String status = verification.path("status").asText("invalid");
                        Double cost = costs.calculate(participant.model(), response);
                        runs.updateTask(id, storageTaskId, status, response, verification, taskPassed,
                                verification.path("score").asDouble(0), cost, elapsed, taskStarted,
                                Instant.now().toString());
                        completed++;
                        if (taskPassed) passed++; else failed++;
                        if (!("passed".equals(status) || "failed".equals(status))) valid = false;
                        log(id, taskPassed ? "INFO" : "WARN", participant.label() + " / " + task.id() + " 完成：" + status);
                    } catch (HttpTimeoutException exception) {
                        runs.updateTask(id, storageTaskId, "timeout", null, errorResult("timeout", "Agent 响应超时"),
                                false, 0d, null, elapsedMillis(nano), taskStarted, Instant.now().toString());
                        completed++;
                        failed++;
                        valid = false;
                        timedOut = true;
                        executionFailed = true;
                        log(id, "WARN", participant.label() + " / " + task.id() + " 超时");
                    } catch (InterruptedException exception) {
                        if (runs.cancelRequested(id)) {
                            finishCancelled(id, started, completed, passed, failed);
                            return;
                        }
                        Thread.currentThread().interrupt();
                        executionFailed = true;
                        valid = false;
                        log(id, "ERROR", participant.label() + " / " + task.id() + " 被中断");
                        break;
                    } catch (Exception exception) {
                        runs.updateTask(id, storageTaskId, "invalid", null,
                                errorResult("invalid", safeMessage(exception)), false, 0d, null,
                                elapsedMillis(nano), taskStarted, Instant.now().toString());
                        completed++;
                        failed++;
                        valid = false;
                        executionFailed = true;
                        log(id, "ERROR", participant.label() + " / " + task.id() + " 执行失败");
                    }
                    runs.updateStatus(id, "running", completed, passed, failed, null, null, null, false, started, null);
                }
                if (executionFailed) break;
            }
            if (runs.cancelRequested(id)) {
                finishCancelled(id, started, completed, passed, failed);
                return;
            }
            if (!valid || executionFailed || timedOut) {
                String status = timedOut ? "timeout" : "failed";
                runs.updateStatus(id, status, completed, passed, failed,
                        timedOut ? "agent_timeout" : "agent_execution_failed",
                        timedOut ? "Agent 在预算时间内未完成任务" : "Agent 任务未能生成完整的可验证结果",
                        null, false, started, Instant.now().toString());
                log(id, "ERROR", "运行结束：" + status);
                return;
            }
            ObjectNode report = report(id, kind, source, datasetId, version, tasks, runtimeParticipants,
                    passed, completed, scope, budget, seed, modelFor(participants));
            String ownerId = runs.find(id, false).orElseThrow().ownerId();
            String reportId = reports.createVerified(ownerId, id, report);
            runs.updateStatus(id, "completed", completed, passed, failed, null, null, reportId, false,
                    started, Instant.now().toString());
            log(id, "INFO", "运行完成，报告 " + reportId + " 已生成");
        } catch (HttpTimeoutException exception) {
            runs.updateStatus(id, "timeout", completed, passed, failed, "agent_timeout",
                    "Agent 健康检查或能力查询超时", null, false, started, Instant.now().toString());
            log(id, "ERROR", "运行超时：Agent 未及时响应");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            finishCancelled(id, started, completed, passed, failed);
        } catch (Exception exception) {
            runs.updateStatus(id, "failed", completed, passed, failed, "agent_unavailable", safeMessage(exception),
                    null, false, started, Instant.now().toString());
            log(id, "ERROR", "运行失败：" + safeMessage(exception));
        } finally {
            if (sandbox != null) docker.stop(sandbox);
            activeJobs.remove(id);
        }
    }

    private void submitRecovered(Run run) {
        if ("local".equals(run.source()) && (run.config() == null || run.config().path("endpoint").asText("").isBlank())) {
            throw new IllegalStateException("本地 Agent 地址未保存，无法恢复运行");
        }
        List<TaskDefinition> all = catalog.tasks(run.datasetId(), run.version());
        List<TaskDefinition> selected = "sample".equals(run.scope())
                ? all.subList(0, Math.min(8, all.size())) : all;
        List<Participant> participants = recoveredParticipants(run.config());
        String endpoint = run.config() == null ? null : optionalText(run.config(), "endpoint");
        String image = run.config() == null ? null : optionalText(run.config(), "image");
        Future<?> future = workers.submit(() -> execute(run.id(), run.kind(), run.source(), image, endpoint,
                run.datasetId(), run.version(), run.seed(), run.budget(), run.scope(), selected, participants));
        activeJobs.put(run.id(), future);
    }

    private List<Participant> recoveredParticipants(ObjectNode config) {
        JsonNode node = config == null ? null : config.get("participants");
        if (node == null || !node.isArray() || node.isEmpty()) throw new IllegalStateException("运行配置缺少参赛方");
        List<Participant> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(new Participant(item.path("key").asText("main"), item.path("label").asText("Agent"),
                    item.path("agent").asText("Agent"), item.path("model").asText("unknown"),
                    item.path("agentVersion").asText("unknown"), strings(item.get("tools")),
                    optionalText(item, "endpoint"), null));
        }
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
        result.put("evaluatorVersion", catalog.evaluatorVersion(datasetId, version));
        result.put("pricingVersion", costs.pricingVersion());
        result.put("demo", false);
        result.put("createdAt", Instant.now().toString());
        result.put("date", Instant.now().toString());
        result.set("usage", usageSummary(stored));
        if (kind.equals("pk")) {
            addPkSummary(result, participants, stored, tasks.size());
            result.put("agent", participants.stream().map(p -> p.participant().agent()).reduce((a, b) -> a + " vs " + b).orElse("Agent"));
        } else {
            ParticipantRuntime p = participants.get(0);
            result.put("agent", p.participant().agent());
            result.put("agentVersion", p.agentVersion());
            result.put("time", totalElapsed(stored) / 1000d);
            Double cost = completeCost(stored);
            if (cost == null) result.putNull("cost"); else result.put("cost", cost);
            ArrayNode categories = result.putArray("categories");
            ObjectNode all = categories.addObject();
            all.put("name", "全部任务");
            all.put("total", completed);
            all.put("passed", passed);
        }
        ArrayNode records = result.putArray("results");
        for (RunTask task : stored) {
            ObjectNode item = records.addObject();
            item.put("taskId", task.taskId());
            item.put("status", task.status());
            if (task.passed() != null) item.put("passed", task.passed());
            if (task.verificationResult() != null) item.set("verification", task.verificationResult());
            if (task.costUsd() != null) item.put("costUsd", task.costUsd());
            if (task.elapsedMs() != null) item.put("elapsedMs", task.elapsedMs());
            ObjectNode usage = usageView(task.agentResponse());
            if (usage != null) item.set("usage", usage);
        }
        return result;
    }

    private void addPkSummary(ObjectNode result, List<ParticipantRuntime> participants,
                              List<RunTask> stored, int total) {
        ArrayNode competitors = result.putArray("competitors");
        List<Double> scores = new ArrayList<>();
        for (ParticipantRuntime runtime : participants) {
            int participantPassed = runtime.passed(stored);
            double score = total == 0 ? 0 : Math.round(participantPassed * 1000d / total) / 10d;
            scores.add(score);
            ObjectNode item = competitors.addObject();
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
            item.set("usage", usageSummary(ownTasks));
            Double cost = runtime.cost(stored);
            if (cost == null) item.putNull("cost"); else item.put("cost", cost);
        }
        String winner = scores.get(0).doubleValue() == scores.get(1).doubleValue() ? "tie"
                : scores.get(0) > scores.get(1) ? participants.get(0).participant().label()
                : participants.get(1).participant().label();
        result.put("winner", winner);
        ArrayNode categories = result.putArray("categories");
        ObjectNode all = categories.addObject();
        all.put("name", "全部任务");
        all.put("total", total);
        all.put("passed", participants.get(0).passed(stored));
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
        Run run = runs.find(id, lock).orElse(null);
        if (run == null || !run.ownerId().equals(user.id())) throw new ApiException(HttpStatus.NOT_FOUND, "运行不存在");
        return run;
    }

    private void log(String id, String level, String message) {
        runs.insertLog(new RunLog(id, runs.nextLogSequence(id), level, message, Instant.now().toString()));
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

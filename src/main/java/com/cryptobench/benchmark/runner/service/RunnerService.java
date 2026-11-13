package com.cryptobench.benchmark.runner.service;

import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.runner.dto.RunnerHeartbeatRequest;
import com.cryptobench.benchmark.runner.dto.RunnerPairRequest;
import com.cryptobench.benchmark.runner.dto.RunnerResultRequest;
import com.cryptobench.benchmark.runner.model.Runner;
import com.cryptobench.benchmark.runner.model.RunnerJob;
import com.cryptobench.benchmark.runner.repository.RunnerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pairing, authentication, and durable task exchange for the local Runner CLI. */
@Service
public class RunnerService {
    private static final long PAIRING_TTL_MS = 10 * 60_000L;
    private static final long RUNNER_LEASE_MS = 90_000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RunnerRepository runners;
    private final ObjectMapper mapper;

    public RunnerService(RunnerRepository runners, ObjectMapper mapper) {
        this.runners = runners;
        this.mapper = mapper;
    }

    /** Create a one-time code that the browser can show to the user. */
    @Transactional
    public ObjectNode createPairing(String ownerId) {
        long expiresAt = System.currentTimeMillis() + PAIRING_TTL_MS;
        String code = randomToken(18);
        runners.insertPairing("pair_" + UUID.randomUUID(), ownerId, sha256(code), expiresAt);
        ObjectNode response = mapper.createObjectNode();
        response.put("pairingCode", code);
        response.put("expiresAt", expiresAt);
        response.put("expiresInSeconds", PAIRING_TTL_MS / 1000);
        return response;
    }

    /** Consume a pairing code and return the long-lived Runner token exactly once. */
    @Transactional
    public ObjectNode register(RunnerPairRequest input) {
        String code = input.pairingCode().trim();
        String ownerId = runners.consumePairing(sha256(code), System.currentTimeMillis())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Runner 配对码无效或已过期"));
        long now = System.currentTimeMillis();
        String token = randomToken(32);
        ObjectNode agents = input.agents() == null ? mapper.createObjectNode() : input.agents().deepCopy();
        Runner runner = new Runner("runner_" + UUID.randomUUID(), ownerId, input.displayName().trim(),
                sha256(token), blankToNull(input.runnerVersion()), "online", agents, true, now, now, null);
        runners.insert(runner);
        ObjectNode response = runner.toResponse(now).toNode(mapper);
        response.put("runnerToken", token);
        return response;
    }

    /** List only Runners owned by the logged-in account. */
    public List<Runner.RunnerResponse> list(String ownerId) {
        long now = System.currentTimeMillis();
        return runners.findByOwner(ownerId).stream().map(runner -> runner.toResponse(now)).toList();
    }

    /** Revoke a Runner and make all future bearer requests fail. */
    @Transactional
    public void revoke(String ownerId, String id) {
        if (!runners.revoke(id, ownerId, System.currentTimeMillis())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Runner 不存在或已经撤销");
        }
    }

    /** Check a selected Runner before a run is created. */
    public Runner requireOwnedActive(String ownerId, String id) {
        Runner runner = runners.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Runner 不存在"));
        if (!ownerId.equals(runner.ownerId()) || runner.revokedAt() != null || !runner.acceptingTasks()) {
            throw new ApiException(HttpStatus.CONFLICT, "Runner 当前不可执行任务");
        }
        return runner;
    }

    /** Enqueue one server-generated task without exposing private evaluator data. */
    @Transactional
    public String enqueue(String ownerId, String runnerId, String runId, long generation,
                          String taskId, int attempt, ObjectNode request) {
        Runner runner = requireOwnedActive(ownerId, runnerId);
        String id = "rjob_" + UUID.randomUUID();
        runners.insertJob(new RunnerJob(id, runner.id(), ownerId, runId, generation, taskId, attempt,
                "queued", request.deepCopy(), null, null, null, System.currentTimeMillis(), null, null));
        return id;
    }

    /** Wait for one Runner callback while the existing run worker remains the evaluator owner. */
    public RunnerJob await(String jobId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1_000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            RunnerJob job = runners.findJob(jobId).orElse(null);
            if (job == null) return null;
            if (List.of("completed", "failed", "timeout", "cancelled").contains(job.status())) return job;
            Thread.sleep(250L);
        }
        return runners.findJob(jobId).orElse(null);
    }

    /** Authenticate a Runner bearer token and claim at most one task. */
    @Transactional
    public ObjectNode next(String authorization) {
        Runner runner = authenticate(authorization);
        runners.heartbeat(runner.id(), runner.runnerVersion(), runner.agents().toString(), true, System.currentTimeMillis());
        RunnerJob job = runners.claimNext(runner.id(), System.currentTimeMillis(), System.currentTimeMillis() + RUNNER_LEASE_MS).orElse(null);
        if (job == null) return null;
        ObjectNode response = mapper.createObjectNode();
        response.put("jobId", job.id()); response.put("runId", job.runId());
        response.put("taskId", job.taskId()); response.put("attempt", job.attempt());
        response.put("leaseUntil", job.leaseUntil() == null ? 0 : job.leaseUntil());
        response.set("request", job.request().deepCopy());
        return response;
    }

    /** Update Runner health and capabilities without ever accepting client scores. */
    public ObjectNode heartbeat(String authorization, RunnerHeartbeatRequest input) {
        Runner runner = authenticate(authorization);
        String version = input.runnerVersion() == null ? runner.runnerVersion() : blankToNull(input.runnerVersion().trim());
        ObjectNode agents = input.agents() == null ? runner.agents() : input.agents().deepCopy();
        boolean accepting = input.acceptingTasks() == null || input.acceptingTasks();
        if (!runners.heartbeat(runner.id(), version, agents.toString(), accepting, System.currentTimeMillis())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Runner 已撤销");
        }
        ObjectNode response = mapper.createObjectNode(); response.put("ok", true); response.put("runnerId", runner.id());
        return response;
    }

    /** Store a callback once; the nested Agent response is evaluated later by RunService. */
    public ObjectNode result(String authorization, String jobId, RunnerResultRequest input) {
        Runner runner = authenticate(authorization);
        String status = input.status() == null ? "failed" : input.status().trim().toLowerCase();
        if (!List.of("completed", "failed", "timeout").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Runner 任务状态无效");
        }
        ObjectNode payload = mapper.createObjectNode();
        if (input.response() != null) payload.set("response", input.response().deepCopy());
        if (input.elapsedMs() != null && input.elapsedMs() >= 0) payload.put("elapsedMs", input.elapsedMs());
        boolean accepted = runners.completeJob(jobId, runner.id(), status, payload.toString(), input.errorCode(),
                input.errorMessage() == null ? null : input.errorMessage().trim(), System.currentTimeMillis());
        if (!accepted) throw new ApiException(HttpStatus.NOT_FOUND, "Runner 任务不存在或租约已失效");
        ObjectNode response = mapper.createObjectNode(); response.put("ok", true); response.put("jobId", jobId);
        return response;
    }

    /** Mark queued or leased tasks cancelled when the owner cancels a run. */
    public void cancelRun(String runId) {
        // The run status remains the source of truth; this only releases Runner work promptly.
        runners.cancelForRun(runId);
    }

    /** Return an authenticated Runner or a generic unauthorized response. */
    private Runner authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Runner 需要 Bearer Token");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isBlank() || token.length() > 256) throw new ApiException(HttpStatus.UNAUTHORIZED, "Runner Token 无效");
        Runner runner = runners.findByTokenHash(sha256(token)).orElse(null);
        if (runner == null || runner.revokedAt() != null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Runner Token 无效");
        return runner;
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes]; RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) { throw new IllegalStateException("Unable to hash Runner token", exception); }
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}

package com.cryptobench.benchmark.runner.repository;

import com.cryptobench.benchmark.common.JsonDocuments;
import com.cryptobench.benchmark.runner.model.Runner;
import com.cryptobench.benchmark.runner.model.RunnerJob;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access for Runner identities, one-time pairings, and the durable task queue. */
@Repository
public class RunnerRepository {
    private final JdbcTemplate jdbc;
    private final JsonDocuments json;

    public RunnerRepository(JdbcTemplate jdbc, JsonDocuments json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Save a short-lived pairing hash; the raw code never reaches the database. */
    public void insertPairing(String id, String ownerId, String codeHash, long expiresAt) {
        jdbc.update("INSERT INTO benchmark_runner_pairings(id,owner_id,code_hash,expires_at) VALUES(?,?,?,?)",
                id, ownerId, codeHash, expiresAt);
    }

    /** Atomically consume a valid pairing code and return its owner. */
    public Optional<String> consumePairing(String codeHash, long now) {
        List<String> owners = jdbc.query("SELECT owner_id FROM benchmark_runner_pairings WHERE code_hash=? AND used_at IS NULL AND expires_at>? FOR UPDATE",
                (row, index) -> row.getString(1), codeHash, now);
        if (owners.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE benchmark_runner_pairings SET used_at=? WHERE code_hash=? AND used_at IS NULL", now, codeHash);
        return Optional.of(owners.getFirst());
    }

    /** Persist only the digest of the long-lived Runner bearer token. */
    public void insert(Runner runner) {
        jdbc.update("""
                INSERT INTO benchmark_runners
                (id,owner_id,display_name,token_hash,runner_version,status,agents_payload,accepting_tasks,created_at,last_seen,revoked_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, runner.id(), runner.ownerId(), runner.displayName(), runner.tokenHash(), runner.runnerVersion(),
                runner.status(), json.write(runner.agents()), runner.acceptingTasks(), runner.createdAt(),
                runner.lastSeen(), runner.revokedAt());
    }

    public Optional<Runner> findByTokenHash(String tokenHash) {
        return jdbc.query("SELECT * FROM benchmark_runners WHERE token_hash=?", this::runner, tokenHash)
                .stream().findFirst();
    }

    public Optional<Runner> find(String id) {
        return jdbc.query("SELECT * FROM benchmark_runners WHERE id=?", this::runner, id).stream().findFirst();
    }

    public List<Runner> findByOwner(String ownerId) {
        return jdbc.query("SELECT * FROM benchmark_runners WHERE owner_id=? ORDER BY created_at,id", this::runner, ownerId);
    }

    public boolean revoke(String id, String ownerId, long now) {
        return jdbc.update("UPDATE benchmark_runners SET status='revoked',revoked_at=?,accepting_tasks=FALSE WHERE id=? AND owner_id=? AND revoked_at IS NULL",
                now, id, ownerId) == 1;
    }

    public boolean heartbeat(String id, String version, String agents, boolean accepting, long now) {
        return jdbc.update("UPDATE benchmark_runners SET runner_version=?,agents_payload=?,accepting_tasks=?,last_seen=?,status='online' WHERE id=? AND revoked_at IS NULL",
                version, agents, accepting, now, id) == 1;
    }

    /** Enqueue a task for the selected Runner. */
    public void insertJob(RunnerJob job) {
        jdbc.update("""
                INSERT INTO benchmark_runner_jobs
                (id,runner_id,owner_id,run_id,generation,task_id,attempt,status,request_payload,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, job.id(), job.runnerId(), job.ownerId(), job.runId(), job.generation(), job.taskId(),
                job.attempt(), job.status(), json.write(job.request()), job.createdAt());
    }

    /** Claim one queued job and renew expired leases before selecting it. */
    public Optional<RunnerJob> claimNext(String runnerId, long now, long leaseUntil) {
        jdbc.update("UPDATE benchmark_runner_jobs SET status='queued',lease_until=NULL WHERE runner_id=? AND status='leased' AND lease_until<?",
                runnerId, now);
        List<RunnerJob> candidates = jdbc.query("SELECT * FROM benchmark_runner_jobs WHERE runner_id=? AND status='queued' ORDER BY created_at,id LIMIT 1 FOR UPDATE",
                this::job, runnerId);
        if (candidates.isEmpty()) return Optional.empty();
        RunnerJob candidate = candidates.getFirst();
        if (jdbc.update("UPDATE benchmark_runner_jobs SET status='leased',lease_until=? WHERE id=? AND status='queued'",
                leaseUntil, candidate.id()) != 1) return Optional.empty();
        return findJob(candidate.id());
    }

    public Optional<RunnerJob> findJob(String id) {
        return jdbc.query("SELECT * FROM benchmark_runner_jobs WHERE id=?", this::job, id).stream().findFirst();
    }

    /** Complete a lease once; repeated identical callbacks are harmless. */
    public boolean completeJob(String id, String runnerId, String status, String result,
                               String errorCode, String errorMessage, long finishedAt) {
        int changed = jdbc.update("""
                UPDATE benchmark_runner_jobs SET status=?,result_payload=?,error_code=?,error_message=?,finished_at=?,lease_until=NULL
                WHERE id=? AND runner_id=? AND status='leased'
                """, status, result, errorCode, errorMessage, finishedAt, id, runnerId);
        return changed == 1 || jdbc.query("SELECT id FROM benchmark_runner_jobs WHERE id=? AND runner_id=? AND status IN ('completed','failed','timeout')",
                (row, index) -> row.getString(1), id, runnerId).size() == 1;
    }

    public int cancelForRun(String runId) {
        return jdbc.update("UPDATE benchmark_runner_jobs SET status='cancelled',finished_at=?,lease_until=NULL WHERE run_id=? AND status IN ('queued','leased')",
                System.currentTimeMillis(), runId);
    }

    private Runner runner(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Runner(row.getString("id"), row.getString("owner_id"), row.getString("display_name"),
                row.getString("token_hash"), row.getString("runner_version"), row.getString("status"),
                json.read(row.getString("agents_payload")), row.getBoolean("accepting_tasks"),
                row.getLong("created_at"), row.getLong("last_seen"), (Long) row.getObject("revoked_at"));
    }

    private RunnerJob job(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        String result = row.getString("result_payload");
        return new RunnerJob(row.getString("id"), row.getString("runner_id"), row.getString("owner_id"),
                row.getString("run_id"), row.getLong("generation"), row.getString("task_id"), row.getInt("attempt"),
                row.getString("status"), json.read(row.getString("request_payload")),
                result == null ? null : json.read(result), row.getString("error_code"), row.getString("error_message"),
                row.getLong("created_at"), (Long) row.getObject("lease_until"), (Long) row.getObject("finished_at"));
    }
}

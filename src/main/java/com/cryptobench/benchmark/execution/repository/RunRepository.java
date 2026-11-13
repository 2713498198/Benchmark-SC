package com.cryptobench.benchmark.execution.repository;

import com.cryptobench.benchmark.common.JsonDocuments;
import com.cryptobench.benchmark.execution.model.Run;
import com.cryptobench.benchmark.execution.model.RunLog;
import com.cryptobench.benchmark.execution.model.RunTask;
import com.cryptobench.benchmark.persistence.MybatisDatabase;
import com.cryptobench.benchmark.persistence.MybatisRowMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RunRepository {
    private final MybatisDatabase database;
    private final JsonDocuments json;
    private final MybatisRowMapper<Run> runMapper;
    private final MybatisRowMapper<RunTask> taskMapper;

    public RunRepository(MybatisDatabase database, JsonDocuments json) {
        this.database = database;
        this.json = json;
        this.runMapper = (row, i) -> new Run(row.getString("run_id"), row.getString("owner_id"),
                row.getString("kind"), row.getString("execution_source"), row.getString("dataset_id"),
                row.getString("dataset_version"), row.getString("budget"), row.getString("task_scope"),
                row.getString("model_id"), row.getString("agent_version"), row.getLong("seed"),
                row.getString("status"), row.getInt("total_tasks"), row.getInt("completed_tasks"),
                row.getInt("passed_tasks"), row.getInt("failed_tasks"), json.read(row.getString("config_payload")),
                row.getString("error_code"), row.getString("error_message"), row.getString("report_id"),
                row.getString("retry_of"), row.getBoolean("cancel_requested"), row.getString("created_at"),
                row.getString("started_at"), row.getString("finished_at"));
        this.taskMapper = (row, i) -> new RunTask(row.getString("run_id"), row.getString("task_id"),
                row.getInt("ordinal"), row.getString("status"), row.getInt("attempt"),
                nullableJson(row.getString("agent_response")), nullableJson(row.getString("verification_result")),
                (Boolean) row.getObject("passed"), (Double) row.getObject("score"),
                (Double) row.getObject("cost_usd"), (Long) row.getObject("elapsed_ms"),
                row.getString("started_at"), row.getString("finished_at"));
    }

    public void insert(Run run) {
        database.update("""
                INSERT INTO benchmark_runs
                (run_id, owner_id, kind, execution_source, dataset_id, dataset_version, budget, task_scope,
                 model_id, agent_version, seed, status, total_tasks, completed_tasks, passed_tasks, failed_tasks,
                 config_payload, error_code, error_message, report_id, retry_of, cancel_requested, created_at,
                 started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.id(), run.ownerId(), run.kind(), run.source(), run.datasetId(), run.version(), run.budget(),
                run.scope(), run.model(), run.agentVersion(), run.seed(), run.status(), run.totalTasks(),
                run.completedTasks(), run.passedTasks(), run.failedTasks(), json.write(run.config()), run.errorCode(),
                run.errorMessage(), run.reportId(), run.retryOf(), run.cancelRequested(), run.createdAt(),
                run.startedAt(), run.finishedAt());
    }

    public Optional<Run> find(String id, boolean forUpdate) {
        return database.query("SELECT * FROM benchmark_runs WHERE run_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                runMapper, id).stream().findFirst();
    }

    public List<Run> findByOwner(String ownerId) {
        return database.query("SELECT * FROM benchmark_runs WHERE owner_id = ? ORDER BY created_at DESC", runMapper, ownerId);
    }

    public List<Run> findByOwner(String ownerId, List<String> statuses, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        StringBuilder sql = new StringBuilder("SELECT * FROM benchmark_runs WHERE owner_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(ownerId);
        if (statuses != null && !statuses.isEmpty()) {
            List<String> allowed = statuses.stream().filter(List.of("queued", "running", "paused", "completed", "failed", "timeout", "cancelled")::contains).toList();
            if (!allowed.isEmpty()) {
                sql.append(" AND status IN (").append("?,".repeat(allowed.size()).replaceAll(",$", "")).append(")");
                args.addAll(allowed);
            }
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(safeSize);
        args.add(safePage * safeSize);
        return database.query(sql.toString(), runMapper, args.toArray());
    }

    public void updateStatus(String id, String status, int completed, int passed, int failed,
                             String errorCode, String errorMessage, String reportId,
                             boolean cancelRequested, String startedAt, String finishedAt) {
        database.update("""
                UPDATE benchmark_runs SET status=?, completed_tasks=?, passed_tasks=?, failed_tasks=?,
                error_code=?, error_message=?, report_id=?, cancel_requested=?, started_at=?, finished_at=?
                WHERE run_id=?
                """, status, completed, passed, failed, errorCode, errorMessage, reportId, cancelRequested,
                startedAt, finishedAt, id);
    }

    /** Claim a queued run exactly once; a queued cancellation must win the worker race. */
    public boolean markRunning(String id, String startedAt) {
        return database.update("""
                UPDATE benchmark_runs SET status='running', started_at=?
                WHERE run_id=? AND status='queued' AND cancel_requested=FALSE
                """, startedAt, id) == 1;
    }

    public void requestCancel(String id) {
        database.update("UPDATE benchmark_runs SET cancel_requested=TRUE WHERE run_id=? AND status IN ('queued','running','paused')", id);
    }

    public boolean cancelRequested(String id) {
        return Boolean.TRUE.equals(database.queryForObject("SELECT cancel_requested FROM benchmark_runs WHERE run_id=?",
                Boolean.class, id));
    }

    public int markInterruptedRuns() {
        return database.update("UPDATE benchmark_runs SET status='failed', error_code='worker_restarted', error_message='应用重启时运行被中断', finished_at=? WHERE status IN ('queued','running')",
                java.time.Instant.now().toString());
    }

    /** Preserve committed results. A lost worker needs an explicit resume; untouched queued jobs stay queued. */
    @Transactional
    public List<Run> recoverInterruptedRuns() {
        List<Run> interrupted = database.query("SELECT * FROM benchmark_runs WHERE status='running' AND (lease_until IS NULL OR lease_until<?) FOR UPDATE",
                runMapper, System.currentTimeMillis());
        for (Run run : interrupted) {
            if (run.cancelRequested()) cancel(run.id());
            else pause(run.id(), "worker_restarted", "执行中断，已保存完成题目，请确认后继续");
        }
        return findQueued(100);
    }

    public List<Run> findQueued(int limit) {
        return database.query("SELECT * FROM benchmark_runs WHERE status='queued' AND cancel_requested=FALSE ORDER BY created_at,run_id LIMIT ?",
                runMapper, Math.min(100, Math.max(1, limit)));
    }

    /** Other live workers may share this database; cleanup must preserve their leases. */
    public java.util.Set<String> activeClaimIds() {
        return new java.util.HashSet<>(database.query("SELECT run_id FROM benchmark_runs WHERE status='running' AND lease_until>? AND cancel_requested=FALSE",
                (row, index) -> row.getString("run_id"), System.currentTimeMillis()));
    }

    @Transactional
    public long claim(String id, String worker, long leaseUntil) {
        int changed = database.update("""
                UPDATE benchmark_runs SET status='running', execution_generation=execution_generation+1,
                claim_owner=?, lease_until=?, started_at=COALESCE(started_at,?), error_code=NULL, error_message=NULL
                WHERE run_id=? AND status='queued' AND cancel_requested=FALSE
                """, worker, leaseUntil, java.time.Instant.now().toString(), id);
        return changed == 1 ? generation(id) : -1;
    }

    public long generation(String id) {
        Long value = database.queryForObject("SELECT execution_generation FROM benchmark_runs WHERE run_id=?", Long.class, id);
        return value == null ? -1 : value;
    }

    public boolean isCurrent(String id, long generation) {
        Integer count = database.queryForObject("SELECT COUNT(*) FROM benchmark_runs WHERE run_id=? AND execution_generation=? AND status='running' AND cancel_requested=FALSE",
                Integer.class, id, generation);
        return count != null && count == 1;
    }

    public void renewLease(String id, String worker, long until) {
        database.update("UPDATE benchmark_runs SET lease_until=? WHERE run_id=? AND claim_owner=? AND status='running' AND cancel_requested=FALSE", until, id, worker);
    }

    public void updateConfig(String id, ObjectNode config) {
        database.update("UPDATE benchmark_runs SET config_payload=? WHERE run_id=?", json.write(config), id);
    }

    public boolean updateConfig(String id, long generation, ObjectNode config) {
        return database.update("UPDATE benchmark_runs SET config_payload=? WHERE run_id=? AND execution_generation=? AND status='running' AND cancel_requested=FALSE",
                json.write(config), id, generation) == 1;
    }

    @Transactional
    public boolean resume(String id) {
        Run run = find(id, true).orElseThrow();
        if (!"paused".equals(run.status()) || run.cancelRequested()) return false;
        database.update("UPDATE benchmark_run_tasks SET status='queued' WHERE run_id=? AND status IN ('running','interrupted')", id);
        recount(id);
        return database.update("UPDATE benchmark_runs SET status='queued',error_code=NULL,error_message=NULL,finished_at=NULL,claim_owner=NULL,lease_until=NULL WHERE run_id=? AND status='paused' AND cancel_requested=FALSE", id) == 1;
    }

    @Transactional
    public void pause(String id, String reason, String message) {
        Run run = find(id, true).orElseThrow();
        if (!List.of("queued", "running").contains(run.status()) || run.cancelRequested()) return;
        database.update("UPDATE benchmark_runs SET status='paused',error_code=?,error_message=?,execution_generation=execution_generation+1,claim_owner=NULL,lease_until=NULL WHERE run_id=?", reason, message, id);
        interruptAttempts(id);
        recount(id);
    }

    @Transactional
    public boolean pause(String id, long generation, String reason, String message) {
        find(id, true).orElseThrow();
        if (!isCurrent(id, generation)) return false;
        pause(id, reason, message);
        return true;
    }

    @Transactional
    public boolean cancel(String id) {
        Run run = find(id, true).orElseThrow();
        if (!List.of("queued", "running", "paused").contains(run.status())) return false;
        database.update("UPDATE benchmark_runs SET status='cancelled',cancel_requested=TRUE,error_code='cancelled',error_message='用户取消运行',finished_at=?,execution_generation=execution_generation+1,claim_owner=NULL,lease_until=NULL WHERE run_id=?", java.time.Instant.now().toString(), id);
        interruptAttempts(id);
        recount(id);
        return true;
    }

    private void interruptAttempts(String id) {
        String now = java.time.Instant.now().toString();
        database.update("UPDATE benchmark_run_task_attempts SET status='interrupted',finished_at=? WHERE run_id=? AND status='running'", now, id);
        database.update("UPDATE benchmark_run_tasks SET status='interrupted',finished_at=? WHERE run_id=? AND status='running'", now, id);
    }

    @Transactional
    public int beginTask(String id, String task, long generation) {
        find(id, true).orElseThrow();
        if (!isCurrent(id, generation)) return -1;
        List<RunTask> existing = database.query("SELECT * FROM benchmark_run_tasks WHERE run_id=? AND task_id=?", taskMapper, id, task);
        if (existing.isEmpty() || List.of("passed", "failed").contains(existing.getFirst().status())) return -1;
        Integer previous = database.queryForObject("SELECT COALESCE(MAX(attempt),0) FROM benchmark_run_task_attempts WHERE run_id=? AND task_id=?", Integer.class, id, task);
        int attempt = (previous == null ? 0 : previous) + 1;
        String now = java.time.Instant.now().toString();
        database.update("INSERT INTO benchmark_run_task_attempts(run_id,task_id,attempt,execution_generation,status,started_at) VALUES(?,?,?,?,'running',?)", id, task, attempt, generation, now);
        database.update("UPDATE benchmark_run_tasks SET status='running',attempt=?,started_at=?,finished_at=NULL WHERE run_id=? AND task_id=?", attempt, now, id, task);
        return attempt;
    }

    @Transactional
    public boolean finishTask(String id, String task, long generation, int attempt, String status,
                              ObjectNode response, ObjectNode verification, Boolean passed, Double score,
                              Double cost, Long elapsed) {
        find(id, true).orElseThrow();
        if (!isCurrent(id, generation)) return false;
        String now = java.time.Instant.now().toString();
        int changed = database.update("UPDATE benchmark_run_task_attempts SET status=?,finished_at=? WHERE run_id=? AND task_id=? AND attempt=? AND execution_generation=? AND status='running'",
                status, now, id, task, attempt, generation);
        if (changed != 1) return false;
        database.update("UPDATE benchmark_run_tasks SET status=?,agent_response=?,verification_result=?,passed=?,score=?,cost_usd=?,elapsed_ms=?,finished_at=? WHERE run_id=? AND task_id=? AND attempt=?",
                status, response == null ? null : json.write(response), verification == null ? null : json.write(verification), passed, score, cost, elapsed, now, id, task, attempt);
        recount(id);
        return true;
    }

    public void recount(String id) {
        List<RunTask> tasks = findTasks(id);
        int completed = 0, passed = 0, failed = 0;
        for (RunTask task : tasks) {
            if (List.of("passed", "failed", "invalid", "timeout").contains(task.status())) {
                completed++;
                if (Boolean.TRUE.equals(task.passed())) passed++; else failed++;
            }
        }
        database.update("UPDATE benchmark_runs SET completed_tasks=?,passed_tasks=?,failed_tasks=? WHERE run_id=?", completed, passed, failed, id);
    }

    public boolean finish(String id, long generation, String status, String errorCode, String errorMessage, String reportId) {
        return database.update("UPDATE benchmark_runs SET status=?,error_code=?,error_message=?,report_id=?,finished_at=?,claim_owner=NULL,lease_until=NULL WHERE run_id=? AND execution_generation=? AND status='running' AND cancel_requested=FALSE",
                status, errorCode, errorMessage, reportId, java.time.Instant.now().toString(), id, generation) == 1;
    }

    @Transactional
    public void appendLog(String id, String level, String message) {
        if (find(id, true).isEmpty()) return;
        Long seq = database.queryForObject("SELECT next_log_sequence FROM benchmark_runs WHERE run_id=?", Long.class, id);
        database.update("UPDATE benchmark_runs SET next_log_sequence=next_log_sequence+1 WHERE run_id=?", id);
        insertLog(new RunLog(id, seq == null ? 1 : seq, level, message, java.time.Instant.now().toString()));
    }

    public void insertTask(String runId, String taskId, int ordinal) {
        database.update("INSERT INTO benchmark_run_tasks (run_id, task_id, ordinal, status, attempt) VALUES (?, ?, ?, 'queued', 1)",
                runId, taskId, ordinal);
    }

    public List<RunTask> findTasks(String runId) {
        return database.query("SELECT * FROM benchmark_run_tasks WHERE run_id=? ORDER BY ordinal", taskMapper, runId);
    }

    public void updateTask(String runId, String taskId, String status, ObjectNode agentResponse,
                           ObjectNode verification, Boolean passed, Double score, Double costUsd, Long elapsedMs,
                           String startedAt, String finishedAt) {
        database.update("""
                UPDATE benchmark_run_tasks SET status=?, agent_response=?, verification_result=?, passed=?, score=?,
                cost_usd=?, elapsed_ms=?, started_at=?, finished_at=? WHERE run_id=? AND task_id=?
                """, status, agentResponse == null ? null : json.write(agentResponse),
                verification == null ? null : json.write(verification), passed, score, costUsd, elapsedMs,
                startedAt, finishedAt, runId, taskId);
    }

    public long nextLogSequence(String runId) {
        Long value = database.queryForObject("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM benchmark_run_logs WHERE run_id=?",
                Long.class, runId);
        return value == null ? 1 : value;
    }

    public void insertLog(RunLog log) {
        database.update("INSERT INTO benchmark_run_logs (run_id, sequence_no, level_name, message, created_at) VALUES (?, ?, ?, ?, ?)",
                log.runId(), log.sequence(), log.level(), log.message(), log.createdAt());
    }

    public List<RunLog> logs(String runId, long after, int limit) {
        return database.query("SELECT * FROM benchmark_run_logs WHERE run_id=? AND sequence_no>? ORDER BY sequence_no LIMIT ?",
                (row, i) -> new RunLog(row.getString("run_id"), row.getLong("sequence_no"),
                        row.getString("level_name"), row.getString("message"), row.getString("created_at")),
                runId, after, limit);
    }

    private ObjectNode nullableJson(String value) {
        return value == null ? null : json.read(value);
    }
}

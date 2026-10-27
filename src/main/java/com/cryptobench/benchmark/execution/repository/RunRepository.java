package com.cryptobench.benchmark.execution.repository;

import com.cryptobench.benchmark.common.JsonDocuments;
import com.cryptobench.benchmark.execution.model.Run;
import com.cryptobench.benchmark.execution.model.RunLog;
import com.cryptobench.benchmark.execution.model.RunTask;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RunRepository {
    private final JdbcTemplate jdbc;
    private final JsonDocuments json;
    private final RowMapper<Run> runMapper;
    private final RowMapper<RunTask> taskMapper;

    public RunRepository(JdbcTemplate jdbc, JsonDocuments json) {
        this.jdbc = jdbc;
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
        jdbc.update("""
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
        return jdbc.query("SELECT * FROM benchmark_runs WHERE run_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                runMapper, id).stream().findFirst();
    }

    public List<Run> findByOwner(String ownerId) {
        return jdbc.query("SELECT * FROM benchmark_runs WHERE owner_id = ? ORDER BY created_at DESC", runMapper, ownerId);
    }

    public List<Run> findByOwner(String ownerId, List<String> statuses, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        StringBuilder sql = new StringBuilder("SELECT * FROM benchmark_runs WHERE owner_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(ownerId);
        if (statuses != null && !statuses.isEmpty()) {
            List<String> allowed = statuses.stream().filter(List.of("queued", "running", "completed", "failed", "timeout", "cancelled")::contains).toList();
            if (!allowed.isEmpty()) {
                sql.append(" AND status IN (").append("?,".repeat(allowed.size()).replaceAll(",$", "")).append(")");
                args.addAll(allowed);
            }
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(safeSize);
        args.add(safePage * safeSize);
        return jdbc.query(sql.toString(), runMapper, args.toArray());
    }

    public void updateStatus(String id, String status, int completed, int passed, int failed,
                             String errorCode, String errorMessage, String reportId,
                             boolean cancelRequested, String startedAt, String finishedAt) {
        jdbc.update("""
                UPDATE benchmark_runs SET status=?, completed_tasks=?, passed_tasks=?, failed_tasks=?,
                error_code=?, error_message=?, report_id=?, cancel_requested=?, started_at=?, finished_at=?
                WHERE run_id=?
                """, status, completed, passed, failed, errorCode, errorMessage, reportId, cancelRequested,
                startedAt, finishedAt, id);
    }

    /** Claim a queued run exactly once; a queued cancellation must win the worker race. */
    public boolean markRunning(String id, String startedAt) {
        return jdbc.update("""
                UPDATE benchmark_runs SET status='running', started_at=?
                WHERE run_id=? AND status='queued' AND cancel_requested=FALSE
                """, startedAt, id) == 1;
    }

    public void requestCancel(String id) {
        jdbc.update("UPDATE benchmark_runs SET cancel_requested=TRUE WHERE run_id=? AND status IN ('queued','running')", id);
    }

    public boolean cancelRequested(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT cancel_requested FROM benchmark_runs WHERE run_id=?",
                Boolean.class, id));
    }

    public int markInterruptedRuns() {
        return jdbc.update("UPDATE benchmark_runs SET status='failed', error_code='worker_restarted', error_message='应用重启时运行被中断', finished_at=? WHERE status IN ('queued','running')",
                java.time.Instant.now().toString());
    }

    /** Re-queues interrupted work and clears in-flight task results before a new worker claims it. */
    public List<Run> recoverInterruptedRuns() {
        List<Run> interrupted = jdbc.query("SELECT * FROM benchmark_runs WHERE status IN ('queued','running') AND cancel_requested=FALSE",
                runMapper);
        if (interrupted.isEmpty()) return interrupted;
        jdbc.update("UPDATE benchmark_runs SET status='queued', error_code='worker_restarted', error_message='应用重启后已重新排队', started_at=NULL, finished_at=NULL WHERE status IN ('queued','running') AND cancel_requested=FALSE");
        interrupted.forEach(run -> jdbc.update("UPDATE benchmark_run_tasks SET status='queued', agent_response=NULL, verification_result=NULL, passed=NULL, score=NULL, cost_usd=NULL, elapsed_ms=NULL, started_at=NULL, finished_at=NULL WHERE run_id=? AND status NOT IN ('queued')", run.id()));
        return interrupted;
    }

    public void insertTask(String runId, String taskId, int ordinal) {
        jdbc.update("INSERT INTO benchmark_run_tasks (run_id, task_id, ordinal, status, attempt) VALUES (?, ?, ?, 'queued', 1)",
                runId, taskId, ordinal);
    }

    public List<RunTask> findTasks(String runId) {
        return jdbc.query("SELECT * FROM benchmark_run_tasks WHERE run_id=? ORDER BY ordinal", taskMapper, runId);
    }

    public void updateTask(String runId, String taskId, String status, ObjectNode agentResponse,
                           ObjectNode verification, Boolean passed, Double score, Double costUsd, Long elapsedMs,
                           String startedAt, String finishedAt) {
        jdbc.update("""
                UPDATE benchmark_run_tasks SET status=?, agent_response=?, verification_result=?, passed=?, score=?,
                cost_usd=?, elapsed_ms=?, started_at=?, finished_at=? WHERE run_id=? AND task_id=?
                """, status, agentResponse == null ? null : json.write(agentResponse),
                verification == null ? null : json.write(verification), passed, score, costUsd, elapsedMs,
                startedAt, finishedAt, runId, taskId);
    }

    public long nextLogSequence(String runId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM benchmark_run_logs WHERE run_id=?",
                Long.class, runId);
        return value == null ? 1 : value;
    }

    public void insertLog(RunLog log) {
        jdbc.update("INSERT INTO benchmark_run_logs (run_id, sequence_no, level_name, message, created_at) VALUES (?, ?, ?, ?, ?)",
                log.runId(), log.sequence(), log.level(), log.message(), log.createdAt());
    }

    public List<RunLog> logs(String runId, long after, int limit) {
        return jdbc.query("SELECT * FROM benchmark_run_logs WHERE run_id=? AND sequence_no>? ORDER BY sequence_no LIMIT ?",
                (row, i) -> new RunLog(row.getString("run_id"), row.getLong("sequence_no"),
                        row.getString("level_name"), row.getString("message"), row.getString("created_at")),
                runId, after, limit);
    }

    private ObjectNode nullableJson(String value) {
        return value == null ? null : json.read(value);
    }
}

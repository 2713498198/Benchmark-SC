ALTER TABLE benchmark_runs ADD COLUMN execution_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE benchmark_runs ADD COLUMN claim_owner VARCHAR(128);
ALTER TABLE benchmark_runs ADD COLUMN lease_until BIGINT;
ALTER TABLE benchmark_runs ADD COLUMN next_log_sequence BIGINT NOT NULL DEFAULT 1;
UPDATE benchmark_runs SET next_log_sequence = 1 + COALESCE((SELECT MAX(sequence_no) FROM benchmark_run_logs WHERE benchmark_run_logs.run_id = benchmark_runs.run_id), 0);
CREATE INDEX idx_run_queue_lease ON benchmark_runs(status, lease_until, created_at);

CREATE TABLE benchmark_run_task_attempts (
    run_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    attempt INT NOT NULL,
    execution_generation BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at VARCHAR(40) NOT NULL,
    finished_at VARCHAR(40),
    PRIMARY KEY (run_id, task_id, attempt),
    CONSTRAINT fk_attempt_task FOREIGN KEY (run_id, task_id)
        REFERENCES benchmark_run_tasks(run_id, task_id) ON DELETE CASCADE
);

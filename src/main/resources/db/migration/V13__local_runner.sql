CREATE TABLE benchmark_runners (
    id VARCHAR(80) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    runner_version VARCHAR(80),
    status VARCHAR(20) NOT NULL,
    agents_payload LONGTEXT NOT NULL,
    accepting_tasks BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    last_seen BIGINT NOT NULL,
    revoked_at BIGINT,
    CONSTRAINT fk_runner_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
);
CREATE INDEX idx_runner_owner ON benchmark_runners(owner_id, created_at);
CREATE INDEX idx_runner_heartbeat ON benchmark_runners(status, last_seen);

CREATE TABLE benchmark_runner_pairings (
    id VARCHAR(80) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at BIGINT NOT NULL,
    used_at BIGINT,
    CONSTRAINT fk_runner_pairing_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
);
CREATE INDEX idx_runner_pairing_expiry ON benchmark_runner_pairings(expires_at);

CREATE TABLE benchmark_runner_jobs (
    id VARCHAR(80) PRIMARY KEY,
    runner_id VARCHAR(80) NOT NULL,
    owner_id VARCHAR(80) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    generation BIGINT NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    request_payload LONGTEXT NOT NULL,
    result_payload LONGTEXT,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    created_at BIGINT NOT NULL,
    lease_until BIGINT,
    finished_at BIGINT,
    CONSTRAINT fk_runner_job_runner FOREIGN KEY (runner_id) REFERENCES benchmark_runners(id),
    CONSTRAINT fk_runner_job_run FOREIGN KEY (run_id) REFERENCES benchmark_runs(run_id) ON DELETE CASCADE,
    CONSTRAINT uq_runner_task_attempt UNIQUE(run_id, generation, task_id, attempt)
);
CREATE INDEX idx_runner_job_queue ON benchmark_runner_jobs(runner_id, status, created_at);

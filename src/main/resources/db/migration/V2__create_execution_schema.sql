CREATE TABLE benchmark_runs (
    run_id VARCHAR(128) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    execution_source VARCHAR(20) NOT NULL,
    dataset_id VARCHAR(128) NOT NULL,
    dataset_version VARCHAR(40) NOT NULL,
    budget VARCHAR(30) NOT NULL,
    task_scope VARCHAR(20) NOT NULL,
    model_id VARCHAR(120) NOT NULL,
    agent_version VARCHAR(120),
    seed BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_tasks INT NOT NULL,
    completed_tasks INT NOT NULL DEFAULT 0,
    passed_tasks INT NOT NULL DEFAULT 0,
    failed_tasks INT NOT NULL DEFAULT 0,
    config_payload LONGTEXT NOT NULL,
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    report_id VARCHAR(128),
    retry_of VARCHAR(128),
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at VARCHAR(40) NOT NULL,
    started_at VARCHAR(40),
    finished_at VARCHAR(40),
    CONSTRAINT fk_run_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
);
CREATE INDEX idx_run_owner_created ON benchmark_runs(owner_id, created_at);
CREATE INDEX idx_run_status ON benchmark_runs(status);

CREATE TABLE benchmark_run_tasks (
    run_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    ordinal INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    agent_response LONGTEXT,
    verification_result LONGTEXT,
    passed BOOLEAN,
    score DOUBLE,
    cost_usd DOUBLE,
    elapsed_ms BIGINT,
    started_at VARCHAR(40),
    finished_at VARCHAR(40),
    PRIMARY KEY (run_id, task_id),
    CONSTRAINT fk_run_task_run FOREIGN KEY (run_id) REFERENCES benchmark_runs(run_id) ON DELETE CASCADE
);
CREATE INDEX idx_run_task_status ON benchmark_run_tasks(run_id, status);

CREATE TABLE benchmark_run_logs (
    run_id VARCHAR(128) NOT NULL,
    sequence_no BIGINT NOT NULL,
    level_name VARCHAR(12) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (run_id, sequence_no),
    CONSTRAINT fk_run_log_run FOREIGN KEY (run_id) REFERENCES benchmark_runs(run_id) ON DELETE CASCADE
);

CREATE TABLE benchmark_password_reset_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL,
    expires_at BIGINT NOT NULL,
    used_at BIGINT,
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT fk_reset_user FOREIGN KEY (user_id) REFERENCES benchmark_users(id) ON DELETE CASCADE
);
CREATE INDEX idx_reset_user ON benchmark_password_reset_tokens(user_id);
CREATE INDEX idx_reset_expiration ON benchmark_password_reset_tokens(expires_at);

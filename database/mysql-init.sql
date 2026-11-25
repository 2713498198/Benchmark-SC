-- CryptoBench MySQL 8 初始化脚本
-- 仅用于全新的空数据库。脚本使用 IF NOT EXISTS，不会删除已有表。
-- 这些表等价于 Flyway V19，并写入 BASELINE；后续版本仍由应用升级。

CREATE DATABASE IF NOT EXISTS cryptobench
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE cryptobench;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS benchmark_users (
    id VARCHAR(80) PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(30) NOT NULL,
    user_role VARCHAR(20) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    password_salt VARCHAR(64),
    created_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_user_role ON benchmark_users(user_role);

CREATE TABLE IF NOT EXISTS benchmark_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL,
    expires_at BIGINT NOT NULL,
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES benchmark_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_session_expiration ON benchmark_sessions(expires_at);

CREATE TABLE IF NOT EXISTS benchmark_connections (
    id VARCHAR(80) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    endpoint_url VARCHAR(2048) NOT NULL,
    model_id VARCHAR(120) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT fk_connection_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_connection_owner ON benchmark_connections(owner_id);

CREATE TABLE IF NOT EXISTS benchmark_reports (
    id VARCHAR(128) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    payload LONGTEXT NOT NULL,
    CONSTRAINT fk_report_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_report_owner ON benchmark_reports(owner_id);
CREATE INDEX idx_report_published ON benchmark_reports(published);

CREATE TABLE IF NOT EXISTS benchmark_datasets (
    id VARCHAR(128) NOT NULL,
    dataset_version VARCHAR(40) NOT NULL,
    owner_id VARCHAR(80) NOT NULL,
    payload LONGTEXT NOT NULL,
    schema_version VARCHAR(80) NOT NULL DEFAULT 'legacy',
    execution_status VARCHAR(30) NOT NULL DEFAULT 'legacy_unbound',
    evaluator_version VARCHAR(120),
    catalog_sha256 VARCHAR(64),
    published_at VARCHAR(40),
    PRIMARY KEY (id, dataset_version),
    CONSTRAINT fk_dataset_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS benchmark_dataset_packages (
    dataset_id VARCHAR(128) NOT NULL,
    dataset_version VARCHAR(40) NOT NULL,
    private_payload LONGTEXT NOT NULL,
    package_sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (dataset_id, dataset_version),
    CONSTRAINT fk_dataset_package FOREIGN KEY (dataset_id, dataset_version)
        REFERENCES benchmark_datasets(id, dataset_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS benchmark_price_tables (
    version VARCHAR(120) PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_by VARCHAR(80),
    created_at VARCHAR(40) NOT NULL,
    activated_at VARCHAR(40),
    revision BIGINT NOT NULL DEFAULT 0,
    locked BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_price_table_creator FOREIGN KEY (created_by) REFERENCES benchmark_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_price_table_status ON benchmark_price_tables(status);

CREATE TABLE IF NOT EXISTS benchmark_model_prices (
    price_version VARCHAR(120) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    input_per_million_tokens DECIMAL(20, 8) NOT NULL,
    output_per_million_tokens DECIMAL(20, 8) NOT NULL,
    currency VARCHAR(12) NOT NULL DEFAULT 'USD',
    PRIMARY KEY (price_version, model_id),
    CONSTRAINT fk_model_price_table FOREIGN KEY (price_version)
        REFERENCES benchmark_price_tables(version) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS benchmark_pricing_state (
    singleton_id INTEGER PRIMARY KEY,
    active_version VARCHAR(120),
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_pricing_singleton CHECK (singleton_id = 1),
    CONSTRAINT fk_pricing_active_version FOREIGN KEY (active_version)
        REFERENCES benchmark_price_tables(version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO benchmark_pricing_state (singleton_id, active_version, revision)
SELECT 1, NULL, 0
WHERE NOT EXISTS (SELECT 1 FROM benchmark_pricing_state WHERE singleton_id = 1);

CREATE TABLE IF NOT EXISTS benchmark_mail_guard (
    id INT PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO benchmark_mail_guard (id)
SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM benchmark_mail_guard WHERE id = 1);

CREATE TABLE IF NOT EXISTS benchmark_mail_messages (
    id VARCHAR(80) PRIMARY KEY,
    purpose VARCHAR(30) NOT NULL,
    reference_id VARCHAR(128) NOT NULL,
    owner_id VARCHAR(80),
    generation INT NOT NULL,
    transport VARCHAR(20) NOT NULL,
    state VARCHAR(30) NOT NULL,
    ciphertext LONGTEXT,
    nonce VARCHAR(40),
    key_id VARCHAR(64),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL,
    lease_until BIGINT,
    claim_id VARCHAR(80),
    error_code VARCHAR(80),
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    completed_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_mail_due ON benchmark_mail_messages(state, next_attempt_at);
CREATE INDEX idx_mail_reference ON benchmark_mail_messages(purpose, reference_id);
CREATE INDEX idx_mail_owner ON benchmark_mail_messages(purpose, owner_id);

CREATE TABLE IF NOT EXISTS benchmark_runs (
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
    execution_generation BIGINT NOT NULL DEFAULT 0,
    claim_owner VARCHAR(128),
    lease_until BIGINT,
    next_log_sequence BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT fk_run_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_run_owner_created ON benchmark_runs(owner_id, created_at);
CREATE INDEX idx_run_status ON benchmark_runs(status);
CREATE INDEX idx_run_queue_lease ON benchmark_runs(status, lease_until, created_at);

CREATE TABLE IF NOT EXISTS benchmark_run_tasks (
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
    CONSTRAINT fk_run_task_run FOREIGN KEY (run_id)
        REFERENCES benchmark_runs(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_run_task_status ON benchmark_run_tasks(run_id, status);

CREATE TABLE IF NOT EXISTS benchmark_run_logs (
    run_id VARCHAR(128) NOT NULL,
    sequence_no BIGINT NOT NULL,
    level_name VARCHAR(12) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (run_id, sequence_no),
    CONSTRAINT fk_run_log_run FOREIGN KEY (run_id)
        REFERENCES benchmark_runs(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS benchmark_run_task_attempts (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS benchmark_run_quota_lock (
    singleton_id INT PRIMARY KEY,
    revision BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO benchmark_run_quota_lock (singleton_id, revision)
SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM benchmark_run_quota_lock WHERE singleton_id = 1);

CREATE TABLE IF NOT EXISTS benchmark_password_reset_challenges (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(80),
    email_key VARCHAR(64) NOT NULL,
    code_digest VARCHAR(64),
    code_key_id VARCHAR(32),
    status VARCHAR(16) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    verified_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_reset_challenge_email
    ON benchmark_password_reset_challenges(email_key, status);
CREATE INDEX idx_reset_challenge_user
    ON benchmark_password_reset_challenges(user_id, status);
CREATE INDEX idx_reset_challenge_expiry
    ON benchmark_password_reset_challenges(expires_at);

CREATE TABLE IF NOT EXISTS benchmark_password_reset_grants (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL,
    challenge_id VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    used_at BIGINT,
    revoked_at BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_reset_grant_user ON benchmark_password_reset_grants(user_id);
CREATE INDEX idx_reset_grant_expiry ON benchmark_password_reset_grants(expires_at);

CREATE TABLE IF NOT EXISTS benchmark_password_reset_rate_events (
    id VARCHAR(36) PRIMARY KEY,
    event_scope VARCHAR(24) NOT NULL,
    subject_hash VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_reset_rate_window
    ON benchmark_password_reset_rate_events(event_scope, subject_hash, created_at);
CREATE INDEX idx_reset_rate_expiry
    ON benchmark_password_reset_rate_events(created_at);

CREATE TABLE IF NOT EXISTS benchmark_runners (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_runner_owner ON benchmark_runners(owner_id, created_at);
CREATE INDEX idx_runner_heartbeat ON benchmark_runners(status, last_seen);

CREATE TABLE IF NOT EXISTS benchmark_runner_pairings (
    id VARCHAR(80) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at BIGINT NOT NULL,
    used_at BIGINT,
    CONSTRAINT fk_runner_pairing_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_runner_pairing_expiry ON benchmark_runner_pairings(expires_at);

CREATE TABLE IF NOT EXISTS benchmark_runner_jobs (
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
    CONSTRAINT uq_runner_task_attempt UNIQUE (run_id, generation, task_id, attempt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_runner_job_queue
    ON benchmark_runner_jobs(runner_id, status, created_at);

CREATE TABLE IF NOT EXISTS benchmark_auth_rate_events (
    id VARCHAR(36) PRIMARY KEY,
    event_scope VARCHAR(24) NOT NULL,
    subject_hash VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_rate_window
    ON benchmark_auth_rate_events(event_scope, subject_hash, created_at);
CREATE INDEX idx_auth_rate_expiry ON benchmark_auth_rate_events(created_at);

CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INT NOT NULL,
    version VARCHAR(50),
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_time INT NOT NULL,
    success BOOLEAN NOT NULL,
    PRIMARY KEY (installed_rank),
    INDEX flyway_schema_history_s_idx (success)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO flyway_schema_history (
    installed_rank, version, description, type, script, checksum,
    installed_by, execution_time, success
)
SELECT 1, '19', 'CryptoBench MySQL baseline', 'BASELINE',
       'mysql-init.sql', NULL, 'cryptobench', 0, TRUE
WHERE NOT EXISTS (SELECT 1 FROM flyway_schema_history);

-- 应用账号请使用独立密码，不要直接使用 MySQL root。
-- 取消下面三行注释，替换密码后执行。Flyway 后续升级需要建表和改表权限。
-- CREATE USER IF NOT EXISTS 'cryptobench'@'localhost' IDENTIFIED BY '请替换为强密码';
-- GRANT ALL PRIVILEGES ON cryptobench.* TO 'cryptobench'@'localhost';
-- FLUSH PRIVILEGES;

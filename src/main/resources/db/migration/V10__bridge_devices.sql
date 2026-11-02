CREATE TABLE benchmark_bridge_pairings (
    code_hash VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    device_name VARCHAR(80) NOT NULL,
    expires_at BIGINT NOT NULL,
    used_at BIGINT,
    CONSTRAINT fk_bridge_pairing_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
);
CREATE TABLE benchmark_bridge_devices (
    id VARCHAR(80) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    device_name VARCHAR(80) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    last_seen BIGINT NOT NULL,
    revoked_at BIGINT,
    agents_payload LONGTEXT NOT NULL,
    accepting_tasks BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_bridge_device_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
);
CREATE INDEX idx_bridge_owner ON benchmark_bridge_devices(owner_id);
CREATE TABLE benchmark_bridge_jobs (
    id VARCHAR(80) PRIMARY KEY,
    bridge_id VARCHAR(80) NOT NULL,
    owner_id VARCHAR(80) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    generation BIGINT NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    request_payload LONGTEXT NOT NULL,
    lease_hash VARCHAR(64),
    lease_until BIGINT,
    result_payload LONGTEXT,
    result_hash VARCHAR(64),
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    created_at BIGINT NOT NULL,
    finished_at BIGINT,
    CONSTRAINT fk_bridge_job_device FOREIGN KEY (bridge_id) REFERENCES benchmark_bridge_devices(id),
    CONSTRAINT uq_bridge_task_attempt UNIQUE(run_id,generation,task_id,attempt)
);
CREATE INDEX idx_bridge_job_queue ON benchmark_bridge_jobs(bridge_id,status,created_at);

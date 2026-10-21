CREATE TABLE benchmark_users (
    id VARCHAR(80) PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(30) NOT NULL,
    user_role VARCHAR(20) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    password_salt VARCHAR(64),
    created_at VARCHAR(40) NOT NULL
);

CREATE TABLE benchmark_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL,
    expires_at BIGINT NOT NULL,
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES benchmark_users(id)
);
CREATE INDEX idx_session_expiration ON benchmark_sessions(expires_at);

CREATE TABLE benchmark_connections (
    id VARCHAR(80) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    endpoint_url VARCHAR(2048) NOT NULL,
    model_id VARCHAR(120) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT fk_connection_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
);
CREATE INDEX idx_connection_owner ON benchmark_connections(owner_id);

CREATE TABLE benchmark_reports (
    id VARCHAR(128) PRIMARY KEY,
    owner_id VARCHAR(80) NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    payload LONGTEXT NOT NULL,
    CONSTRAINT fk_report_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
);
CREATE INDEX idx_report_owner ON benchmark_reports(owner_id);
CREATE INDEX idx_report_published ON benchmark_reports(published);

CREATE TABLE benchmark_datasets (
    id VARCHAR(128) NOT NULL,
    dataset_version VARCHAR(40) NOT NULL,
    owner_id VARCHAR(80) NOT NULL,
    payload LONGTEXT NOT NULL,
    PRIMARY KEY (id, dataset_version),
    CONSTRAINT fk_dataset_owner FOREIGN KEY (owner_id) REFERENCES benchmark_users(id)
);

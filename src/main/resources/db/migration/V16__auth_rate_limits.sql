CREATE TABLE benchmark_auth_rate_events (
    id VARCHAR(36) PRIMARY KEY,
    event_scope VARCHAR(24) NOT NULL,
    subject_hash VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_auth_rate_window ON benchmark_auth_rate_events(event_scope, subject_hash, created_at);
CREATE INDEX idx_auth_rate_expiry ON benchmark_auth_rate_events(created_at);

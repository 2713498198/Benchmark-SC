CREATE TABLE benchmark_password_reset_challenges (
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
);
CREATE INDEX idx_reset_challenge_email ON benchmark_password_reset_challenges(email_key, status);
CREATE INDEX idx_reset_challenge_user ON benchmark_password_reset_challenges(user_id, status);
CREATE INDEX idx_reset_challenge_expiry ON benchmark_password_reset_challenges(expires_at);

CREATE TABLE benchmark_password_reset_grants (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(80) NOT NULL,
    challenge_id VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    used_at BIGINT,
    revoked_at BIGINT
);
CREATE INDEX idx_reset_grant_user ON benchmark_password_reset_grants(user_id);
CREATE INDEX idx_reset_grant_expiry ON benchmark_password_reset_grants(expires_at);

CREATE TABLE benchmark_password_reset_rate_events (
    id VARCHAR(36) PRIMARY KEY,
    event_scope VARCHAR(24) NOT NULL,
    subject_hash VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_reset_rate_window ON benchmark_password_reset_rate_events(event_scope, subject_hash, created_at);
CREATE INDEX idx_reset_rate_expiry ON benchmark_password_reset_rate_events(created_at);

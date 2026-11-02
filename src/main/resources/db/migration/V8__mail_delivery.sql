ALTER TABLE benchmark_admin_invitations ADD COLUMN revoked_at BIGINT;
ALTER TABLE benchmark_admin_invitations ADD COLUMN revoked_by VARCHAR(80);
ALTER TABLE benchmark_admin_invitations ADD COLUMN generation INT NOT NULL DEFAULT 1;
ALTER TABLE benchmark_admin_invitations ADD COLUMN last_message_id VARCHAR(80);

CREATE TABLE benchmark_mail_guard (id INT PRIMARY KEY);
INSERT INTO benchmark_mail_guard(id) VALUES (1);

CREATE TABLE benchmark_mail_messages (
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
);
CREATE INDEX idx_mail_due ON benchmark_mail_messages(state, next_attempt_at);
CREATE INDEX idx_mail_reference ON benchmark_mail_messages(purpose, reference_id);
CREATE INDEX idx_mail_owner ON benchmark_mail_messages(purpose, owner_id);

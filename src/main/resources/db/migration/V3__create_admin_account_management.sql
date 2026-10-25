ALTER TABLE benchmark_users ADD COLUMN account_status VARCHAR(20) NOT NULL DEFAULT 'active';
ALTER TABLE benchmark_users ADD COLUMN disabled_at VARCHAR(40);
ALTER TABLE benchmark_users ADD COLUMN disabled_by VARCHAR(80);
CREATE INDEX idx_user_role_status ON benchmark_users(user_role, account_status);

CREATE TABLE benchmark_admin_invitations (
    id VARCHAR(80) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    display_name VARCHAR(30) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    invited_by VARCHAR(80) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    expires_at BIGINT NOT NULL,
    used_at BIGINT,
    CONSTRAINT fk_admin_inviter FOREIGN KEY (invited_by) REFERENCES benchmark_users(id)
);
CREATE INDEX idx_admin_invite_email ON benchmark_admin_invitations(email);

CREATE TABLE benchmark_admin_audits (
    id VARCHAR(80) PRIMARY KEY,
    actor_user_id VARCHAR(80),
    target_user_id VARCHAR(80),
    action VARCHAR(60) NOT NULL,
    metadata LONGTEXT,
    result VARCHAR(20) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT fk_admin_audit_actor FOREIGN KEY (actor_user_id) REFERENCES benchmark_users(id)
);
CREATE INDEX idx_admin_audit_created ON benchmark_admin_audits(created_at);
CREATE INDEX idx_admin_audit_target ON benchmark_admin_audits(target_user_id);

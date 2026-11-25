-- Administrator bootstrap, invitations and account auditing are no longer
-- product features. Keep the user profile table and the session table used by
-- email/password authentication.
DELETE FROM benchmark_mail_messages WHERE purpose = 'admin_invite';

DROP TABLE benchmark_admin_invitations;
DROP TABLE benchmark_admin_audits;

DROP INDEX idx_user_role_status ON benchmark_users;
ALTER TABLE benchmark_users DROP COLUMN account_status;
ALTER TABLE benchmark_users DROP COLUMN disabled_at;
ALTER TABLE benchmark_users DROP COLUMN disabled_by;
CREATE INDEX idx_user_role ON benchmark_users(user_role);

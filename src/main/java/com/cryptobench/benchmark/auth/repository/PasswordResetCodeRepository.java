package com.cryptobench.benchmark.auth.repository;

import com.cryptobench.benchmark.persistence.MybatisDatabase;
import com.cryptobench.benchmark.persistence.MybatisRowMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All mutations run under the existing database mail guard, shared by legacy reset operations. */
@Repository
public class PasswordResetCodeRepository {
    private final MybatisDatabase database;
    private static final MybatisRowMapper<Challenge> CHALLENGE = (r, i) -> new Challenge(r.getString("id"), r.getString("user_id"),
            r.getString("email_key"), r.getString("code_digest"), r.getString("code_key_id"), r.getString("status"),
            r.getInt("failed_attempts"), r.getLong("created_at"), r.getLong("expires_at"));
    private static final MybatisRowMapper<Grant> GRANT = (r, i) -> new Grant(r.getString("token_hash"), r.getString("user_id"),
            r.getString("challenge_id"), r.getLong("expires_at"));
    @Autowired
    public PasswordResetCodeRepository(MybatisDatabase database) { this.database = database; }
    /** Compatibility constructor for direct repository tests and legacy integrations. */
    public PasswordResetCodeRepository(JdbcTemplate jdbc) { this(new MybatisDatabase(jdbc)); }
    public void insertChallenge(String id, String user, String emailKey, String digest, String keyId, long now, long expires) {
        database.update("INSERT INTO benchmark_password_reset_challenges(id,user_id,email_key,code_digest,code_key_id,status,created_at,expires_at) VALUES(?,?,?,?,?,'active',?,?)",
                id, user, emailKey, digest, keyId, now, expires);
    }
    public Optional<Challenge> challenge(String id) {
        return database.query("SELECT * FROM benchmark_password_reset_challenges WHERE id=?", CHALLENGE, id).stream().findFirst();
    }
    public void invalidateEmail(String emailKey) {
        database.update("UPDATE benchmark_password_reset_challenges SET status='revoked',code_digest=NULL,code_key_id=NULL WHERE email_key=? AND status='active'", emailKey);
    }
    public void invalidateUser(String user, long now) {
        database.update("UPDATE benchmark_password_reset_challenges SET status='revoked',code_digest=NULL,code_key_id=NULL WHERE user_id=? AND status IN ('active','verified')", user);
        database.update("UPDATE benchmark_password_reset_grants SET revoked_at=? WHERE user_id=? AND used_at IS NULL AND revoked_at IS NULL", now, user);
    }
    public void failChallenge(String id, boolean lock) {
        database.update("UPDATE benchmark_password_reset_challenges SET failed_attempts=failed_attempts+1,status=?,code_digest=CASE WHEN ? THEN NULL ELSE code_digest END,code_key_id=CASE WHEN ? THEN NULL ELSE code_key_id END WHERE id=? AND status='active'",
                lock ? "locked" : "active", lock, lock, id);
    }
    public int verifyChallenge(String id, long now) {
        return database.update("UPDATE benchmark_password_reset_challenges SET status='verified',verified_at=?,code_digest=NULL,code_key_id=NULL WHERE id=? AND status='active' AND expires_at>?", now, id, now);
    }
    public void insertGrant(String hash, String user, String challenge, long now, long expires) {
        database.update("INSERT INTO benchmark_password_reset_grants(token_hash,user_id,challenge_id,created_at,expires_at) VALUES(?,?,?,?,?)", hash, user, challenge, now, expires);
    }
    public Optional<Grant> validGrant(String hash, long now) {
        return database.query("SELECT g.* FROM benchmark_password_reset_grants g JOIN benchmark_password_reset_challenges c ON c.id=g.challenge_id WHERE g.token_hash=? AND g.used_at IS NULL AND g.revoked_at IS NULL AND g.expires_at>? AND c.status='verified'", GRANT, hash, now).stream().findFirst();
    }
    public int consumeGrant(String hash, String challenge, long now) {
        return database.update("UPDATE benchmark_password_reset_grants SET used_at=? WHERE token_hash=? AND challenge_id=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>?", now, hash, challenge, now);
    }
    public void event(String scope, String key, long now) {
        database.update("INSERT INTO benchmark_password_reset_rate_events(id,event_scope,subject_hash,created_at) VALUES(?,?,?,?)", UUID.randomUUID().toString(), scope, key, now);
    }
    public long retryAfter(String scope, String key, long now, long window, int limit) {
        List<Long> times = database.query("SELECT created_at FROM benchmark_password_reset_rate_events WHERE event_scope=? AND subject_hash=? AND created_at>? ORDER BY created_at", (r, i) -> r.getLong("created_at"), scope, key, now - window);
        if (times.size() < limit) return 0;
        return Math.max(1, (times.get(times.size() - limit) + window - now + 999) / 1000);
    }
    public void cleanup(long now, long rateRetentionMillis) {
        database.update("DELETE FROM benchmark_password_reset_rate_events WHERE created_at<=?", now - Math.max(3_600_000L, rateRetentionMillis));
        database.update("UPDATE benchmark_password_reset_challenges SET status='expired',code_digest=NULL,code_key_id=NULL WHERE status='active' AND expires_at<=?", now);
        database.update("DELETE FROM benchmark_password_reset_grants WHERE expires_at<=?", now - 86_400_000L);
        database.update("DELETE FROM benchmark_password_reset_challenges WHERE expires_at<=? AND NOT EXISTS (SELECT 1 FROM benchmark_password_reset_grants g WHERE g.challenge_id=benchmark_password_reset_challenges.id)", now - 86_400_000L);
    }
    public record Challenge(String id, String userId, String emailKey, String digest, String keyId, String status, int failedAttempts, long createdAt, long expiresAt) {}
    public record Grant(String hash, String userId, String challengeId, long expiresAt) {}
}

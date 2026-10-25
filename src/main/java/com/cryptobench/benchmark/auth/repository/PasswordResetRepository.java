package com.cryptobench.benchmark.auth.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetRepository {
    public record Token(String hash, String userId, long expiresAt, Long usedAt) {}

    private final JdbcTemplate jdbc;

    public PasswordResetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String hash, String userId, long expiresAt, String createdAt) {
        jdbc.update("DELETE FROM benchmark_password_reset_tokens WHERE user_id=? OR expires_at<=?",
                userId, System.currentTimeMillis());
        jdbc.update("INSERT INTO benchmark_password_reset_tokens (token_hash,user_id,expires_at,created_at) VALUES (?,?,?,?)",
                hash, userId, expiresAt, createdAt);
    }

    public Optional<Token> find(String hash) {
        return jdbc.query("SELECT token_hash,user_id,expires_at,used_at FROM benchmark_password_reset_tokens WHERE token_hash=?",
                (row, i) -> new Token(row.getString("token_hash"), row.getString("user_id"),
                        row.getLong("expires_at"), (Long) row.getObject("used_at")), hash).stream().findFirst();
    }

    public void markUsed(String hash) {
        jdbc.update("UPDATE benchmark_password_reset_tokens SET used_at=? WHERE token_hash=? AND used_at IS NULL",
                System.currentTimeMillis(), hash);
    }

    /** 原子地占用令牌，避免两个并发请求同时重置密码。 */
    public int consume(String hash, long now) {
        return jdbc.update("UPDATE benchmark_password_reset_tokens SET used_at=? "
                        + "WHERE token_hash=? AND used_at IS NULL AND expires_at>?",
                now, hash, now);
    }
}

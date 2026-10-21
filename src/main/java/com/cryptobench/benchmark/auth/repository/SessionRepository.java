package com.cryptobench.benchmark.auth.repository;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public SessionRepository(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public Optional<BenchmarkUser> findUser(String tokenHash) {
        return jdbc.query("SELECT user_id FROM benchmark_sessions WHERE token_hash = ? AND expires_at > ?",
                (row, index) -> row.getString(1), tokenHash, System.currentTimeMillis())
                .stream().findFirst().flatMap(users::findById);
    }

    public void insert(String tokenHash, String userId, long expiresAt) {
        jdbc.update("DELETE FROM benchmark_sessions WHERE expires_at <= ?", System.currentTimeMillis());
        jdbc.update("INSERT INTO benchmark_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                tokenHash, userId, expiresAt);
    }

    public void delete(String tokenHash) {
        jdbc.update("DELETE FROM benchmark_sessions WHERE token_hash = ?", tokenHash);
    }
}

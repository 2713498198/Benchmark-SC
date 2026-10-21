package com.cryptobench.benchmark.auth.repository;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private static final RowMapper<BenchmarkUser> MAPPER = (row, index) -> new BenchmarkUser(
            row.getString("id"), row.getString("email"), row.getString("display_name"),
            row.getString("user_role"), row.getString("password_hash"),
            row.getString("password_salt"), row.getString("created_at"));
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BenchmarkUser> findByEmail(String email) {
        return jdbc.query("SELECT * FROM benchmark_users WHERE email = ?", MAPPER, email).stream().findFirst();
    }

    public Optional<BenchmarkUser> findById(String id) {
        return jdbc.query("SELECT * FROM benchmark_users WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public void insert(BenchmarkUser user) {
        jdbc.update("""
                INSERT INTO benchmark_users (id, email, display_name, user_role, password_hash, password_salt, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, user.id(), user.email(), user.name(), user.role(), user.passwordHash(),
                user.passwordSalt(), user.createdAt());
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_users", Long.class);
    }
}

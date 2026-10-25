package com.cryptobench.benchmark.auth.repository;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 数据库会话存储：只保存令牌摘要、用户 ID 和过期时间，不保存原始 Cookie 令牌。
 */
@Repository
public class SessionRepository {
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public SessionRepository(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /**
     * 仅匹配尚未过期的令牌摘要，再查询关联用户；不存在或已过期都视为未登录。
     */
    public Optional<BenchmarkUser> findUser(String tokenHash) {
        return jdbc.query("SELECT s.user_id FROM benchmark_sessions s JOIN benchmark_users u ON u.id=s.user_id "
                        + "WHERE s.token_hash = ? AND s.expires_at > ? AND u.account_status='active'",
                (row, index) -> row.getString(1), tokenHash, System.currentTimeMillis())
                .stream().findFirst().flatMap(users::findById);
    }

    /**
     * 创建会话前顺便清理过期记录；expiresAt 使用 Unix 毫秒时间戳。
     */
    public void insert(String tokenHash, String userId, long expiresAt) {
        jdbc.update("DELETE FROM benchmark_sessions WHERE expires_at <= ?", System.currentTimeMillis());
        jdbc.update("INSERT INTO benchmark_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                tokenHash, userId, expiresAt);
    }

    /**
     * 按令牌摘要撤销单个会话；记录已不存在时也可安全调用。
     */
    public void delete(String tokenHash) {
        jdbc.update("DELETE FROM benchmark_sessions WHERE token_hash = ?", tokenHash);
    }

    public void deleteByUser(String userId) {
        jdbc.update("DELETE FROM benchmark_sessions WHERE user_id = ?", userId);
    }

    public int deleteByUserAndCount(String userId) {
        return jdbc.update("DELETE FROM benchmark_sessions WHERE user_id = ?", userId);
    }
}

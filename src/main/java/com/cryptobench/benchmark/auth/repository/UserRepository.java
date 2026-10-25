package com.cryptobench.benchmark.auth.repository;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import java.util.Optional;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 用户表的数据访问层，使用 JdbcTemplate 参数绑定完成查询和插入。
 */
@Repository
public class UserRepository {
    // 统一映射数据库下划线列名与 Java 模型字段，避免各查询重复编写。
    private static final RowMapper<BenchmarkUser> MAPPER = (row, index) -> new BenchmarkUser(
            row.getString("id"), row.getString("email"), row.getString("display_name"),
            row.getString("user_role"), row.getString("password_hash"),
            row.getString("password_salt"), row.getString("created_at"),
            row.getString("account_status"), row.getString("disabled_at"), row.getString("disabled_by"));
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按已规范化的邮箱查找用户；未找到时返回空 Optional。
     */
    public Optional<BenchmarkUser> findByEmail(String email) {
        return jdbc.query("SELECT * FROM benchmark_users WHERE email = ?", MAPPER, email).stream().findFirst();
    }

    /**
     * 按主键查找用户，供会话恢复当前用户时使用。
     */
    public Optional<BenchmarkUser> findById(String id) {
        return jdbc.query("SELECT * FROM benchmark_users WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<BenchmarkUser> findPage(String query, String role, String status, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM benchmark_users WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(email) LIKE ? OR LOWER(display_name) LIKE ?)");
            String value = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            args.add(value); args.add(value);
        }
        if (role != null && !role.isBlank()) { sql.append(" AND user_role=?"); args.add(role); }
        if (status != null && !status.isBlank()) { sql.append(" AND account_status=?"); args.add(status); }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(size); args.add((long) page * size);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long countPage(String query, String role, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM benchmark_users WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(email) LIKE ? OR LOWER(display_name) LIKE ?)");
            String value = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            args.add(value); args.add(value);
        }
        if (role != null && !role.isBlank()) { sql.append(" AND user_role=?"); args.add(role); }
        if (status != null && !status.isBlank()) { sql.append(" AND account_status=?"); args.add(status); }
        return jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    }

    public boolean existsByEmail(String email) { return findByEmail(email).isPresent(); }

    public int countActiveAdmins() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_users WHERE user_role='admin' AND account_status='active'", Integer.class);
    }

    public int updateRole(String id, String role) {
        return jdbc.update("UPDATE benchmark_users SET user_role=? WHERE id=?", role, id);
    }

    public int updateStatus(String id, String status, String disabledAt, String disabledBy) {
        return jdbc.update("UPDATE benchmark_users SET account_status=?, disabled_at=?, disabled_by=? WHERE id=?",
                status, disabledAt, disabledBy, id);
    }

    /**
     * 保存用户资料与密码哈希；邮箱唯一约束用于兜底防止重复注册。
     */
    public void insert(BenchmarkUser user) {
        jdbc.update("""
                INSERT INTO benchmark_users (id, email, display_name, user_role, password_hash, password_salt, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, user.id(), user.email(), user.name(), user.role(), user.passwordHash(),
                user.passwordSalt(), user.createdAt());
    }

    /**
     * 统计已有用户，供旧数据导入器判断目标库是否允许导入。
     */
    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_users", Long.class);
    }

    public void updatePassword(String userId, String passwordHash) {
        jdbc.update("UPDATE benchmark_users SET password_hash=?, password_salt=NULL WHERE id=?", passwordHash, userId);
    }
}

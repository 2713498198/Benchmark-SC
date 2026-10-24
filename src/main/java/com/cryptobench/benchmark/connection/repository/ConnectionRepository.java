package com.cryptobench.benchmark.connection.repository;

import com.cryptobench.benchmark.connection.model.AgentConnection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 连接表的数据访问层；按归属查询和删除，所有业务参数通过 SQL 占位符绑定。
 */
@Repository
public class ConnectionRepository {
    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按用户列出连接，使用创建时间和 ID 保持确定的显示顺序。
     */
    public List<AgentConnection> findByOwner(String ownerId) {
        return jdbc.query("SELECT * FROM benchmark_connections WHERE owner_id = ? ORDER BY created_at, id",
                (row, index) -> new AgentConnection(row.getString("id"), row.getString("owner_id"),
                        row.getString("display_name"), row.getString("endpoint_url"),
                        row.getString("model_id"), row.getString("created_at")), ownerId);
    }

    /**
     * 写入服务层生成的连接模型，数据库外键约束归属用户必须存在。
     */
    public void insert(AgentConnection connection) {
        jdbc.update("""
                INSERT INTO benchmark_connections (id, owner_id, display_name, endpoint_url, model_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, connection.id(), connection.ownerId(), connection.name(), connection.url(),
                connection.model(), connection.createdAt());
    }

    /**
     * 同时匹配连接 ID 和归属用户；返回是否实际删除，防止跨用户操作。
     */
    public boolean delete(String id, String ownerId) {
        return jdbc.update("DELETE FROM benchmark_connections WHERE id = ? AND owner_id = ?", id, ownerId) > 0;
    }
}

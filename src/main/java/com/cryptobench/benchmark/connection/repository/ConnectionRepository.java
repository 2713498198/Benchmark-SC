package com.cryptobench.benchmark.connection.repository;

import com.cryptobench.benchmark.connection.model.AgentConnection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectionRepository {
    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AgentConnection> findByOwner(String ownerId) {
        return jdbc.query("SELECT * FROM benchmark_connections WHERE owner_id = ? ORDER BY created_at, id",
                (row, index) -> new AgentConnection(row.getString("id"), row.getString("owner_id"),
                        row.getString("display_name"), row.getString("endpoint_url"),
                        row.getString("model_id"), row.getString("created_at")), ownerId);
    }

    public void insert(AgentConnection connection) {
        jdbc.update("""
                INSERT INTO benchmark_connections (id, owner_id, display_name, endpoint_url, model_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, connection.id(), connection.ownerId(), connection.name(), connection.url(),
                connection.model(), connection.createdAt());
    }

    public boolean delete(String id, String ownerId) {
        return jdbc.update("DELETE FROM benchmark_connections WHERE id = ? AND owner_id = ?", id, ownerId) > 0;
    }
}

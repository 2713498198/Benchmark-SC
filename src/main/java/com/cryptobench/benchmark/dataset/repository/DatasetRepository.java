package com.cryptobench.benchmark.dataset.repository;

import com.cryptobench.benchmark.common.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DatasetRepository {
    private final JdbcTemplate jdbc;
    private final JsonDocuments json;

    public DatasetRepository(JdbcTemplate jdbc, JsonDocuments json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ObjectNode> findPublished() {
        return jdbc.query("SELECT payload FROM benchmark_datasets ORDER BY id, dataset_version",
                (row, index) -> json.read(row.getString("payload")));
    }

    public boolean exists(String id, String version) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_datasets WHERE id = ? AND dataset_version = ?",
                Long.class, id, version) > 0;
    }

    public void insert(String ownerId, ObjectNode payload) {
        jdbc.update("INSERT INTO benchmark_datasets (id, dataset_version, owner_id, payload) VALUES (?, ?, ?, ?)",
                payload.path("id").asText(), payload.path("version").asText(), ownerId, json.write(payload));
    }
}

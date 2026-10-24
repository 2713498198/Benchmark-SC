package com.cryptobench.benchmark.dataset.repository;

import com.cryptobench.benchmark.common.JsonDocuments;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 自定义测评集的持久化层；当前表只保存已发布版本，任务内容以 JSON 文档保存。
 */
@Repository
public class DatasetRepository {
    private final JdbcTemplate jdbc;
    private final JsonDocuments json;

    public DatasetRepository(JdbcTemplate jdbc, JsonDocuments json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 列出已保存的发布版本，按 ID 和版本字符串排序，而非语义版本比较。
     */
    public List<ObjectNode> findPublished() {
        return jdbc.query("SELECT payload FROM benchmark_datasets ORDER BY id, dataset_version",
                (row, index) -> json.read(row.getString("payload")));
    }

    /**
     * 检查 ID 与版本组合是否已存在，供服务层拒绝覆盖发布。
     */
    public boolean exists(String id, String version) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_datasets WHERE id = ? AND dataset_version = ?",
                Long.class, id, version) > 0;
    }

    /**
     * 插入完整发布文档；联合主键为 ID 和版本，数据库再次约束版本唯一性。
     */
    public void insert(String ownerId, ObjectNode payload) {
        jdbc.update("INSERT INTO benchmark_datasets (id, dataset_version, owner_id, payload) VALUES (?, ?, ?, ?)",
                payload.path("id").asText(), payload.path("version").asText(), ownerId, json.write(payload));
    }
}

package com.cryptobench.benchmark.dataset.repository;

import com.cryptobench.benchmark.common.JsonDocuments;
import com.cryptobench.benchmark.dataset.service.CustomDatasetRules;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DatasetRepository {
    private final JdbcTemplate jdbc;
    private final JsonDocuments json;

    public DatasetRepository(JdbcTemplate jdbc, JsonDocuments json) { this.jdbc = jdbc; this.json = json; }

    public List<ObjectNode> findPublished() {
        return jdbc.query("SELECT payload FROM benchmark_datasets", (row, index) -> json.read(row.getString("payload")))
                .stream().sorted(Comparator.comparing((ObjectNode item) -> item.path("id").asText())
                        .thenComparing(item -> item.path("version").asText(), CustomDatasetRules::compareVersions)).toList();
    }

    public Optional<ObjectNode> findPublic(String id, String version) {
        return jdbc.query("SELECT payload FROM benchmark_datasets WHERE id=? AND dataset_version=?",
                (row, index) -> json.read(row.getString("payload")), id, version).stream().findFirst();
    }

    /** Only execution/catalog projection services may read this internal package. */
    public Optional<ObjectNode> findPrivate(String id, String version) {
        return jdbc.query("SELECT private_payload,package_sha256 FROM benchmark_dataset_packages WHERE dataset_id=? AND dataset_version=?",
                (row, index) -> { ObjectNode payload = json.read(row.getString("private_payload"));
                    if (!CustomDatasetRules.hash(payload).equals(row.getString("package_sha256"))) throw new IllegalStateException("Private dataset package integrity check failed");
                    return payload;
                }, id, version).stream().findFirst();
    }

    public boolean exists(String id, String version) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_datasets WHERE id=? AND dataset_version=?", Long.class, id, version) > 0;
    }

    @Transactional
    public void insertPublished(String ownerId, ObjectNode summary, ObjectNode privatePackage) {
        jdbc.update("INSERT INTO benchmark_datasets(id,dataset_version,owner_id,payload,schema_version,execution_status,evaluator_version,catalog_sha256,published_at) VALUES(?,?,?,?,?,?,?,?,?)",
                summary.path("id").asText(), summary.path("version").asText(), ownerId, json.write(summary),
                summary.path("schema").asText(), summary.path("executionStatus").asText(),
                summary.has("evaluatorVersion") ? summary.path("evaluatorVersion").asText() : null,
                summary.path("catalogSha256").asText(), summary.has("publishedAt") ? summary.path("publishedAt").asText() : null);
        jdbc.update("INSERT INTO benchmark_dataset_packages(dataset_id,dataset_version,private_payload,package_sha256) VALUES(?,?,?,?)",
                summary.path("id").asText(), summary.path("version").asText(), json.write(privatePackage), CustomDatasetRules.hash(privatePackage));
    }

    /** Historical imports remain browsable and are never implicitly trusted for execution. */
    @Transactional
    public void insertLegacy(String ownerId, ObjectNode original) {
        ObjectNode privatePackage = original.deepCopy();
        privatePackage.remove("ownerId");
        String hash = CustomDatasetRules.publicHash(privatePackage, false);
        insertPublished(ownerId, CustomDatasetRules.summary(privatePackage, false, hash, Instant.now().toString()), privatePackage);
    }

    /** Compatibility for existing import callers; not the publish API. */
    @Transactional
    public void insert(String ownerId, ObjectNode original) { insertLegacy(ownerId, original); }
}

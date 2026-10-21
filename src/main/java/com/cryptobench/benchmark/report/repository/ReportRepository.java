package com.cryptobench.benchmark.report.repository;

import com.cryptobench.benchmark.common.JsonDocuments;
import com.cryptobench.benchmark.report.model.BenchmarkReport;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {
    private final JdbcTemplate jdbc;
    private final JsonDocuments json;
    private final RowMapper<BenchmarkReport> mapper;

    public ReportRepository(JdbcTemplate jdbc, JsonDocuments json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (row, index) -> new BenchmarkReport(row.getString("id"), row.getString("owner_id"),
                row.getBoolean("published"), json.read(row.getString("payload")));
    }

    public List<BenchmarkReport> findByOwner(String ownerId) {
        return jdbc.query("SELECT * FROM benchmark_reports WHERE owner_id = ? ORDER BY id", mapper, ownerId);
    }

    public List<BenchmarkReport> findPublished() {
        return jdbc.query("SELECT * FROM benchmark_reports WHERE published = TRUE ORDER BY id", mapper);
    }

    public Optional<BenchmarkReport> findById(String id, boolean forUpdate) {
        return jdbc.query("SELECT * FROM benchmark_reports WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                mapper, id).stream().findFirst();
    }

    public void insert(BenchmarkReport report) {
        jdbc.update("INSERT INTO benchmark_reports (id, owner_id, published, payload) VALUES (?, ?, ?, ?)",
                report.id(), report.ownerId(), report.published(), json.write(report.payload()));
    }

    public void update(BenchmarkReport report) {
        jdbc.update("UPDATE benchmark_reports SET published = ?, payload = ? WHERE id = ? AND owner_id = ?",
                report.published(), json.write(report.payload()), report.id(), report.ownerId());
    }
}

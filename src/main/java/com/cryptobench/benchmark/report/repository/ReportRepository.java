package com.cryptobench.benchmark.report.repository;

import com.cryptobench.benchmark.common.JsonDocuments;
import com.cryptobench.benchmark.report.model.BenchmarkReport;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 报告表的数据访问层；独立保存归属与公开状态，并负责 JSON 文本和对象之间的转换。
 */
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

    /**
     * 列出某个用户的全部报告，供私有工作台使用。
     */
    public List<BenchmarkReport> findByOwner(String ownerId) {
        return jdbc.query("SELECT * FROM benchmark_reports WHERE owner_id = ? ORDER BY id", mapper, ownerId);
    }

    /**
     * 只查询公开记录，供游客与公共榜单读取。
     */
    public List<BenchmarkReport> findPublished() {
        return jdbc.query("SELECT * FROM benchmark_reports WHERE published = TRUE ORDER BY id", mapper);
    }

    /**
     * 按 ID 查询报告；forUpdate 为 true 时追加行锁，应在服务层事务中使用。
     */
    public Optional<BenchmarkReport> findById(String id, boolean forUpdate) {
        // SQL 的可选片段仅由布尔参数选择，报告 ID 始终通过占位符绑定。
        return jdbc.query("SELECT * FROM benchmark_reports WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""),
                mapper, id).stream().findFirst();
    }

    /**
     * 插入报告元数据及完整 payload，归属由服务层确定。
     */
    public void insert(BenchmarkReport report) {
        jdbc.update("INSERT INTO benchmark_reports (id, owner_id, published, payload) VALUES (?, ?, ?, ?)",
                report.id(), report.ownerId(), report.published(), json.write(report.payload()));
    }

    /**
     * 同时更新独立公开标记和 JSON 文档，并通过 owner_id 条件限制归属。
     */
    public void update(BenchmarkReport report) {
        jdbc.update("UPDATE benchmark_reports SET published = ?, payload = ? WHERE id = ? AND owner_id = ?",
                report.published(), json.write(report.payload()), report.id(), report.ownerId());
    }
}

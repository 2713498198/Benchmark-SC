package com.cryptobench.benchmark.report.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.common.JsonDocuments;
import com.cryptobench.benchmark.report.model.BenchmarkReport;
import com.cryptobench.benchmark.report.repository.ReportRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告业务服务：控制用户归属、保存、同步与公开状态；不负责运行 Agent 或核验成绩。
 */
@Service
public class ReportService {
    private final ReportRepository reports;

    public ReportService(ReportRepository reports) {
        this.reports = reports;
    }

    /**
     * 游客仅看公开报告；登录用户仅看自己的报告，公开列表由独立方法提供。
     */
    public List<ObjectNode> list(BenchmarkUser user) {
        return (user == null ? reports.findPublished() : reports.findByOwner(user.id()))
                .stream().map(BenchmarkReport::toResponse).toList();
    }

    /**
     * 返回全部公开报告，供 bootstrap 和公共展示使用。
     */
    public List<ObjectNode> listPublished() {
        return reports.findPublished().stream().map(BenchmarkReport::toResponse).toList();
    }

    /**
     * 先允许读取公开记录，再对私有记录检查登录状态及所有权。
     */
    public ObjectNode get(BenchmarkUser user, String id) {
        BenchmarkReport report = reports.findById(id, false).orElse(null);
        if (report != null && report.published()) {
            return report.toResponse();
        }
        requireOwner(user, report);
        return report.toResponse();
    }

    /**
     * 在事务中新增或更新私有报告；已公开报告拒绝修改，客户端不能自行声明归属或公开状态。
     */
    @Transactional
    public ObjectNode save(BenchmarkUser user, ObjectNode input) {
        // 复制输入，避免规范化字段时改变调用方持有的对象。
        ObjectNode payload = input.deepCopy();
        if (payload.path("id").asText("").isBlank()) {
            payload.put("id", UUID.randomUUID().toString());
        }
        String id = JsonDocuments.requireIdentifier(payload, "id", 128);
        // 对已有记录加行锁，让读取当前状态与写入新状态处于同一事务。
        BenchmarkReport existing = reports.findById(id, true).orElse(null);
        if (existing != null && !existing.ownerId().equals(user.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "报告 ID 已属于其他用户");
        }
        if (existing != null && existing.published()) {
            throw new ApiException(HttpStatus.CONFLICT, "已公开报告不可修改");
        }
        // 忽略客户端归属和状态声明；归属来自当前用户，同步状态只能继承已有记录。
        payload.remove("ownerId");
        payload.put("synced", existing != null && existing.payload().path("synced").asBoolean());
        payload.put("published", false);
        // 首次保存使用服务端时间，更新保留原创建时间。
        payload.put("createdAt", existing == null ? Instant.now().toString()
                : existing.payload().path("createdAt").asText());
        var report = new BenchmarkReport(id, user.id(), false, payload);
        if (existing == null) {
            reports.insert(report);
        } else {
            reports.update(report);
        }
        return report.toResponse();
    }

    /**
     * 在事务和行锁保护下标记同步；synced 表示记录了同步操作，不代表成绩已验证。
     */
    @Transactional
    public ObjectNode sync(BenchmarkUser user, String id) {
        BenchmarkReport report = ownedForUpdate(user, id);
        report.payload().put("synced", true);
        reports.update(report);
        return report.toResponse();
    }

    /**
     * 仅公开已同步的完整非 PK 测评，并同时更新 JSON 和数据库公开列。
     */
    @Transactional
    public ObjectNode publish(BenchmarkUser user, String id) {
        BenchmarkReport report = ownedForUpdate(user, id);
        ObjectNode payload = report.payload();
        // 发布必须已同步、scope=full 且 kind 不是 pk；这些条件不等同于执行或评分认证。
        if (!payload.path("synced").asBoolean() || !"full".equals(payload.path("scope").asText())
                || "pk".equals(payload.path("kind").asText())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "报告必须先同步且为完整测评");
        }
        payload.put("published", true);
        reports.update(new BenchmarkReport(id, report.ownerId(), true, payload));
        return report.toResponse();
    }

    /**
     * 为后续状态修改锁定已存在的报告，并统一校验当前用户的所有权。
     */
    private BenchmarkReport ownedForUpdate(BenchmarkUser user, String id) {
        BenchmarkReport report = reports.findById(id, true).orElse(null);
        requireOwner(user, report);
        return report;
    }

    /**
     * 游客返回 401；记录不存在或不属于当前用户都返回 404，避免泄露私有记录是否存在。
     */
    private void requireOwner(BenchmarkUser user, BenchmarkReport report) {
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (report == null || !report.ownerId().equals(user.id())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "报告不存在");
        }
    }
}

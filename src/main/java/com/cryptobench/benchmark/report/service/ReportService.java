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

@Service
public class ReportService {
    private final ReportRepository reports;

    public ReportService(ReportRepository reports) {
        this.reports = reports;
    }

    public List<ObjectNode> list(BenchmarkUser user) {
        return (user == null ? reports.findPublished() : reports.findByOwner(user.id()))
                .stream().map(BenchmarkReport::toResponse).toList();
    }

    public List<ObjectNode> listPublished() {
        return reports.findPublished().stream().map(BenchmarkReport::toResponse).toList();
    }

    public ObjectNode get(BenchmarkUser user, String id) {
        BenchmarkReport report = reports.findById(id, false).orElse(null);
        if (report != null && report.published()) {
            return report.toResponse();
        }
        requireOwner(user, report);
        return report.toResponse();
    }

    @Transactional
    public ObjectNode save(BenchmarkUser user, ObjectNode input) {
        ObjectNode payload = input.deepCopy();
        if (payload.path("id").asText("").isBlank()) {
            payload.put("id", UUID.randomUUID().toString());
        }
        String id = JsonDocuments.requireIdentifier(payload, "id", 128);
        BenchmarkReport existing = reports.findById(id, true).orElse(null);
        if (existing != null && !existing.ownerId().equals(user.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "报告 ID 已属于其他用户");
        }
        if (existing != null && existing.published()) {
            throw new ApiException(HttpStatus.CONFLICT, "已公开报告不可修改");
        }
        payload.remove("ownerId");
        payload.put("synced", existing != null && existing.payload().path("synced").asBoolean());
        payload.put("published", false);
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

    @Transactional
    public ObjectNode sync(BenchmarkUser user, String id) {
        BenchmarkReport report = ownedForUpdate(user, id);
        report.payload().put("synced", true);
        reports.update(report);
        return report.toResponse();
    }

    @Transactional
    public ObjectNode publish(BenchmarkUser user, String id) {
        BenchmarkReport report = ownedForUpdate(user, id);
        ObjectNode payload = report.payload();
        if (!payload.path("synced").asBoolean() || !"full".equals(payload.path("scope").asText())
                || "pk".equals(payload.path("kind").asText())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "报告必须先同步且为完整测评");
        }
        payload.put("published", true);
        reports.update(new BenchmarkReport(id, report.ownerId(), true, payload));
        return report.toResponse();
    }

    private BenchmarkReport ownedForUpdate(BenchmarkUser user, String id) {
        BenchmarkReport report = reports.findById(id, true).orElse(null);
        requireOwner(user, report);
        return report;
    }

    private void requireOwner(BenchmarkUser user, BenchmarkReport report) {
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (report == null || !report.ownerId().equals(user.id())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "报告不存在");
        }
    }
}

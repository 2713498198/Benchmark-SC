package com.cryptobench.benchmark.report.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 持久化报告：ID、归属和公开标记是独立列，其余内容保存在 JSON payload 中。
 */
public record BenchmarkReport(String id, String ownerId, boolean published, ObjectNode payload) {
    /**
     * 复制报告内容并去除 ownerId，避免修改原文档或向前端暴露归属字段。
     */
    public ObjectNode toResponse() {
        ObjectNode response = payload.deepCopy();
        response.remove("ownerId");
        return response;
    }
}

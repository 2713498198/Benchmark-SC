package com.cryptobench.benchmark.report.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record BenchmarkReport(String id, String ownerId, boolean published, ObjectNode payload) {
    public ObjectNode toResponse() {
        ObjectNode response = payload.deepCopy();
        response.remove("ownerId");
        return response;
    }
}

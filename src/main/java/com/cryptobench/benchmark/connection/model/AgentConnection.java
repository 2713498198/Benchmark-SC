package com.cryptobench.benchmark.connection.model;

public record AgentConnection(String id, String ownerId, String name, String url, String model, String createdAt) {
    public ConnectionResponse toResponse() {
        return new ConnectionResponse(id, name, url, model, createdAt);
    }

    public record ConnectionResponse(String id, String name, String url, String model, String createdAt) {
    }
}

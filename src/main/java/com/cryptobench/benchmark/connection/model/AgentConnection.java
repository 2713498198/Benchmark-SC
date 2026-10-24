package com.cryptobench.benchmark.connection.model;

/**
 * Agent 连接的持久化模型；ownerId 用于用户隔离，当前仅保存连接元数据。
 */
public record AgentConnection(String id, String ownerId, String name, String url, String model, String createdAt) {
    /**
     * 构造前端响应，隐藏内部归属字段 ownerId。
     */
    public ConnectionResponse toResponse() {
        return new ConnectionResponse(id, name, url, model, createdAt);
    }

    // 公开给连接所有者的字段，不包含凭证或连通性认证结果。
    public record ConnectionResponse(String id, String name, String url, String model, String createdAt) {
    }
}

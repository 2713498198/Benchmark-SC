package com.cryptobench.benchmark.runner.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A paired local Runner owned by one website account. */
public record Runner(String id, String ownerId, String displayName, String tokenHash,
                     String runnerVersion, String status, ObjectNode agents,
                     boolean acceptingTasks, long createdAt, long lastSeen, Long revokedAt) {
    /** Expose Runner health without returning the authentication token hash. */
    public RunnerResponse toResponse(long now) {
        boolean online = revokedAt == null && now - lastSeen <= 30_000L;
        return new RunnerResponse(id, displayName, runnerVersion, status, agents,
                acceptingTasks, online, createdAt, lastSeen, revokedAt);
    }

    /** Browser-safe Runner metadata returned by the owner API. */
    public record RunnerResponse(String id, String displayName, String runnerVersion,
                                 String status, ObjectNode agents, boolean acceptingTasks,
                                 boolean online, long createdAt, long lastSeen, Long revokedAt) {
        /** Convert the response record to JSON for CLI registration. */
        public ObjectNode toNode(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", id); node.put("displayName", displayName);
            node.put("runnerVersion", runnerVersion); node.put("status", status);
            node.set("agents", agents == null ? mapper.createObjectNode() : agents.deepCopy());
            node.put("acceptingTasks", acceptingTasks); node.put("online", online);
            node.put("createdAt", createdAt); node.put("lastSeen", lastSeen);
            if (revokedAt == null) node.putNull("revokedAt"); else node.put("revokedAt", revokedAt);
            return node;
        }
    }
}

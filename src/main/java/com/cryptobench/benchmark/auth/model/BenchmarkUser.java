package com.cryptobench.benchmark.auth.model;

public record BenchmarkUser(String id, String email, String name, String role,
                            String passwordHash, String passwordSalt, String createdAt) {
    public UserResponse toResponse() {
        return new UserResponse(id, name, email, role, createdAt);
    }

    public record UserResponse(String id, String name, String email, String role, String createdAt) {
    }
}

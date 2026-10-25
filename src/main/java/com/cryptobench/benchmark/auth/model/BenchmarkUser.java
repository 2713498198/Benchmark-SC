package com.cryptobench.benchmark.auth.model;

/**
 * 数据库用户模型，同时作为认证成功后的当前用户对象。
 * passwordHash 用于密码验证；passwordSalt 兼容旧版 scrypt，新 PBKDF2 用户无需单独存盐。
 */
public record BenchmarkUser(String id, String email, String name, String role,
                            String passwordHash, String passwordSalt, String createdAt,
                            String accountStatus, String disabledAt, String disabledBy) {
    /** Backwards-compatible constructor for legacy imports and unit tests. */
    public BenchmarkUser(String id, String email, String name, String role,
                         String passwordHash, String passwordSalt, String createdAt) {
        this(id, email, name, role, passwordHash, passwordSalt, createdAt, "active", null, null);
    }

    /**
     * 转换为对外响应，明确排除密码哈希和旧版盐值。
     */
    public UserResponse toResponse() {
        return new UserResponse(id, name, email, role, accountStatus, createdAt);
    }

    // 前端可见的用户资料；role 用于显示角色，实际权限仍由后端校验。
    public record UserResponse(String id, String name, String email, String role,
                               String status, String createdAt) {
    }
}

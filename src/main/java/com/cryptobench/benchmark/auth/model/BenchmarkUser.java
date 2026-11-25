package com.cryptobench.benchmark.auth.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;

/**
 * 用户账号实体，对应 benchmark_users 表。
 *
 * <p>字段保持精简，只保存登录和个人展示所需信息。密码仅保存哈希；
 * passwordSalt 用于兼容旧版 scrypt 密码。</p>
 */
@TableName("benchmark_users")
public class BenchmarkUser implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("email")
    private String email;

    @TableField("display_name")
    private String name;

    @TableField("user_role")
    private String role;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("password_salt")
    private String passwordSalt;

    @TableField("created_at")
    private String createdAt;

    public BenchmarkUser() {
    }

    public BenchmarkUser(String id, String email, String name, String role,
                         String passwordHash, String passwordSalt, String createdAt) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.role = role;
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
        this.createdAt = createdAt;
    }

    /**
     * 转换为对外响应，明确排除密码哈希和旧版盐值。
     */
    public UserResponse toResponse() {
        return new UserResponse(id, name, email, role, createdAt);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getPasswordSalt() { return passwordSalt; }
    public void setPasswordSalt(String passwordSalt) { this.passwordSalt = passwordSalt; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String id() { return id; }
    public String email() { return email; }
    public String name() { return name; }
    public String role() { return role; }
    public String passwordHash() { return passwordHash; }
    public String passwordSalt() { return passwordSalt; }
    public String createdAt() { return createdAt; }

    /** 前端可见的用户资料；实际权限仍由后端校验。 */
    public record UserResponse(String id, String name, String email, String role, String createdAt) {
    }
}

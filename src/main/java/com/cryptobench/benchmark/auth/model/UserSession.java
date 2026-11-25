package com.cryptobench.benchmark.auth.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;

/** 数据库会话实体，只保存令牌摘要、用户 ID 与过期时间。 */
@TableName("benchmark_sessions")
public class UserSession implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "token_hash", type = IdType.INPUT)
    private String tokenHash;

    @TableField("user_id")
    private String userId;

    @TableField("expires_at")
    private Long expiresAt;

    public UserSession() {
    }

    public UserSession(String tokenHash, String userId, Long expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }

    public String tokenHash() { return tokenHash; }
    public String userId() { return userId; }
    public Long expiresAt() { return expiresAt; }
}

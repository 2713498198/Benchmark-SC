package com.cryptobench.benchmark.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.model.UserSession;
import com.cryptobench.benchmark.auth.mapper.UserSessionMapper;
import com.cryptobench.benchmark.auth.service.UserAccountService;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 数据库会话存储：只保存令牌摘要、用户 ID 和过期时间，不保存原始 Cookie 令牌。
 */
@Repository
public class SessionRepository {
    private final UserSessionMapper sessions;
    private final UserAccountService users;

    public SessionRepository(UserSessionMapper sessions, UserAccountService users) {
        this.sessions = sessions;
        this.users = users;
    }

    /**
     * 仅匹配尚未过期的令牌摘要，再查询关联用户；不存在或已过期都视为未登录。
     */
    public Optional<BenchmarkUser> findUser(String tokenHash) {
        UserSession session = sessions.selectById(tokenHash);
        if (session == null || session.expiresAt() <= System.currentTimeMillis()) return Optional.empty();
        return users.findById(session.userId());
    }

    /**
     * 创建会话前顺便清理过期记录；expiresAt 使用 Unix 毫秒时间戳。
     */
    public void insert(String tokenHash, String userId, long expiresAt) {
        sessions.delete(new LambdaQueryWrapper<UserSession>()
                .le(UserSession::getExpiresAt, System.currentTimeMillis()));
        sessions.insert(new UserSession(tokenHash, userId, expiresAt));
    }

    /**
     * 按令牌摘要撤销单个会话；记录已不存在时也可安全调用。
     */
    public void delete(String tokenHash) {
        sessions.deleteById(tokenHash);
    }

    public void deleteByUser(String userId) {
        sessions.delete(new LambdaQueryWrapper<UserSession>().eq(UserSession::getUserId, userId));
    }

    public int deleteByUserAndCount(String userId) {
        return sessions.delete(new LambdaQueryWrapper<UserSession>().eq(UserSession::getUserId, userId));
    }
}

package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.dto.LoginRequest;
import com.cryptobench.benchmark.auth.dto.RegisterRequest;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.service.AuthRateLimitService;
import com.cryptobench.benchmark.common.ApiException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号业务服务：负责注册和密码验证。
 */
@Service
public class AuthService {
    private final UserAccountService users;
    private final PasswordService passwords;
    private final AuthRateLimitService rates;

    public AuthService(UserAccountService users, PasswordService passwords, AuthRateLimitService rates) {
        this.users = users;
        this.passwords = passwords;
        this.rates = rates;
    }

    /**
     * 在事务中注册研究者；统一邮箱格式并拒绝重复邮箱，角色不接受客户端指定。
     */
    @Transactional
    public BenchmarkUser register(RegisterRequest input, String remoteAddr) {
        rates.beforeRegister(remoteAddr);
        rates.registerAttempted(remoteAddr);
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "该邮箱已注册");
        }
        // 用户 ID、角色、密码哈希与创建时间均由服务端生成。
        BenchmarkUser user = new BenchmarkUser("user_" + UUID.randomUUID(), email, input.name().trim(),
                "researcher", passwords.encode(input.password()), null, Instant.now().toString());
        users.insert(user);
        return user;
    }

    /**
     * 按规范化邮箱验证密码；账号不存在和密码错误使用相同提示。
     */
    public BenchmarkUser login(LoginRequest input, String remoteAddr) {
        String email = input.email().trim().toLowerCase(Locale.ROOT);
        rates.beforeLogin(email);
        BenchmarkUser user = users.findByEmail(email).orElse(null);
        if (user == null || !passwords.matches(input.password(), user)) {
            rates.loginFailed(email);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确");
        }
        rates.loginSucceeded(email);
        return user;
    }

}

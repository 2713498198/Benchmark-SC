package com.cryptobench.benchmark.auth.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cryptobench.benchmark.auth.mapper.UserAccountMapper;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.service.UserAccountService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 用户账号数据访问实现，采用 MyBatis-Plus 标准 ServiceImpl 写法。 */
@Service
public class UserAccountServiceImpl extends ServiceImpl<UserAccountMapper, BenchmarkUser>
        implements UserAccountService {

    @Override
    public Optional<BenchmarkUser> findByEmail(String email) {
        return Optional.ofNullable(lambdaQuery().eq(BenchmarkUser::getEmail, email).one());
    }

    @Override
    public Optional<BenchmarkUser> findById(String id) {
        return Optional.ofNullable(getById(id));
    }

    @Override
    public boolean existsByEmail(String email) {
        return lambdaQuery().eq(BenchmarkUser::getEmail, email).count() > 0;
    }

    @Override
    public void insert(BenchmarkUser user) {
        if (!save(user)) {
            throw new IllegalStateException("用户账号保存失败");
        }
    }

    @Override
    public void updatePassword(String userId, String passwordHash) {
        lambdaUpdate().eq(BenchmarkUser::getId, userId)
                .set(BenchmarkUser::getPasswordHash, passwordHash)
                .set(BenchmarkUser::getPasswordSalt, null)
                .update();
    }
}

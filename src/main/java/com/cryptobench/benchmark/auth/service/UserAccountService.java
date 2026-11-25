package com.cryptobench.benchmark.auth.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import java.util.Optional;

/** 用户账号领域服务，基础 CRUD 由 MyBatis-Plus IService 提供。 */
public interface UserAccountService extends IService<BenchmarkUser> {
    Optional<BenchmarkUser> findByEmail(String email);

    Optional<BenchmarkUser> findById(String id);

    boolean existsByEmail(String email);

    void insert(BenchmarkUser user);

    void updatePassword(String userId, String passwordHash);
}

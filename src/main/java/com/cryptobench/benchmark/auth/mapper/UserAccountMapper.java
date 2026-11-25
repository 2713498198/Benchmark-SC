package com.cryptobench.benchmark.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import org.apache.ibatis.annotations.Mapper;

/** 用户账号数据访问接口，CRUD 由 MyBatis-Plus 提供。 */
@Mapper
public interface UserAccountMapper extends BaseMapper<BenchmarkUser> {
}

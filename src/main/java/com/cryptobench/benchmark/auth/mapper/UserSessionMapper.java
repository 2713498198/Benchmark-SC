package com.cryptobench.benchmark.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cryptobench.benchmark.auth.model.UserSession;
import org.apache.ibatis.annotations.Mapper;

/** 用户会话数据访问接口，CRUD 由 MyBatis-Plus 提供。 */
@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {
}

package com.cryptobench.benchmark.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 统一执行仓储层参数化 SQL 的 MyBatis Mapper。 */
public interface RawSqlMapper {
    /** 执行查询并返回列名到值的映射。 */
    List<Map<String, Object>> select(@Param("sql") String sql, @Param("params") List<Object> params);

    /** 执行新增、更新或删除语句并返回受影响行数。 */
    int update(@Param("sql") String sql, @Param("params") List<Object> params);
}

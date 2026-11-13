package com.cryptobench.benchmark.persistence;

/** 把一行 MyBatis 查询结果转换为业务对象。 */
@FunctionalInterface
public interface MybatisRowMapper<T> {
    T map(MybatisRow row, int index);
}

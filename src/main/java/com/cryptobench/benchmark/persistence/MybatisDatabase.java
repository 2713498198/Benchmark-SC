package com.cryptobench.benchmark.persistence;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 的统一数据库入口。
 * SQL 中的问号占位符会转换为 MyBatis 参数占位符，避免把业务参数拼接进 SQL。
 */
@Component
public class MybatisDatabase {
    private final SqlSessionTemplate sessions;
    private final JdbcTemplate jdbc;

    @Autowired
    public MybatisDatabase(SqlSessionTemplate sessions) {
        this.sessions = sessions; this.jdbc = null;
    }

    public MybatisDatabase(JdbcTemplate jdbc) {
        this.sessions = null; this.jdbc = jdbc;
    }

    public int update(String sql, Object... parameters) {
        if (jdbc != null) return jdbc.update(sql, parameters);
        return sessions.update("com.cryptobench.benchmark.persistence.RawSqlMapper.update", arguments(sql, parameters));
    }

    public void execute(String sql) {
        update(sql);
    }

    public <T> List<T> query(String sql, MybatisRowMapper<T> mapper, Object... parameters) {
        if (jdbc != null) return jdbc.query(sql, (resultSet, index) -> mapper.map(new MybatisRow(resultSet), index), parameters);
        List<Map<String, Object>> rows = sessions.selectList("com.cryptobench.benchmark.persistence.RawSqlMapper.select",
                arguments(sql, parameters));
        return java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(index -> mapper.map(new MybatisRow(rows.get(index)), index)).toList();
    }

    public <T> T queryForObject(String sql, Class<T> type, Object... parameters) {
        if (jdbc != null) {
            List<T> values = jdbc.query(sql, (resultSet, index) -> convert(resultSet.getObject(1), type), parameters);
            return values.isEmpty() ? null : values.getFirst();
        }
        List<MybatisRow> rows = query(sql, (row, index) -> row, parameters);
        if (rows.isEmpty()) return null;
        Object value = rows.getFirst().firstValue();
        return convert(value, type);
    }

    /** 查询单行并使用调用方提供的映射器转换结果。 */
    public <T> T queryForObject(String sql, MybatisRowMapper<T> mapper, Object... parameters) {
        List<T> rows = query(sql, mapper, parameters);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(Object value, Class<T> type) {
        if (value == null) return null;
        if (type.isInstance(value)) return (T) value;
        if (type == Integer.class || type == int.class) return (T) Integer.valueOf(((Number) value).intValue());
        if (type == Long.class || type == long.class) return (T) Long.valueOf(((Number) value).longValue());
        if (type == Boolean.class || type == boolean.class) return (T) Boolean.valueOf(value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString()));
        if (type == String.class) return (T) value.toString();
        throw new IllegalArgumentException("不支持的查询结果类型: " + type.getName());
    }

    private static Map<String, Object> arguments(String sql, Object[] parameters) {
        StringBuilder bound = new StringBuilder();
        int parameterIndex = 0;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '?') bound.append("#{params[").append(parameterIndex++).append("]}");
            else bound.append(current);
        }
        if (parameterIndex != parameters.length) {
            throw new IllegalArgumentException("SQL 占位符数量与参数数量不一致");
        }
        // HashMap 允许参数列表中存在 null，Map.of 会直接拒绝 null 值。
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("sql", bound.toString());
        arguments.put("params", Arrays.asList(parameters));
        return arguments;
    }
}

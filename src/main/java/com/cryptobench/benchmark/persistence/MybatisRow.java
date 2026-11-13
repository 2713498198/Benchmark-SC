package com.cryptobench.benchmark.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 对 MyBatis 查询结果提供接近 JDBC ResultSet 的类型读取能力。 */
public final class MybatisRow {
    private final Map<String, Object> values;

    public MybatisRow(Map<String, Object> values) {
        this.values = values;
    }

    public MybatisRow(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            String label = metadata.getColumnLabel(column);
            if (label == null || label.isBlank()) label = metadata.getColumnName(column);
            values.put(label, resultSet.getObject(column));
        }
        this.values = values;
    }

    public String getString(String column) {
        Object value = value(column);
        return value == null ? null : value.toString();
    }

    public int getInt(String column) {
        Object value = value(column);
        return value == null ? 0 : ((Number) value).intValue();
    }

    public long getLong(String column) {
        Object value = value(column);
        return value == null ? 0L : ((Number) value).longValue();
    }

    public boolean getBoolean(String column) {
        Object value = value(column);
        if (value instanceof Boolean result) return result;
        if (value instanceof Number number) return number.intValue() != 0;
        return value != null && Boolean.parseBoolean(value.toString());
    }

    public BigDecimal getBigDecimal(String column) {
        Object value = value(column);
        return value == null ? null : value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    public Object getObject(String column) {
        return value(column);
    }

    public Object firstValue() {
        return values.values().stream().findFirst().orElse(null);
    }

    private Object value(String column) {
        Object direct = values.get(column);
        if (direct != null || values.containsKey(column)) return direct;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) return entry.getValue();
        }
        return null;
    }
}

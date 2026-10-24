package com.cryptobench.benchmark.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 报告和测评集 JSON 文档的存储辅助组件，同时提供业务标识符的基础校验。
 */
@Component
public class JsonDocuments {
    private final ObjectMapper mapper;

    public JsonDocuments(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 把数据库中的 JSON 文本还原为对象；格式或节点类型错误属于存储数据异常。
     */
    public ObjectNode read(String value) {
        try {
            return (ObjectNode) mapper.readTree(value);
        } catch (JsonProcessingException | ClassCastException exception) {
            throw new IllegalStateException("Invalid stored JSON document", exception);
        }
    }

    /**
     * 将对象序列化为紧凑 JSON 文本，供数据库 payload 字段保存。
     */
    public String write(ObjectNode value) {
        return value.toString();
    }

    /**
     * 要求字段非空、长度不超限，并只包含字母、数字、下划线、点和连字符。
     */
    public static String requireIdentifier(ObjectNode input, String field, int maxLength) {
        String value = input.path(field).asText("");
        if (value.isBlank() || value.length() > maxLength || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, field + " 格式无效");
        }
        return value;
    }
}

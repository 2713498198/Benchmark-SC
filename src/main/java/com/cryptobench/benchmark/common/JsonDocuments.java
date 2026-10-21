package com.cryptobench.benchmark.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class JsonDocuments {
    private final ObjectMapper mapper;

    public JsonDocuments(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode read(String value) {
        try {
            return (ObjectNode) mapper.readTree(value);
        } catch (JsonProcessingException | ClassCastException exception) {
            throw new IllegalStateException("Invalid stored JSON document", exception);
        }
    }

    public String write(ObjectNode value) {
        return value.toString();
    }

    public static String requireIdentifier(ObjectNode input, String field, int maxLength) {
        String value = input.path(field).asText("");
        if (value.isBlank() || value.length() > maxLength || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, field + " 格式无效");
        }
        return value;
    }
}

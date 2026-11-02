package com.cryptobench.benchmark.dataset.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.dataset.service.CustomDatasetRules;
import com.cryptobench.benchmark.dataset.service.DatasetService;
import com.cryptobench.benchmark.dataset.service.DatasetValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {
    private final DatasetService datasets;
    private final ObjectMapper mapper;
    public DatasetController(DatasetService datasets, ObjectMapper mapper) { this.datasets = datasets; this.mapper = mapper; }

    @PostMapping(value = "/custom", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode publish(@AuthenticationPrincipal BenchmarkUser user, @RequestBody byte[] input) {
        return datasets.publish(user, parse(input));
    }

    @PostMapping(value = "/custom/validate", consumes = "application/json")
    public ObjectNode validate(@AuthenticationPrincipal BenchmarkUser user, @RequestBody byte[] input) {
        return datasets.validate(user, parse(input));
    }

    @GetMapping("/{id}/versions/{version}/catalog")
    public ObjectNode catalog(@PathVariable String id, @PathVariable String version) { return datasets.publicCatalog(id, version); }

    @ExceptionHandler(DatasetValidationException.class)
    public ResponseEntity<?> invalid(DatasetValidationException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage(), "errors", exception.errors()));
    }

    private ObjectNode parse(byte[] input) {
        if (input.length > CustomDatasetRules.MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "任务包不得超过 2,000,000 字节");
        try {
            JsonNode node = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
            if (node instanceof ObjectNode object) return object;
        } catch (java.io.IOException ignored) { }
        throw new ApiException(HttpStatus.BAD_REQUEST, "任务包必须是有效的 JSON 对象");
    }
}

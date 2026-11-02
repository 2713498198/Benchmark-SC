package com.cryptobench.benchmark.dataset.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.dataset.repository.DatasetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetService {
    private final DatasetRepository datasets;
    private final ObjectMapper mapper;
    public DatasetService(DatasetRepository datasets, ObjectMapper mapper) { this.datasets = datasets; this.mapper = mapper; }

    public List<ObjectNode> listPublished() { return datasets.findPublished(); }

    public ObjectNode validate(BenchmarkUser user, ObjectNode input) {
        requireAdmin(user);
        var validation = CustomDatasetRules.validate(input);
        var issues = new ArrayList<>(validation.errors());
        if (validation.valid() && datasets.exists(input.path("id").asText().trim(), input.path("version").asText().trim())) {
            issues.add(new CustomDatasetRules.Issue("version", "duplicate_version", "相同 ID 与版本已经发布，请发布新版本"));
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("valid", issues.isEmpty());
        response.set("errors", mapper.valueToTree(issues));
        if (issues.isEmpty()) {
            String hash = CustomDatasetRules.publicHash(validation.normalized(), true);
            ObjectNode preview = CustomDatasetRules.summary(validation.normalized(), true, hash, null);
            response.set("preview", CustomDatasetRules.publicCatalog(preview, validation.normalized()));
        }
        return response;
    }

    @Transactional
    public ObjectNode publish(BenchmarkUser user, ObjectNode input) {
        requireAdmin(user);
        var validation = CustomDatasetRules.validate(input);
        if (!validation.valid()) throw new DatasetValidationException(validation.errors());
        ObjectNode normalized = validation.normalized();
        if (datasets.exists(normalized.path("id").asText(), normalized.path("version").asText())) throw new ApiException(HttpStatus.CONFLICT, "相同 ID 与版本已经发布");
        ObjectNode summary = CustomDatasetRules.summary(normalized, true, CustomDatasetRules.publicHash(normalized, true), Instant.now().toString());
        datasets.insertPublished(user.id(), summary, normalized);
        return summary;
    }

    public ObjectNode publicCatalog(String id, String version) {
        ObjectNode summary = datasets.findPublic(id, version).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "测评集版本不存在"));
        ObjectNode privatePackage = datasets.findPrivate(id, version).orElseGet(mapper::createObjectNode);
        return CustomDatasetRules.publicCatalog(summary, privatePackage);
    }

    private void requireAdmin(BenchmarkUser user) {
        if (user == null || !"admin".equals(user.role())) throw new ApiException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
}

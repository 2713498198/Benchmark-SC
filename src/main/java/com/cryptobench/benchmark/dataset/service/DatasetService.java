package com.cryptobench.benchmark.dataset.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.common.JsonDocuments;
import com.cryptobench.benchmark.dataset.repository.DatasetRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetService {
    private final DatasetRepository datasets;

    public DatasetService(DatasetRepository datasets) {
        this.datasets = datasets;
    }

    public List<ObjectNode> listPublished() {
        return datasets.findPublished();
    }

    @Transactional
    public ObjectNode publish(BenchmarkUser user, ObjectNode input) {
        if (user == null || !"admin".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        String id = JsonDocuments.requireIdentifier(input, "id", 128);
        String version = JsonDocuments.requireIdentifier(input, "version", 40);
        if (!input.path("tasks").isArray() || input.path("tasks").isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "测评集格式无效");
        }
        if (datasets.exists(id, version)) {
            throw new ApiException(HttpStatus.CONFLICT, "相同 ID 与版本已经发布");
        }
        ObjectNode payload = input.deepCopy();
        payload.remove("ownerId");
        payload.set("taskPackage", input.path("tasks").deepCopy());
        payload.put("tasks", input.path("tasks").size());
        payload.put("published", true);
        datasets.insert(user.id(), payload);
        return payload;
    }
}

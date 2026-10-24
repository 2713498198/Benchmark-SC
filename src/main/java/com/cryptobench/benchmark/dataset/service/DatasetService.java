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

/**
 * 自定义测评集发布规则：管理员权限、标识符校验、非空任务数组和版本不可覆盖。
 */
@Service
public class DatasetService {
    private final DatasetRepository datasets;

    public DatasetService(DatasetRepository datasets) {
        this.datasets = datasets;
    }

    /**
     * 返回所有已发布的自定义版本，供初始化接口合并到前端目录。
     */
    public List<ObjectNode> listPublished() {
        return datasets.findPublished();
    }

    /**
     * 在事务中校验并发布；当前只检查基础结构，不执行题目或验证评分器。
     */
    @Transactional
    public ObjectNode publish(BenchmarkUser user, ObjectNode input) {
        // 在业务层再次校验角色，避免绕开 HTTP 路由直接调用服务时失去权限约束。
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
        // 复制输入后再规范化，避免直接修改调用方的 JSON 对象。
        ObjectNode payload = input.deepCopy();
        // 归属取自当前登录用户，忽略客户端提交的 ownerId。
        payload.remove("ownerId");
        // 完整任务数组保留为 taskPackage，tasks 改为数量，便于前端目录统一展示。
        payload.set("taskPackage", input.path("tasks").deepCopy());
        payload.put("tasks", input.path("tasks").size());
        payload.put("published", true);
        datasets.insert(user.id(), payload);
        return payload;
    }
}

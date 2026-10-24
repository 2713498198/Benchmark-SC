package com.cryptobench.benchmark.workspace.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.connection.service.ConnectionService;
import com.cryptobench.benchmark.dataset.service.DatasetService;
import com.cryptobench.benchmark.report.service.ReportService;
import com.cryptobench.benchmark.workspace.dto.BootstrapResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端初始化聚合接口：一次返回账号、私有连接与报告、公开报告和自定义测评集。
 */
@RestController
public class WorkspaceController {
    private final ConnectionService connections;
    private final ReportService reports;
    private final DatasetService datasets;

    public WorkspaceController(ConnectionService connections, ReportService reports, DatasetService datasets) {
        this.connections = connections;
        this.reports = reports;
        this.datasets = datasets;
    }

    /**
     * 游客的私有字段为空，仍可获取公开数据；登录用户的私有查询始终使用认证用户 ID。
     */
    @GetMapping("/api/bootstrap")
    public BootstrapResponse bootstrap(@AuthenticationPrincipal BenchmarkUser user) {
        // 公开报告与用户自己的报告分开返回，前端据此维护工作台和公共榜单状态。
        return new BootstrapResponse(user == null ? null : user.toResponse(),
                user == null ? List.of() : connections.list(user.id()),
                user == null ? List.of() : reports.list(user), reports.listPublished(), datasets.listPublished());
    }
}

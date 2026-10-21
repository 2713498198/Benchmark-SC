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

    @GetMapping("/api/bootstrap")
    public BootstrapResponse bootstrap(@AuthenticationPrincipal BenchmarkUser user) {
        return new BootstrapResponse(user == null ? null : user.toResponse(),
                user == null ? List.of() : connections.list(user.id()),
                user == null ? List.of() : reports.list(user), reports.listPublished(), datasets.listPublished());
    }
}

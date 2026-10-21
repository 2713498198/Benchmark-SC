package com.cryptobench.benchmark.report.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.report.service.ReportService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public List<ObjectNode> list(@AuthenticationPrincipal BenchmarkUser user) {
        return reports.list(user);
    }

    @GetMapping("/{id}")
    public ObjectNode get(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        return reports.get(user, id);
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode save(@AuthenticationPrincipal BenchmarkUser user, @RequestBody ObjectNode input) {
        return reports.save(user, input);
    }

    @PostMapping("/{id}/sync")
    public ObjectNode sync(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        return reports.sync(user, id);
    }

    @PostMapping("/{id}/publish")
    public ObjectNode publish(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        return reports.publish(user, id);
    }
}

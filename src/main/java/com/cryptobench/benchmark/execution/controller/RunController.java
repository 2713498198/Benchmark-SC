package com.cryptobench.benchmark.execution.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.execution.model.RunLog;
import com.cryptobench.benchmark.execution.service.RunService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RunController {
    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @PostMapping(value = "/agent/check", consumes = "application/json")
    public ObjectNode check(@RequestBody ObjectNode input) {
        return runs.checkAgent(input);
    }

    @GetMapping("/agent/runtime")
    public ObjectNode runtime() {
        return runs.runtimeStatus();
    }

    @PostMapping(value = "/runs", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ObjectNode create(@AuthenticationPrincipal BenchmarkUser user, @RequestBody ObjectNode input) {
        return runs.create(user, input);
    }

    @GetMapping("/runs")
    public List<ObjectNode> list(@AuthenticationPrincipal BenchmarkUser user) {
        return runs.list(user);
    }

    @GetMapping("/runs/{id}")
    public ObjectNode get(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        return runs.get(user, id);
    }

    @GetMapping("/runs/{id}/logs")
    public List<RunLog> logs(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id,
                             @RequestParam(defaultValue = "0") long after) {
        return runs.logs(user, id, after);
    }

    @PostMapping("/runs/{id}/cancel")
    public ObjectNode cancel(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        return runs.cancel(user, id);
    }

    @PostMapping("/runs/{id}/retry")
    public ObjectNode retry(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id,
                            @RequestBody(required = false) ObjectNode input) {
        return runs.retry(user, id, input);
    }
}

package com.cryptobench.benchmark.dataset.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.dataset.service.DatasetService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/datasets")
public class DatasetController {
    private final DatasetService datasets;

    public DatasetController(DatasetService datasets) {
        this.datasets = datasets;
    }

    @PostMapping(value = "/custom", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode publish(@AuthenticationPrincipal BenchmarkUser user, @RequestBody ObjectNode input) {
        return datasets.publish(user, input);
    }
}

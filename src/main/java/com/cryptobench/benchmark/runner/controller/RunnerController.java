package com.cryptobench.benchmark.runner.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.runner.dto.RunnerHeartbeatRequest;
import com.cryptobench.benchmark.runner.dto.RunnerPairRequest;
import com.cryptobench.benchmark.runner.dto.RunnerResultRequest;
import com.cryptobench.benchmark.runner.model.Runner.RunnerResponse;
import com.cryptobench.benchmark.runner.service.RunnerService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Browser owner endpoints and bearer-authenticated local Runner endpoints. */
@RestController
public class RunnerController {
    private final RunnerService service;

    public RunnerController(RunnerService service) {
        this.service = service;
    }

    /** Create a pairing code for the currently logged-in account. */
    @PostMapping("/api/runners/pair")
    public ObjectNode pair(@AuthenticationPrincipal BenchmarkUser user) {
        return service.createPairing(user.id());
    }

    /** List the owner's paired Runners. */
    @GetMapping("/api/runners")
    public List<RunnerResponse> list(@AuthenticationPrincipal BenchmarkUser user) {
        return service.list(user.id());
    }

    /** Revoke one of the owner's Runners. */
    @DeleteMapping("/api/runners/{id}")
    public ObjectNode revoke(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        service.revoke(user.id(), id);
        ObjectNode response = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        response.put("ok", true);
        return response;
    }

    /** Consume a one-time pairing code; this endpoint is authenticated by the code itself. */
    @PostMapping(value = "/api/runner/register", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode register(@Valid @RequestBody RunnerPairRequest input) {
        return service.register(input);
    }

    /** Long-polling client asks for the next durable task. */
    @GetMapping("/api/runner/jobs/next")
    public ObjectNode next(@RequestHeader(value = "Authorization", required = false) String authorization) {
        ObjectNode job = service.next(authorization);
        return job == null ? new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().putNull("job") : job;
    }

    /** Runner sends health and local Agent capability metadata. */
    @PostMapping(value = "/api/runner/heartbeat", consumes = "application/json")
    public ObjectNode heartbeat(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @Valid @RequestBody RunnerHeartbeatRequest input) {
        return service.heartbeat(authorization, input);
    }

    /** Runner sends an Agent response; the website later performs the trusted evaluation. */
    @PostMapping(value = "/api/runner/jobs/{id}/result", consumes = "application/json")
    public ObjectNode result(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @PathVariable String id, @RequestBody RunnerResultRequest input) {
        return service.result(authorization, id, input);
    }
}

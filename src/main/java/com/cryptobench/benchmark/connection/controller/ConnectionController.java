package com.cryptobench.benchmark.connection.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.connection.dto.ConnectionRequest;
import com.cryptobench.benchmark.connection.model.AgentConnection.ConnectionResponse;
import com.cryptobench.benchmark.connection.service.ConnectionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {
    private final ConnectionService connections;

    public ConnectionController(ConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping
    public List<ConnectionResponse> list(@AuthenticationPrincipal BenchmarkUser user) {
        return connections.list(user.id());
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionResponse create(@AuthenticationPrincipal BenchmarkUser user,
                                     @Valid @RequestBody ConnectionRequest input) {
        return connections.create(user.id(), input);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        connections.delete(user.id(), id);
        return Map.of("ok", true);
    }
}

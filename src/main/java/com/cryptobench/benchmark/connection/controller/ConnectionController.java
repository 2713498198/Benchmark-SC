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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录用户的 Agent 连接管理接口；归属用户始终取自认证信息，不接受客户端指定。
 */
@RestController
@RequestMapping("/api/connections")
public class ConnectionController {
    private final ConnectionService connections;

    public ConnectionController(ConnectionService connections) {
        this.connections = connections;
    }

    /**
     * 只返回当前用户自己的连接元数据。
     */
    @GetMapping
    public List<ConnectionResponse> list(@AuthenticationPrincipal BenchmarkUser user) {
        return connections.list(user.id());
    }

    /**
     * 校验连接请求并保存，成功返回 201；不发起远程连接或执行模型调用。
     */
    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionResponse create(@AuthenticationPrincipal BenchmarkUser user,
                                     @Valid @RequestBody ConnectionRequest input) {
        return connections.create(user.id(), input);
    }

    /**
     * 删除当前用户名下的指定连接，成功返回统一确认结构。
     */
    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        connections.delete(user.id(), id);
        return Map.of("ok", true);
    }

    @PatchMapping(value = "/{id}", consumes = "application/json")
    public ConnectionResponse update(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id,
                                     @Valid @RequestBody ConnectionRequest input) {
        return connections.update(user.id(), id, input);
    }

    @PostMapping("/{id}/check")
    public com.fasterxml.jackson.databind.node.ObjectNode check(@AuthenticationPrincipal BenchmarkUser user,
                                                                 @PathVariable String id) {
        return connections.check(user.id(), id);
    }
}

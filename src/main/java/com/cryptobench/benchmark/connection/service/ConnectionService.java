package com.cryptobench.benchmark.connection.service;

import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.connection.dto.ConnectionRequest;
import com.cryptobench.benchmark.connection.model.AgentConnection;
import com.cryptobench.benchmark.connection.model.AgentConnection.ConnectionResponse;
import com.cryptobench.benchmark.connection.repository.ConnectionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.cryptobench.benchmark.execution.service.AgentHttpClient;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 连接业务服务：创建服务端 ID 和时间，转换响应，并处理删除失败；不保存凭证。
 */
@Service
public class ConnectionService {
    private final ConnectionRepository connections;
    private final AgentHttpClient agent;

    public ConnectionService(ConnectionRepository connections, AgentHttpClient agent) {
        this.connections = connections;
        this.agent = agent;
    }

    /**
     * 取得指定用户的连接，并统一转换为不包含归属字段的响应。
     */
    public List<ConnectionResponse> list(String ownerId) {
        return connections.findByOwner(ownerId).stream().map(AgentConnection::toResponse).toList();
    }

    /**
     * 在事务中保存元数据，名称和模型去除首尾空白；不验证远程端点连通性。
     */
    @Transactional
    public ConnectionResponse create(String ownerId, ConnectionRequest input) {
        var connection = new AgentConnection("conn_" + UUID.randomUUID(), ownerId,
                input.name().trim(), input.url(), input.model().trim(), Instant.now().toString());
        connections.insert(connection);
        return connection.toResponse();
    }

    /**
     * 仅删除本人的连接；不存在和属于其他用户都返回 404。
     */
    @Transactional
    public void delete(String ownerId, String id) {
        if (!connections.delete(id, ownerId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "连接不存在");
        }
    }

    @Transactional
    public ConnectionResponse update(String ownerId, String id, ConnectionRequest input) {
        if (!connections.update(id, ownerId, input.name().trim(), input.url(), input.model().trim())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "连接不存在");
        }
        return connections.findByOwner(ownerId).stream().filter(item -> item.id().equals(id))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "连接不存在")).toResponse();
    }

    public ObjectNode check(String ownerId, String id) {
        var connection = connections.findByOwner(ownerId).stream().filter(item -> item.id().equals(id))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "连接不存在"));
        try {
            ObjectNode result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            result.put("reachable", true);
            result.set("health", agent.check(connection.url(), null));
            result.set("capabilities", agent.capabilities(connection.url(), null));
            return result;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Agent 连接检查失败");
        }
    }
}

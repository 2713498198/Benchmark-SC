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

/**
 * 连接业务服务：创建服务端 ID 和时间，转换响应，并处理删除失败；不保存凭证。
 */
@Service
public class ConnectionService {
    private final ConnectionRepository connections;

    public ConnectionService(ConnectionRepository connections) {
        this.connections = connections;
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
}

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

@Service
public class ConnectionService {
    private final ConnectionRepository connections;

    public ConnectionService(ConnectionRepository connections) {
        this.connections = connections;
    }

    public List<ConnectionResponse> list(String ownerId) {
        return connections.findByOwner(ownerId).stream().map(AgentConnection::toResponse).toList();
    }

    @Transactional
    public ConnectionResponse create(String ownerId, ConnectionRequest input) {
        var connection = new AgentConnection("conn_" + UUID.randomUUID(), ownerId,
                input.name().trim(), input.url(), input.model().trim(), Instant.now().toString());
        connections.insert(connection);
        return connection.toResponse();
    }

    @Transactional
    public void delete(String ownerId, String id) {
        if (!connections.delete(id, ownerId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "连接不存在");
        }
    }
}

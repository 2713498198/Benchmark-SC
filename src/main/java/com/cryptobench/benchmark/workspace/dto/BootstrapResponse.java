package com.cryptobench.benchmark.workspace.dto;

import com.cryptobench.benchmark.auth.model.BenchmarkUser.UserResponse;
import com.cryptobench.benchmark.connection.model.AgentConnection.ConnectionResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

public record BootstrapResponse(UserResponse user, List<ConnectionResponse> connections,
                                List<ObjectNode> reports, List<ObjectNode> publicReports,
                                List<ObjectNode> customDatasets) {
}

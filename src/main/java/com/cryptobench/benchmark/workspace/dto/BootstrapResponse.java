package com.cryptobench.benchmark.workspace.dto;

import com.cryptobench.benchmark.auth.model.BenchmarkUser.UserResponse;
import com.cryptobench.benchmark.connection.model.AgentConnection.ConnectionResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * 前端启动和登录后刷新所使用的聚合响应，字段与 app.js 的 applyBootstrap 对应。
 * @param user 当前用户的对外资料，游客为 null
 * @param connections 当前用户的私有连接列表
 * @param reports 当前用户的报告列表
 * @param publicReports 所有用户已公开的报告
 * @param customDatasets 已发布的自定义测评集版本
 */
public record BootstrapResponse(UserResponse user, List<ConnectionResponse> connections,
                                List<ObjectNode> reports, List<ObjectNode> publicReports,
                                List<ObjectNode> customDatasets) {
}

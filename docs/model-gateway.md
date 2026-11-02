# Docker 模型网关

Agent 仍仅连接本次运行的 internal Docker 网络。模型网关另接一个出站网络，仅转发管理员配置的 chat completions 供应商与模型。现有 Agent 入站 TCP 代理保留原用途；模型网关不提供任意 URL 转发、CONNECT 或通用 HTTP 代理。

网关镜像在执行机器上预先构建；运行机器无需安装 Node：

```text
docker build -f docker/model-gateway.Dockerfile -t cryptobench-model-gateway:local docker
```

在管理员的 Spring 配置中添加以下配置。Provider 配置不包含 API Key：

```yaml
benchmark:
  gateway:
    enabled: true
    image: cryptobench-model-gateway:local
    providers:
      - id: example
        name: Example provider
        base-url: https://your-provider.example/v1
        models: [provider-model-id]
        price-model-ids:
          provider-model-id: model-id-in-price-table
```

`base-url` 是具体服务商的兼容 API 基础路径；此示例域名不是可用服务。生产供应商必须使用 HTTPS。测试仅允许网关进程自身 loopback HTTP。price-model-ids 可省略，省略时按供应商模型原标识在价格快照中查价。服务端使用管理员配置的计价映射，不能信任 Agent 自报计价模型。

也可设置 `CRYPTOBENCH_GATEWAY_ENABLED=true`，以及 `CRYPTOBENCH_GATEWAY_PROVIDERS` 为 JSON 数组，字段为 id、name、baseUrl、models、priceModelIds；格式见 `.env.example`。显式非空 YAML providers 列表优先于 JSON 环境配置。启用但没有供应商时启动会明确报错。Compose 会映射这些变量；源码启动应在 PowerShell/IDE 环境中设置，`start.cmd` 不自动加载 `.env`。此配置不接受 apiKey 字段。

`GET /api/agent/gateway` 返回启用状态、供应商名称、允许的模型和价格映射；不返回 Key。用户自带 Key 只在本次运行内存中使用，启动 sidecar 时通过 `docker start -ai` 的标准输入交给网关，既不写 Docker 环境变量和命令行，也不写配置文件或数据库。结束或取消时关闭网关并清理容器和网络。单机普通进程 Agent 的直连模型请求不经过这个网关，不可宣称已由网关计量。

Java 的 `ModelGatewayService.openRun(runId,sandbox,providerId,apiKey)` 返回 AutoCloseable Session；每次 Agent 任务调用前：

1. `beginTask(taskId,model,maxCalls,deadline)` 发放独立临时 token。标准预算 20 次，扩展预算 40 次，deadline 同任务超时。
2. 将返回的对象放进协议请求的 `modelGateway` 字段。Agent 使用它的 baseUrl、token、model 调用兼容接口。
3. Agent 返回后调用 `finishTask(taskId)`，撤销 token 并获取本任务完整用量。
4. `usageComplete=true` 才使用网关的 inputTokens/outputTokens；不要与 Agent 上报值相加。缺少供应商完整用量时费用未知，不将其推算为零。priceModelId 决定使用运行价格快照中的哪条单价。
5. finally 中关闭 Session，然后停止 Agent sandbox。

兼容接口为 `GET /v1/models` 和 `POST /v1/chat/completions`，支持普通 JSON 与 SSE。网关强制流式响应请求 include_usage，对同一调用的重复 usage 事件只取最终值，不重复累计。每任务最多两个并发调用，20/40 次总调用上限在发请求前计数，失败请求仍占一次；供应商失败或限流不会自动重试。网络中断、任务结束和到期会取消正在进行的请求。供应商没有返回完整用量时回传 usageComplete=false。

所有 Agent、平台代理、网关容器和网络都带 instance 与 run 标签。服务器默认按工作目录生成稳定 instance。相同目录同时运行多个服务器时分别设置 `CRYPTOBENCH_INSTANCE_ID` 或 `-Dcryptobench.instance-id=...`。启动回收仅处理该 instance 的资源；旧版本无 instance 标签的资源不会被新逻辑全局删除。

网关在运行内部启动，控制接口通过随机管理 token 保护，仅发布到 DockerProperties 指定的宿主机管理地址；Agent token 不能调用控制接口。网站运行在容器内的部署需继续按现有 sandbox 配置设置管理端口的绑定和 endpoint host。

自动验证：

```text
node tests/model-gateway.mjs
```

该测试仅启动本机假供应商，覆盖 JSON/SSE、重复 usage、模型白名单、临时 token、次数预算、deadline、取消、缺失用量和供应商错误，不调用真实模型。

可选的 Docker 生命周期测试需要本机 Docker 引擎和三个镜像（mock-agent、agent-proxy、model-gateway）。设置 `CRYPTOBENCH_GATEWAY_DOCKER_TEST=1` 后运行 `mvnw.cmd -Dtest=ModelGatewayDockerTest test`。测试使用随机 instance 和独立容器，假供应商在 gateway 容器 loopback 中运行，检查 Key 未进入 Docker 配置、Agent 网络隔离、跨 instance 回收隔离、真实转发及资源清理。默认普通 Java 回归会跳过该依赖 Docker 的测试。

```powershell
$env:CRYPTOBENCH_GATEWAY_DOCKER_TEST = '1'
.\start.cmd -Dtest=ModelGatewayDockerTest test
```

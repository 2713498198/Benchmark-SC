# 本地 Agent 测试

本地测试接口由 Spring Boot 提供，Agent 作为独立 HTTP 服务运行。Docker 用于正式隔离执行来源；开发联调可以直接使用本地进程或 Mock Agent。

## 启动 Mock Agent

在项目目录执行：

```powershell
npm run agent:mock
```

默认地址为 `http://127.0.0.1:8000`，也可以指定端口：

```powershell
npm run agent:mock -- --port 8100
```

## 启动平台

另开终端执行：

```powershell
.\start.cmd
```

登录后，在“开始测评”中填写：

- Agent 地址：`http://127.0.0.1:8000`
- 模型：`mock-model`
- 测评集：Mini-SPN 差分分析 · `1.0.0`
- 范围：快速体验
- 预算：标准预算

点击“检查本地 Agent”，然后开始真实测评。页面会轮询运行状态并显示服务端日志。

## API

### Docker 运行时状态

登录后可调用 `GET /api/agent/runtime` 查看 Docker Desktop 是否可用、允许的本地镜像和平台资源限制。浏览器不接触 Docker socket；服务端仅使用配置中的镜像白名单。

### 检查 Agent

```http
POST /api/agent/check
Content-Type: application/json

{"endpoint":"http://127.0.0.1:8000"}
```

Docker 沙箱检查使用：

```http
POST /api/agent/check
Content-Type: application/json

{"source":"docker","image":"cryptobench-mock-agent:local"}
```

`/capabilities` 可返回 `models`、`tools`、`capabilities` 和 `datasets`。其中
`datasets` 可以是 `{ "id": "mini-spn", "version": "1.0.0" }` 数组；一旦 Agent
明确返回该字段，服务端会在排队任务真正发送到 `/analyze` 前校验测评集版本。任务目录中声明的
`requiredTools` 与 `requiredCapabilities` 也会在同一阶段检查。Agent 省略这些可选字段时，服务端
保持对旧协议的兼容，但不会把客户端传入的成绩当作验证结果。

### 创建运行

```http
POST /api/runs
Content-Type: application/json

{
  "datasetId":"mini-spn",
  "version":"1.0.0",
  "scope":"sample",
  "budget":"standard",
  "source":"local",
  "endpoint":"http://127.0.0.1:8000",
  "model":"mock-model",
  "seed":42
}
```

响应为 `202 Accepted`，其中 `runId` 用于读取运行状态：

```http
GET /api/runs/{runId}
GET /api/runs
GET /api/runs/{runId}/logs?after=0
POST /api/runs/{runId}/cancel
POST /api/runs/{runId}/retry
```

## 评分规则

Mini-SPN 的隐藏验证材料位于服务端 classpath 的 `evaluation/mini-spn/1.0.0`，不在静态目录中。Agent 只能看到公开题目和输入，最终通过率由服务端 evaluator 计算。客户端提交的 `score`、`passed` 和 `cost` 不会覆盖服务端结果。

耗时由服务端围绕 `/analyze` 请求测量。费用由服务端价格表根据 Agent 返回的输入、输出 token 数计算；未知模型或缺少合法用量时显示为空，不会按零费用计入榜单。Agent 返回的费用字段只作为原始证据保存。

可信报告会同时保存 `usage` 汇总：`inputTokens`、`outputTokens`、`totalTokens`、`knownTasks` 和 `taskCount`；每个完整用量的任务结果也会保存同名的用量明细。只有所有任务都提供合法的输入和输出 token 时才生成总 Token 数，部分缺失时保留覆盖数量并将总量置空。

PK 请求需要在 `players` 中提供两个参赛方。服务端会为每个参赛方执行同一组任务，分别验证并生成 `competitors`、`winner` 和独立的时间、费用结果：

```json
{
  "kind": "pk",
  "players": [
    {"label":"A","agent":"Agent A","model":"model-a"},
    {"label":"B","agent":"Agent B","model":"model-b"}
  ]
}
```

## Docker 执行来源

真实 Docker 协议验收使用：

```powershell
npm.cmd run test:docker
```

该命令必须连接 Docker Desktop；没有 Docker 引擎时会失败并提示原因，不会把未执行当成通过。

创建运行时可以把 `source` 设为 `docker` 并提供 `image`。镜像需要在 `8000/tcp` 提供同一套 Agent 协议：

```json
{
  "source":"docker",
  "image":"cryptobench-agent:local"
}
```

平台会使用受限容器启动 Agent，设置 CPU、内存与 swap 上限、文件描述符和进程数、只读根文件系统、丢弃 Linux capabilities、禁止特权提升，并将容器放入每次运行独立的 internal network。任务结束、失败或取消后会停止容器并删除 network。Docker 不可用时运行会进入失败状态，不会回退为浏览器模拟成绩。

## 密码找回本地联调

忘记密码接口不会返回账号是否存在：

```powershell
$env:PASSWORD_RESET_OUTBOX = "D:/Benchmark-SC2/Benchmark-SC/var/mail/reset.jsonl"
```

启动平台后访问“忘记密码”，提交注册邮箱，再从 outbox 文件中读取一次性令牌或 reset URL。生产环境应设置 `PASSWORD_RESET_DELIVERY_URL` 指向邮件网关，不要把 outbox 文件放在公开目录。

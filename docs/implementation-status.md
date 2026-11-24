# CryptoBench-SC 实施进度

更新时间：2026-10-27

当前分支：`main`

验证基线：`main` 分支（提交记录以 Git 为准）

远程仓库：`https://github.com/2713498198/Benchmark-SC.git`

## 已完成

- 服务端运行调度：排队、运行、完成、失败、超时、取消、日志和重试。
- 真实 Agent 协议调用：`/health`、`/capabilities`、`/analyze`。
- Mini-SPN 服务端验证器和私有任务目录。
- 服务端重新计算通过率、耗时、Token 用量和费用；不采信客户端提交的费用或成绩。
- 单次运行和 PK 报告分别保存真实用量明细；缺失 Token 或费用不会按零处理。
- Docker 沙箱资源限制：CPU、内存、swap、PID、文件描述符、只读根文件系统、临时目录、capabilities、禁止新增权限和 internal Agent network；宿主机访问通过平台自有代理容器完成。
- Docker 代理网络兼容旧版引擎：优先使用 routed/no-masquerade 选项；不支持时回退到普通平台代理桥接网络，Agent 仍只连接 internal 网络。
- Docker Agent 启动增加 HTTP 就绪检查：端口监听后继续轮询 `/health`，避免代理端口已开放但 Agent 尚未完成启动时误报 `agent_unavailable`。
- Docker 容器、网络的运行结束清理及应用重启时遗留资源回收。
- 本地进程和 Docker 沙箱两种执行来源的前端入口。
- 连接管理、账号认证、管理员账号管理、报告公开和权限隔离。
- 首个管理员环境变量初始化规则已增加独立单元测试：缺配置不创建、密码不在 8～24 位范围内拒绝、已有邮箱不覆盖、首次创建只写入密码哈希。
- Compose、Dockerfile、本地网页版部署与下载内容。
- GitHub Actions 前端、后端和 Docker 沙箱验证工作流。
- 新增平台级 Docker E2E：真实启动 Spring Boot，提交 `/api/runs`，轮询运行状态并检查可信报告：
  `npm run test:docker:e2e`。
- Compose 部署冒烟测试：构建平台镜像、启动容器、检查 Docker 健康状态和 `/actuator/health`，结束后清理容器与网络：
  `npm run test:compose`。
- 通用模型价格表支持版本化配置；未知模型或无效用量保留空费用，并由 `ServerCostCalculatorTest` 验证自定义价格表覆盖和 token 计费。
- 管理员价格后台已接入版本草稿、启用、三位 ISO 货币校验和公开 `GET /api/pricing/active` 快照；启用后服务端费用计算器即时重新加载，API 权限和版本生命周期由 `PricingApiIntegrationTest` 覆盖。
- 应用启动会回收遗留 Docker 资源，并恢复未取消的 `queued/running` 运行；运行配置和任务状态会重新排队，取消的运行保持终态。凭证仍只保存在当前 worker 内存中，重启后需重新输入受保护 Agent 的凭证。
- 管理员邀请邮件现在明确返回 `gateway`、`outbox` 或 `unconfigured` 投递状态；邀请令牌仍只保存哈希，明文只进入已配置的网关或本地 outbox，不出现在 API 响应、日志和审计记录。
- 生产部署统一通过 `scripts/prod-compose.sh` 显式加载 `.env.prod`，备份、升级和健康检查使用同一入口。
- 正式榜单增加 `cryptobench-trust-v1`：只有平台模型网关确认供应商、模型和完整用量，且价格快照可计算全部费用的完整测评才进入正式排名；其他公开报告保留查看但 `rankingEligible=false`。
- V17 新增跨实例运行配额锁。运行创建按用户限制活跃数量和每日创建数，并限制全平台活跃运行数，超限返回 HTTP 429。
- 新增私有 evaluator catalog 校验器：检查版本、任务 ID、题干、公开文件路径和静态目录泄漏；只输出摘要与 SHA-256，不打印隐藏答案：`npm run validate:evaluator`。
- evaluator catalog 增加公开题库覆盖闸门：材料交付后可使用 `--public-catalog` 验证私有目录与公开题库 ID 完整一致，拒绝缺题或多余题；`npm run test:evaluator` 覆盖通过与缺题失败场景。
- 增加 V4 隐藏材料清单校验器：校验私有目录不在 `resources/static`、每道公开题都有 verifier 引用和 hidden/oracle/answer/reference 材料、文件真实 SHA-256、路径不越界以及公开目录哈希；材料未交付前只运行 `npm run test:v4-materials` 的夹具测试，不会将示例材料当作正式材料。
- 本地完整浏览器冒烟测试已在独立内存 H2 数据库和真实 Mock Agent 上通过，覆盖真实 Agent 接入、报告生成、结果核算、运行取消、管理员导入、移动端路由、敏感文件隔离和页面错误检查：`npm test`。

## 已验证

### Docker 真实端到端

Docker Desktop 已在本机启动并完成过真实验证；当前远程 Ubuntu 24.04 CI 也已通过完整 Docker 流程：

```powershell
npm run test:docker
npm run test:docker:e2e
```

第一条验证资源限制、Agent 协议和清理；第二条验证完整平台调用链：

1. 构建 Mock Agent 镜像。
2. 启动 Spring Boot 平台。
3. 通过 `source=docker` 创建真实运行。
4. 调用 Agent 的 `/health`、`/capabilities`、`/analyze`。
5. 由服务端生成 Mini-SPN 可信报告和真实 Token/费用统计。
6. 清理 Agent、代理容器和网络。

最近一次平台级运行完成了 8 道 Mini-SPN 任务，生成了服务端可信报告；测试结束后没有遗留带有 `com.cryptobench.owner=cryptobench` 标签的容器、网络或测试镜像。

## 当前待收尾项

### CryptoBench-SC V4.1 工作区验证器部署

V4.1 已导入 170 道公开题目和 75 个公开附件，`executionReady: true`，只保留 `cryptobench-sc-v4@4.1.0`。服务端私有目录当前按规则分布：

- 确定性声明式规则 105 道：`numeric` 33、`string` 31、`boolean` 1、`fields` 39、`piecewise` 1。
- `workspace-validator` 65 道：经上游逐题 Python 验证器打分。

声明式题目可直接评分；`workspace-validator` 题目需要先把服务端私有套件部署到非静态目录并配置 `CRYPTOBENCH_V4_SUITE_ROOT`，否则相关题目按未通过处理。部署步骤见 [V4 部署说明](v4-deployment.md)，套件准备使用 `scripts/prepare-v4-private-suite.py`。

## 重启后继续清单

1. 启动 Docker Desktop，确认：

   ```powershell
   docker version
   docker info
   ```

2. 进入项目目录：

   ```powershell
   cd D:\Benchmark-SC2\Benchmark-SC
   ```

3. 执行 Docker 资源和协议验证：

   ```powershell
   npm run test:docker
   ```

4. 执行平台真实 Docker 测评验证：

   ```powershell
   npm run test:docker:e2e
   ```

5. 重新运行完整检查：

   ```powershell
   npm run check
   mvn --batch-mode verify
   ```

6. 若 Docker E2E 失败，保留完整错误输出，优先检查 Agent/代理镜像构建、代理端口、internal network、Agent 三个接口和清理阶段。

7. Docker 与回归测试通过后，再验证本地网页版部署；V4.1 的 65 道 workspace-validator 题需按部署说明配置私有套件后才能完整评分。

## 当前已知验证结果

- `npm run check`：通过。
- `node --check tests/docker-platform-e2e.mjs`：通过。
- `npm run test:docker`：通过。
- `npm run test:docker:e2e`：通过，真实生成报告并完成资源清理。
- `npm run test:compose`：通过，Compose 容器健康检查 HTTP 200，测试结束后完成清理。
- `npm run test:docker-ui`：通过；在构建 `cryptobench-mock-agent:local` 与 `cryptobench-agent-proxy:local` 后，确认 Docker 引擎可用时前端入口启用，镜像不可用时安全禁用。
- `npm test`：通过，完整浏览器场景和移动端检查通过；测试使用独立内存 H2，避免污染本地持久化数据。
- `npm run check`：通过，108 个 JavaScript 模块和 11 个 Vue 模板。
- `mvn --batch-mode verify`：通过，35 项测试通过。
- `ServerCostCalculatorTest`：通过，验证版本化自定义模型价格和未知模型费用为空。
- `PricingApiIntegrationTest`：通过，验证游客价格快照、研究者 403、管理员创建/启用多模型价格表和非法价格拒绝。
- `ExecutionApiIntegrationTest`：通过，验证重启恢复会重新排队未取消运行、清理进行中任务结果并保留取消终态。
- `PasswordResetNotifierTest`：通过，验证管理员邀请写入本地 outbox、投递模式状态和未配置投递时不虚报成功。
- `npm run validate:evaluator`：通过，Mini-SPN 私有目录 8 项任务校验通过；不安全的 hidden/oracle/answer 公开路径会被拒绝。
- GitHub Actions run 43：通过 Ubuntu Docker sandbox、平台 Docker 执行和 Compose 健康检查；CI 使用兼容代理桥接网络，Agent 仍保持 internal 网络隔离。
- GitHub Actions run 45（提交 `5781d1b`）：通过 `Run Docker Agent sandbox E2E`、`Run platform Docker execution E2E` 和 `Run Docker Compose deployment smoke test`；验证 Docker Agent HTTP 就绪检查修复。


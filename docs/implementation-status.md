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
- Compose、Dockerfile、本地部署下载内容和 Windows `jpackage` 打包脚本。
- GitHub Actions 前端、后端和 Docker 沙箱验证工作流。
- 新增平台级 Docker E2E：真实启动 Spring Boot，提交 `/api/runs`，轮询运行状态并检查可信报告：
  `npm run test:docker:e2e`。
- Compose 部署冒烟测试：构建平台镜像、启动容器、检查 Docker 健康状态和 `/actuator/health`，结束后清理容器与网络：
  `npm run test:compose`。
- 通用模型价格表支持版本化配置；未知模型或无效用量保留空费用，并由 `ServerCostCalculatorTest` 验证自定义价格表覆盖和 token 计费。
- 新增私有 evaluator catalog 校验器：检查版本、任务 ID、题干、公开文件路径和静态目录泄漏；只输出摘要与 SHA-256，不打印隐藏答案：`npm run validate:evaluator`。
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

### Windows 桌面包

Windows `app-image` 已在本机实际构建并启动验收通过，内置 Java 运行时和 `CryptoBench.exe` 均存在；健康检查返回 HTTP 200，测试结束后端口已释放：

```powershell
.\scripts\package-windows.ps1
```

启动验收命令：

```powershell
npm run test:desktop
```

EXE 安装器构建、安装、升级和卸载均已在本机完成验证：安装器使用当前用户目录，不要求管理员权限。

EXE 安装器构建已在本机完成：使用 WiX 3.14 官方 binaries ZIP（存放在本地忽略目录 `.tools/wix314`）后，
`.\scripts\package-windows.ps1 -Installer` 成功生成 `output/desktop/CryptoBench-1.0.0.exe`。
`tests/windows-installer.ps1` 已完成 1.0.0 安装、1.0.1 升级、卸载及注册表和可执行文件清理检查。
Windows CI 也包含同一生命周期验证。
CI 使用 `-AllowExternalOutput` 将临时产物写入 runner 临时目录；默认本地输出路径保护仍然保留。

生成的 `output/desktop` 属于本地产物，不提交到仓库。

## 当前阻塞项

### CryptoBench-SC V4 正式执行

V4 仍保持 `executionReady: false`。项目缺少真实隐藏答案、参考复放材料和正式服务端验证器。不能用公开题干推测答案，也不能用示例结果冒充正式成绩。

需要维护者提供并确认：

- 每个 V4 任务的隐藏验证材料。
- 验证器版本及评分规则。
- Agent 任务输入和输出的正式结构。
- 任务执行所需的能力、工具和预算约束。

材料到位后，先注册私有 V4 任务目录和 evaluator，运行完整测试，再把 V4 标记为可执行。

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

7. Docker 与回归测试通过后，再继续 Windows 包验证；V4 仍需等待隐藏验证材料。

## 当前已知验证结果

- `npm run check`：通过。
- `node --check tests/docker-platform-e2e.mjs`：通过。
- `npm run test:docker`：通过。
- `npm run test:docker:e2e`：通过，真实生成报告并完成资源清理。
- `npm run test:compose`：通过，Compose 容器健康检查 HTTP 200，测试结束后完成清理。
- `npm run test:docker-ui`：通过；在构建 `cryptobench-mock-agent:local` 与 `cryptobench-agent-proxy:local` 后，确认 Docker 引擎可用时前端入口启用，镜像不可用时安全禁用。
- `npm test`：通过，完整浏览器场景和移动端检查通过；测试使用独立内存 H2，避免污染本地持久化数据。
- `npm run check`：通过，104 个 JavaScript 模块和 11 个 Vue 模板。
- `mvn --batch-mode verify`：通过，26 项测试通过。
- `npm run test:desktop`：通过，Windows app-image 健康检查 HTTP 200，进程树已清理。
- `.\scripts\package-windows.ps1 -Installer`：通过，生成 Windows EXE 安装器。
- `tests/windows-installer.ps1`：通过，本机完成安装、升级和卸载生命周期验证。
- `ServerCostCalculatorTest`：通过，验证版本化自定义模型价格和未知模型费用为空。
- `npm run validate:evaluator`：通过，Mini-SPN 私有目录 8 项任务校验通过；不安全的 hidden/oracle/answer 公开路径会被拒绝。
- GitHub Actions run 43：通过 Ubuntu Docker sandbox、平台 Docker 执行、Compose 健康检查和 Windows 打包任务；CI 使用兼容代理桥接网络，Agent 仍保持 internal 网络隔离。
- GitHub Actions run 45（提交 `5781d1b`）：通过 `Run Docker Agent sandbox E2E`、`Run platform Docker execution E2E`、`Run Docker Compose deployment smoke test` 和 `windows-package`；验证 Docker Agent HTTP 就绪检查修复。


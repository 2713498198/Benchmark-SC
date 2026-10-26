# CryptoBench-SC 实施进度

更新时间：2026-10-26

当前分支：`main`

最近提交：`0a0c018 docs: record desktop smoke test usage`

远程仓库：`https://github.com/2713498198/Benchmark-SC.git`

## 已完成

- 服务端运行调度：排队、运行、完成、失败、超时、取消、日志和重试。
- 真实 Agent 协议调用：`/health`、`/capabilities`、`/analyze`。
- Mini-SPN 服务端验证器和私有任务目录。
- 服务端重新计算通过率、耗时、Token 用量和费用；不采信客户端提交的费用或成绩。
- 单次运行和 PK 报告分别保存真实用量明细；缺失 Token 或费用不会按零处理。
- Docker 沙箱资源限制：CPU、内存、swap、PID、文件描述符、只读根文件系统、临时目录、capabilities、禁止新增权限和 internal Agent network；宿主机访问通过平台自有代理容器完成。
- Docker 容器、网络的运行结束清理及应用重启时遗留资源回收。
- 本地进程和 Docker 沙箱两种执行来源的前端入口。
- 连接管理、账号认证、管理员账号管理、报告公开和权限隔离。
- Compose、Dockerfile、本地部署下载内容和 Windows `jpackage` 打包脚本。
- GitHub Actions 前端、后端和 Docker 沙箱验证工作流。
- 新增平台级 Docker E2E：真实启动 Spring Boot，提交 `/api/runs`，轮询运行状态并检查可信报告：
  `npm run test:docker:e2e`。

## 已验证

### Docker 真实端到端

Docker Desktop 已安装并启动。当前本机已通过以下两条真实验证：

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

当前仍未完成的是升级和卸载流程，以及需要 WiX Toolset 的 EXE 安装器构建。

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
- `npm run check`：通过，102 个 JavaScript 模块和 11 个 Vue 模板。
- `mvn --batch-mode verify`：通过，25 项测试通过。
- `npm run test:desktop`：通过，Windows app-image 健康检查 HTTP 200，进程树已清理。


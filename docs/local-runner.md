# 本地 Runner

本地 Runner 运行在用户自己的电脑上。网站只通过 Runner 发起的 HTTPS/HTTP 出站请求下发任务，因此用户不需要开放本地 Agent 端口，也不需要把 Docker socket 暴露给网站。

## 安装

登录网站后进入“本地 Runner”页面，先下载 Windows x64 便携包并解压。压缩包已经包含 Node.js，不需要安装 Node.js，也不需要克隆项目源码。

在解压后的 `CryptoBench-Runner` 目录打开 PowerShell，后续配对和运行都使用 `cryptobench-runner.cmd`。

## 工作流程

1. 登录网站并下载、解压 Runner 便携包。
2. 启动或配置本机 Agent。
3. 在“本地 Runner”页面生成十分钟有效的一次性配对码。
4. 在 `CryptoBench-Runner` 目录执行页面给出的配对命令。
5. Runner 将长 Token 的哈希注册到网站，明文 Token 只写入用户目录的配置文件。
6. 执行 `cryptobench-runner.cmd run`，Runner 主动轮询任务。
7. Runner 在本机调用 Agent 后回传脱敏结果；网站使用服务端隐藏验证器评分。

## 配对和运行

```powershell
.\cryptobench-runner.cmd pair --url https://benchmark.example.com --token <配对码> --agent-url http://127.0.0.1:8000 --model mock-model
.\cryptobench-runner.cmd doctor
.\cryptobench-runner.cmd run
```

查看便携包及其内置 Node 版本：

```powershell
.\cryptobench-runner.cmd version
```

Runner 的本机配置默认位于 `%USERPROFILE%\\.cryptobench\\runner.json`。不要把这个文件提交到 Git。Agent 访问凭证只保存在该文件或当前进程内存中，网站报告不会保存这些凭证。

## 源码运行

已经克隆项目并安装 Node.js 20+ 的开发者可以直接使用以下源码命令；Runner 页面只为便携包用户生成 `cryptobench-runner.cmd` 命令。

```powershell
node runner/src/cli.mjs pair --url http://127.0.0.1:4173 --token <配对码> --agent-url http://127.0.0.1:8000 --model mock-model
node runner/src/cli.mjs run
```

本地生成便携包：

```powershell
npm run build:runner-package
```

产物位于 `target/runner-package/`。源码启动默认使用该目录；Docker 镜像在构建阶段自动生成并打包。

## 协议

Runner API 使用短期任务租约和幂等回传：

- `GET /api/runners/package`：登录后读取便携包版本、平台、Node 版本、大小和 SHA-256。
- `GET /api/runners/download`：登录后下载 Windows x64 ZIP。
- `POST /api/runner/register`：消费一次性配对码并领取 Runner Token。
- `GET /api/runner/jobs/next`：领取一个任务；无任务时返回 `{ "job": null }`。
- `POST /api/runner/heartbeat`：更新 Runner 在线状态和公开能力。
- `POST /api/runner/jobs/{jobId}/result`：上传结果或错误。

网站不会信任 Runner 上传的 `score`、`passed` 或验证结果。Runner 只能提交 Agent 的任务响应，最终评分始终由网站服务端 evaluator 生成。

## 命令 Adapter

Codex、DeepSeek Harness 或其他桌面 Agent 通常不会监听 HTTP 端口，也没有可供网站访问的 IP。命令 Adapter 由 Runner 在用户电脑上直接启动该 Agent 的非交互 CLI，采集标准输出并转换为统一结果。

先根据实际安装方式编辑配置：

```powershell
Copy-Item runner/examples/codex.json runner/agent.json
node runner/src/cli.mjs pair `
  --url http://127.0.0.1:4173 `
  --token <配对码> `
  --agent-config runner/agent.json
node runner/src/cli.mjs run
```

包含多个 Agent 的配置使用：

```json
{
  "agents": [
    {
      "name": "Codex CLI",
      "model": "codex-cli",
      "version": "local",
      "adapter": "command",
      "command": "codex",
      "args": [
        "--sandbox",
        "workspace-write",
        "--ask-for-approval",
        "never",
        "exec",
        "--json",
        "--ephemeral",
        "--skip-git-repo-check",
        "-"
      ],
      "stdin": "prompt",
      "output": "jsonl",
      "timeoutSeconds": 300
    },
    {
      "name": "My Agent",
      "model": "my-agent",
      "version": "1.0.0",
      "adapter": "command",
      "command": "C:/Tools/my-agent.exe",
      "args": ["--non-interactive", "{prompt}"],
      "stdin": "none",
      "output": "json",
      "cwd": "C:/research",
      "timeoutSeconds": 300
    }
  ]
}
```

`args` 是参数数组，Runner 使用 `shell: false` 启动，不执行 Shell 拼接。它支持 `{prompt}`、`{taskId}`、`{runId}`、`{model}`、`{taskJson}` 和 `{requestJson}` 占位符。

`stdin` 可选择 `prompt`、`json` 或 `none`。`output` 可选择：

- `jsonl`：推荐。保留多轮消息、工具事件与 token 用量，最终答案从最后的 Agent 消息提取。
- `json`：整个 stdout 是一个对象，可包含 `artifact`、`events`、`trajectory`、`items` 和 `usage`。
- `text`：整个 stdout 作为最终答案，证据信息较少。
- `auto`：优先尝试 JSONL/JSON，失败时使用文本。

命令 Adapter 只接受本机配置中的可执行文件、参数和工作目录。网站任务不能指定本地命令。任务预算优先于 profile 的 `timeoutSeconds`，所有输出都有大小限制，Windows 超时会清理整个进程树。

没有配置 `cwd` 时，Runner 会为每个任务创建独立临时目录，并在完成后清理，避免 Agent 修改 Runner 或网站源码。需要访问固定本地项目或工具配置时，应显式填写经过确认的工作目录。

带附件或需要工作区验证的任务始终使用独立临时工作目录。Runner 会解码并校验
`task.files`，把附件写入 `attachments/`；任务结束后回收代码、日志、JSON、CSV 等文本产物，
作为 `artifact.files` 上传。Agent 可通过环境变量 `CRYPTOBENCH_WORKSPACE` 或提示词中的
`{workspace}` 获取工作区路径。

Windows 上可直接配置 `.exe`，也可通过 `node` 启动 JavaScript 入口。`.cmd` 和 `.bat` 包装器通过系统 `ComSpec` 运行；参数只能来自本机可信的 Agent 配置文件。

## V4 动态预算

CryptoBench-SC V4 不再统一使用固定的 300 秒或 600 秒。服务端根据题目公开元数据中的 `budget_class` 读取套件预算配置，并把基础执行时间和允许的续期时间相加，同时设置服务端等待、Runner 请求超时、命令进程超时和工作区验证器超时。

如果个别任务仍超时，系统会记录该任务失败并继续执行下一题，不会因为第一题超时而终止整个 170 题实验。

默认预算文件位于：

```text
private/v4-suite/configs/budget_profiles.json
```

可以通过 `CRYPTOBENCH_V4_BUDGET_PROFILES` 指定其他路径。Mini-SPN 和自定义题库继续使用原来的 300/600 秒联调预算。

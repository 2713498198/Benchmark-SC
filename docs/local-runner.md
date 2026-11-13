# 本地 Runner

本地 Runner 运行在用户自己的电脑上。网站只通过 Runner 发起的 HTTPS/HTTP 出站请求下发任务，因此用户不需要开放本地 Agent 端口，也不需要把 Docker socket 暴露给网站。

## 工作流程

1. 登录网站的“本地 Runner”页面，生成十分钟有效的一次性配对码。
2. 在项目根目录执行页面给出的 `node runner/src/cli.mjs pair` 命令。
3. Runner 将长 Token 的哈希注册到网站，明文 Token 只写入用户目录的配置文件。
4. 执行 `node runner/src/cli.mjs run`，Runner 主动轮询任务。
5. Runner 在本机调用 Agent 的 `POST /analyze`、启动命令 Adapter，或按配置启动临时 Docker Agent。
6. Runner 回传脱敏后的 Agent 结果；网站使用服务端隐藏验证器计算成绩和报告。

## 配对和运行

```powershell
node runner/src/cli.mjs pair --url http://127.0.0.1:4173 --token <配对码> --agent-url http://127.0.0.1:8000 --model mock-model
node runner/src/cli.mjs run
```

Docker Agent：

```powershell
node runner/src/cli.mjs pair --url http://127.0.0.1:4173 --token <配对码> --docker-image cryptobench-agent:local
```

Runner 的本机配置默认位于 `%USERPROFILE%\\.cryptobench\\runner.json`。不要把这个文件提交到 Git。Agent 访问凭证只保存在该文件或当前进程内存中，网站报告不会保存这些凭证。

## 协议

Runner API 使用短期任务租约和幂等回传：

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

Windows 上可直接配置 `.exe`，也可通过 `node` 启动 JavaScript 入口。`.cmd` 和 `.bat` 包装器通过系统 `ComSpec` 运行；参数只能来自本机可信的 Agent 配置文件。

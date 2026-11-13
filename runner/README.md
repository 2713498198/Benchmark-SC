# CryptoBench 本地 Runner

Runner 在用户本机主动连接网站，不需要把本地 Agent 端口暴露到公网。长 Token、Agent 访问凭证和 Docker 配置只保存在本机 Runner 配置文件中；网站只保存 Runner Token 的哈希和公开能力信息。

Runner 支持两种 Agent 接入方式：

- `http`：Agent 在本机监听 `/health`、`/capabilities` 和 `/analyze`。
- `command`：Runner 直接启动已安装的 Agent CLI，适合 Codex、DeepSeek Harness 或其他提供非交互命令的程序。

命令 Adapter 使用参数数组启动进程，不通过 Shell 拼接命令。网站不能向 Runner 下发可执行文件、任意参数或工作目录。

## 配对

登录网站后，在本地 Runner 页面创建一次性配对码，然后在项目根目录执行：

```powershell
node runner/src/cli.mjs pair --url http://127.0.0.1:4173 --token <配对码> --agent-url http://127.0.0.1:8000 --model mock-model
```

Agent 凭证可以通过 `--agent-token` 传入，也可以直接编辑本机配置文件。配对成功后，配置默认写入 `%USERPROFILE%\\.cryptobench\\runner.json`，不会进入 Git。

## 运行

```powershell
node runner/src/cli.mjs run
```

Runner 会轮询网站任务，在本机调用 Agent 的 `POST /analyze`，再上传 Agent 原始结果。网站服务端仍负责读取隐藏评分规则并生成最终成绩。

排查本机配置可以执行：

```powershell
node runner/src/cli.mjs doctor
```

该命令检查已保存的可执行文件和工作目录，不会连接网站或执行测评任务。

## 命令 Adapter

多个 Agent 和复杂参数推荐使用 JSON 配置。先把示例中的可执行文件和参数改成用户本机安装的 CLI：

```powershell
Copy-Item runner/examples/codex.json runner/agent.json
node runner/src/cli.mjs pair --url http://127.0.0.1:4173 --token <配对码> --agent-config runner/agent.json
node runner/src/cli.mjs run
```

也可以在一个配置文件中声明多个 Agent：

```powershell
node runner/src/cli.mjs pair --url http://127.0.0.1:4173 --token <配对码> --agent-config runner/examples/multi-agent.json
```

简单命令可以在配对时直接配置：

```powershell
node runner/src/cli.mjs pair `
  --url http://127.0.0.1:4173 `
  --token <配对码> `
  --adapter command `
  --agent-name "Codex CLI" `
  --model codex-cli `
  --command codex `
  --arg exec `
  --arg --json `
  --arg --skip-git-repo-check `
  --arg - `
  --stdin prompt `
  --output jsonl
```

命令配置字段：

| 字段 | 说明 |
| --- | --- |
| `command` | 可执行文件名称或绝对路径，不经过 Shell |
| `args` | 参数数组，支持 `{prompt}`、`{taskId}`、`{runId}`、`{model}`、`{taskJson}`、`{requestJson}` 占位符 |
| `stdin` | `prompt`、`json` 或 `none` |
| `output` | `jsonl`、`json`、`text` 或 `auto` |
| `cwd` | Agent 工作目录；相对路径按 Runner 项目目录解析。省略时每个任务使用独立临时目录，任务结束后清理 |
| `timeoutSeconds` | 单任务超时，任务预算存在时优先使用任务预算 |
| `env` | 只为本机 Agent 子进程增加的本地环境变量 |
| `promptTemplate` | 任务提示词模板 |
| `includeStderr` | 是否将截断并脱敏后的 stderr 写入证据 |

建议优先使用 JSONL 输出。Runner 会保存结构化事件、提取最终 `agent_message`、token 用量和错误信息；普通文本输出只能把全部 stdout 当作最终答案，证据区分能力较弱。

Windows 上首选 `.exe` 或通过 `node` 启动脚本；`.cmd`、`.bat` 包装器也受支持，但参数必须来自本机可信配置。

配对前 Runner 会检查工作目录和可执行文件是否存在。任务期间强制超时、限制 stdout/stderr 大小，并在 Windows 使用 `taskkill /T` 结束整个进程树。

## Docker Agent

配对时增加 `--docker-image`，Runner 会为每道任务创建临时容器，使用 CPU、内存、PID、只读文件系统、丢弃 Linux capabilities 和禁止特权提升等限制：

```powershell
node runner/src/cli.mjs pair --url http://127.0.0.1:4173 --token <配对码> --docker-image cryptobench-agent:local
```

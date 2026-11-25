# CryptoBench 用户智能体接入与本地测评说明

本文说明用户下载 CryptoBench-SC 题库后，如何把本地 HTTP Agent 或 CLI Agent 接入网站并完成测评。

## 1. 先了解题库

- 公开题库 ZIP 用于查看题目、附件和复现材料。
- 正式测评时不需要手动上传 ZIP。网站在运行时把单道题目和附件下发给本地 Runner。
- 测评时保持题库版本一致，当前版本为 `cryptobench-sc-v4` / `4.1.0`。
- 隐藏答案和评分器只在网站服务端，Agent 和 Runner 不应读取。

## 2. 开始前准备

- 安装 Node.js 20 或更高版本。
- 准备一个可正常登录的 CryptoBench 账号。
- 确认自己的 Agent 能在本机运行。
- 如果使用云端模型，确认模型 API 可用。
- Runner 必须能够访问 Benchmark 网站。

## 3. 选择接入方式

| Agent 情况 | 推荐方式 | 用户需要做什么 |
|---|---|---|
| 已提供 HTTP 服务 | 本地 HTTP Agent | 实现 `/analyze` 接口并启动服务 |
| 是 Python、Node 或其他命令行程序 | CLI Adapter | 配置启动命令，或增加一个简单包装脚本 |
| 只能通过桌面窗口点击 | 暂不能直接接入 | 先增加非交互 CLI 或 HTTP 接口 |

## 4. 方式一：本地 HTTP Agent

### 4.1 需要实现的接口

| 接口 | 是否必需 | 用途 |
|---|---|---|
| `GET /health` | 推荐 | 检查服务是否在线 |
| `GET /capabilities` | 推荐 | 声明模型、工具和题库版本 |
| `POST /analyze` | 必需 | 接收一道题并返回答案和证据 |

### 4.2 启动 Agent

假设服务监听以下地址：

```text
http://127.0.0.1:8000
```

启动后先手动检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

### 4.3 返回结果

`POST /analyze` 至少返回以下 JSON：

```json
{
  "status": "completed",
  "artifact": {
    "answer": 25
  },
  "evidence": [
    {
      "type": "solver_result",
      "content": "求解器输出和关键验证过程"
    }
  ]
}
```

注意事项：

- `status` 必须为 `completed`。
- `artifact.answer` 的类型必须符合题目中的 `answerFormat`。
- 数字题返回数字，例如 `25`，不要返回整段解释。
- 证据、代码和日志可以放在 `evidence` 或 `artifact.files` 中。

### 4.4 配对并启动 Runner

登录网站，进入“本地 Runner”，生成一次性配对码，然后执行：

```powershell
node runner/src/cli.mjs pair `
  --url https://你的Benchmark域名 `
  --token 页面上的配对码 `
  --agent-url http://127.0.0.1:8000 `
  --model research-crypto-agent

node runner/src/cli.mjs doctor
node runner/src/cli.mjs run
```

`run` 窗口需要一直保持运行。

## 5. 方式二：CLI Adapter

### 5.1 适用情况

Agent 没有 HTTP 服务，但支持非交互运行，例如：

```powershell
python C:/agent/main.py
```

如果原程序不能读取 Benchmark 任务，建议增加一个小型 `adapter.py`，由它调用原 Agent 并输出标准 JSON。

### 5.2 创建配置

```powershell
Copy-Item runner/examples/generic-cli-agent.json runner/agent.json
```

编辑 `runner/agent.json`：

```json
{
  "name": "我的密码分析智能体",
  "model": "my-agent",
  "version": "0.1.0",
  "adapter": "command",
  "command": "python",
  "args": ["C:/agent/adapter.py"],
  "stdin": "json",
  "output": "json",
  "timeoutSeconds": 300,
  "includeStderr": true
}
```

主要字段：

| 字段 | 说明 |
|---|---|
| `command` / `args` | Runner 在本机启动 Agent 的命令 |
| `stdin` | `prompt`、`json` 或 `none` |
| `output` | `text`、`json`、`jsonl` 或 `auto` |
| `cwd` | 可选固定工作目录；省略时每题使用临时目录 |
| `promptTemplate` | 可选自定义任务提示词 |
| `timeoutSeconds` | Agent 默认超时 |

### 5.3 Adapter 的最低要求

Adapter 应当：

1. 从 stdin 读取标准任务 JSON。
2. 提取 `task.question` 和附件。
3. 调用真正的 Agent。
4. 输出标准结果 JSON。

标准输出示例：

```json
{
  "status": "completed",
  "artifact": {
    "answer": 25
  },
  "evidence": [
    {
      "type": "agent_output",
      "content": "关键推导和工具结果"
    }
  ]
}
```

### 5.4 配对并启动 Runner

```powershell
node runner/src/cli.mjs pair `
  --url https://你的Benchmark域名 `
  --token 页面上的配对码 `
  --agent-config runner/agent.json

node runner/src/cli.mjs doctor
node runner/src/cli.mjs run
```

## 6. 在网站上开始测评

1. 登录网站，确认“本地 Runner”显示在线。
2. 进入“开始测评”，执行来源选择“本地 Runner”。
3. 点击“检查本地 Agent”。
4. 选择题库 `CryptoBench-SC V4`，版本 `4.1.0`。
5. 第一次使用标准预算、快速体验和随机种子 `42`。
6. 点击“开始真实测评”。
7. 在 Runner 终端查看实时执行过程，在网站查看最终报告。

第一次先跑最多 8 题。确认题目输入、答案提交、证据上传和评分都正常后，再运行完整题库。

## 7. 答案格式要求

| `answerFormat` | 正确示例 | 错误示例 |
|---|---|---|
| `number` | `25` | `"最终答案是 25"` |
| `string` | `"AES-128"` | 完整解释段落 |
| `boolean` | `true` | `"是"` |
| `object` | `{"min_active_sboxes":25}` | 自然语言表格 |
| `workspace` | 提交机器可读文件和验证证据 | 只有一句结论 |

即使结论正确，答案类型不正确也可能被判失败。

## 8. 常见问题

| 现象 | 处理方式 |
|---|---|
| Runner 不在线 | 保持 `node runner/src/cli.mjs run` 运行，确认网站地址和账号一致 |
| Runner Token 无效 | 登录同一账号，重新生成配对码并重新配对 |
| HTTP Agent 无法连接 | 先手动访问 `/health` 和 `/analyze` |
| CLI 无法启动 | 手动执行同一命令，再运行 `doctor` |
| 答案正确但失败 | 检查 `artifact.answer` 是否符合 `answerFormat` |
| 附件找不到 | 从 `CRYPTOBENCH_WORKSPACE` 或 `attachments/` 读取 |
| 任务超时 | 检查模型网络和工具耗时，必要时使用扩展预算 |

## 9. 安全与运行方式

- Runner Token 和 Agent 密钥只保存在本机。
- 网站不能向 Runner 下发任意本地命令。
- 当前 CLI Adapter 直接在用户电脑上启动进程，不会自动使用 Docker。
- 标准测评不是离线的。Runner 必须访问网站；使用云端模型时还必须访问模型 API。

## 10. 临时测试结果核验

```powershell
node runner/src/cli.mjs status
node runner/src/cli.mjs doctor
node runner/src/cli.mjs run
```

如果 `status`、`doctor` 和 `run` 均正常，同时网站显示 Runner 在线，即可开始第一次 8 题快速体验。
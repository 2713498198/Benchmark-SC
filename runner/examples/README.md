# CLI Agent 配置示例

- `codex.json`：使用 Codex 非交互 JSONL 事件流。
- `codex-deepseek.json`：使用 Codex CLI 的自定义 DeepSeek provider。
- `generic-cli-agent.json`：通用单 Agent 模板，需替换可执行文件、参数和输出格式。
- `multi-agent.json`：在同一个 Runner 中声明多个 CLI Agent。

复制并修改配置后先运行：

```powershell
node runner/src/cli.mjs pair `
  --url http://127.0.0.1:4173 `
  --token <配对码> `
  --agent-config runner/agent.json
node runner/src/cli.mjs doctor
node runner/src/cli.mjs run
```

配置中的 `command` 和 `args` 只在本机保存。可执行文件如果没有非交互参数，需要根据其官方文档填写；不要使用需要人工点击的交互模式。

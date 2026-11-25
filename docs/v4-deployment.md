# CryptoBench-SC V4.1 部署与 agent 协议

## 服务端私有套件

`workspace-validator` 题目（106–170）需要把学长包里的私有部分部署到服务器非静态目录。
用下面的脚本只拷贝验证器所需的 private 材料，绝不拷贝公开题干和附件：

```powershell
python scripts/prepare-v4-private-suite.py <release-root> <private-suite-dir>
```

然后配置：

```yaml
benchmark:
  v4:
    suite-root: <private-suite-dir>
    python: python3
    validator-timeout-seconds: 120
```

等价环境变量：`CRYPTOBENCH_V4_SUITE_ROOT`、`CRYPTOBENCH_V4_PYTHON`、
`CRYPTOBENCH_V4_VALIDATOR_TIMEOUT_SECONDS`。

生产 Compose 默认把宿主机 `CRYPTOBENCH_V4_SUITE_HOST_DIR`（默认
`./private/v4-suite`）只读挂载到 `/app/private/v4-suite`，并把
`CRYPTOBENCH_V4_SUITE_ROOT` 设为该目录。运行时镜像已经安装 Python 3。

## agent 工作区产物回传

`workspace-validator` 题目需要 agent 在响应里回传工作区文件，验证器才能读到代码、日志和
witness 并给过程证据分：

```json
{
  "status": "completed",
  "artifact": {
    "answer": "最终答案文本",
    "files": [
      {"path": "solve.py", "content": "# agent 写出的代码"},
      {"path": "log.txt", "content": "运行日志"}
    ]
  }
}
```

服务端只把这些文件写盘后交给受信任的验证器读取，绝不执行 agent 自己的代码。

服务端还会把响应中的 `evidence` 写入 `logs/agent_stdout.txt`，并把
`logs/`、`events.jsonl`、`trajectory.jsonl` 类产物镜像到原 harness 路径，兼容上游验证器。

## 公开附件交付

运行请求的 `task` 同时保留 `publicFiles` 兼容字段，并新增 `files` 数组。每个文件包含
工作区相对路径、Base64 内容、大小、SHA-256 和媒体类型：

```json
{
  "files": [
    {
      "path": "attachments/input.json",
      "url": "data/cryptobench-sc-v4/4.1.0/tasks/.../public/attachments/input.json",
      "encoding": "base64",
      "bytes": 123,
      "sha256": "...",
      "mediaType": "application/json",
      "content": "..."
    }
  ]
}
```

Runner 的 CLI Adapter 会先校验并写入 `attachments/`，任务结束后回收工作区中的文本代码、
日志、JSON 和 CSV。HTTP Agent 与 Docker Agent 必须自行把 `task.files` 写入工作区。

## 打分覆盖

当前私有目录 `evaluation/cryptobench-sc-v4/4.1.0/tasks.json` 的 170 题分布：

- `numeric` / `string` / `boolean`：65 题，确定性声明式规则。
- `fields`：39 题，多字段确定性规则，agent 按 `input.answerFormat` 返回结构化 JSON。
- `workspace-validator`：65 题，经上游逐题 Python 验证器打分。
- `dynamic`：0 题。

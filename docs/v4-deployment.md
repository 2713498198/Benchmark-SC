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

## 打分覆盖

当前私有目录 `evaluation/cryptobench-sc-v4/4.1.0/tasks.json` 的 170 题分布：

- `numeric` / `string` / `boolean`：65 题，确定性声明式规则。
- `fields`：39 题，多字段确定性规则，agent 按 `input.answerFormat` 返回结构化 JSON。
- `workspace-validator`：65 题，经上游逐题 Python 验证器打分。
- `dynamic`：1 题（47），需人工确认分段公式的结构化答案。

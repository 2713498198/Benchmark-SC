# CryptoBench 离线 Docker 测评

离线模式只使用用户本机磁盘上的题库、评测器镜像和 Agent 镜像。它不访问 Benchmark 网站，不会领取服务器任务，也不会上传过程或报告。

## 公平性边界

离线包内的题库、预期答案和验证器位于用户自己的电脑。具备管理员权限和镜像解包能力的用户仍可能读取评分材料。因此离线模式可以统一运行环境、固定版本并阻止无意联网，但不能像线上服务端评分那样提供不可篡改的防作弊保证。

## 统一运行环境

所有 Agent 必须使用同一基础镜像构建的 Docker 镜像。基础镜像固定 Node、Python、求解器版本，并由 Runner 以相同 CPU、内存和进程限制启动。基础镜像带有 `org.cryptobench.runtime` 标签，离线 Runner 会在运行前检查该标签。Agent 应实现非交互入口：从标准输入读取题目，向标准输出写入文本、JSON 或 JSONL。

构建基础镜像：

```powershell
docker build -f docker/offline-agent-base.Dockerfile -t cryptobench-agent-runtime:4.2.0 .
```

为一个 Agent 创建镜像：

```dockerfile
FROM cryptobench-agent-runtime:4.2.0
USER root
COPY --chown=agent:agent ./my-agent /opt/my-agent
RUN chmod +x /opt/my-agent
USER agent
ENTRYPOINT ["/opt/my-agent"]
```

```powershell
docker build -t cryptobench-agent-myagent:4.2.0 .
docker image inspect cryptobench-agent-myagent:4.2.0
```

只有明确添加 `--allow-nonstandard-runtime` 时才允许运行未继承标准基础镜像的 Agent。该选项会降低不同 Agent 之间的可比性。

Codex、Claude Code、DeepSeek Harness 也采用相同方式：先把固定版本的 CLI 安装进镜像，再多显式配置非交互参数并固定版本，不能依赖用户宿主机上碰巧安装的版本。如果需要云端模型推理，必须把 `network` 设为 `bridge`，此时只能称为“本地执行、本地评分”，不能称为完整离线。

离线 Runner 默认强制使用 `2 CPU`、`2 GiB` 内存和 `256 PID`，并要求 `network: none`。只有显式添加 `--allow-network` 才允许连接云端模型，该运行会在报告中标记为非完整离线。

## 构建本地评测器镜像

将私有评测套件复制到独立构建上下文，不要把隐藏答案放进静态下载目录：

```text
offline-judge/
├─ Dockerfile
└─ suite/
```

```powershell
docker build -f docker/offline-judge.Dockerfile -t cryptobench-offline-judge:4.2.0 .
```

镜像包含 `suite/scripts/grade_submission.py` 或 `suite/scripts/run_task_validator.py`。

## 配置 Agent

复制 `runner/examples/offline-agent.json` 并修改镜像名、入口命令和输出格式。

关键字段：

| 字段 | 说明 |
|---|---|
| `image` | 用户构建并固定版本的 Docker 镜像 |
| `command` | 镜像内可执行命令；留空时使用镜像 ENTRYPOINT |
| `args` | 容器内参数数组 |
| `stdin` | `prompt`、`json` 或 `none` |
| `output` | `text`、`json`、`jsonl` 或 `auto` |
| `network` | 离线测评使用 `none` |
| `cpus` / `memory` / `pidsLimit` | 固定资源预算 |
| `env` | 通过环境变量传入的非持久凭据 |

## 运行

管理员构建网站发布的离线 ZIP：

```powershell
python scripts/build-offline-package.py `
  --tasks src/main/resources/evaluation/cryptobench-sc-v4/4.2.0/tasks.json `
  --public-root src/main/resources/static/data/cryptobench-sc-v4/4.2.0 `
  --suite private/cryptobench-sc-v4/170-20261112 `
  --output-dir target/offline-package
```

不传 `--suite` 时可以生成公开题库与 Agent 环境包，但工作区验证题无法在本地评分。

离线包解压后，在根目录执行：

```powershell
.\cryptobench-runner.cmd offline `
  --tasks .\tasks.json `
  --public-root .\public `
  --agent-config .\agent.json `
  --judge-image cryptobench-offline-judge:4.2.0 `
  --scope sample `
  --limit 8 `
  --seed 42 `
  --output .\offline-results
```

完整题库：

```powershell
.\cryptobench-runner.cmd offline `
  --tasks .\tasks.json `
  --public-root .\public `
  --agent-config .\agent.json `
  --judge-image cryptobench-offline-judge:4.2.0 `
  --scope full
```

报告写入：

```text
offline-results/<runId>/report.json
offline-results/<runId>/report.md
```

工作区默认在任务结束后删除。调试时增加 `--keep-workspace`，但最终公开发布前应删除。

## 容器隔离

Runner 使用以下限制：

- 默认 `--network none`
- 每次运行使用独立临时工作区
- `--cpus`、`--memory`、`--pids-limit`
- 根文件系统只读
- `/tmp` 使用受限 tmpfs
- 删除全部 Linux capabilities
- `no-new-privileges`
- 任务结束后自动删除容器

## 支持用户自研 Agent

用户只需提供 Docker 镜像。镜像内的 Agent 可以采用任意语言和框架，只要其非交互入口能从标准输入读取任务，并向标准输出写出最终答案或标准响应 JSON。无需修改 Runner 或评测器。

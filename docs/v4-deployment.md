# CryptoBench-SC V4.2 部署与 agent 协议

## 服务端私有套件

V4.2 的全部 170 道题统一通过发布版 judge 评分。公开题面、附件、隐藏 Oracle 和验证器必须先由导入器校验并拆分：

```powershell
python scripts/import-v4-release.py `
  --public-zip <CryptoBench-public.zip> `
  --judge-zip <CryptoBench-judge.zip>
```

导入器会生成 `cryptobench-sc-v4@4.2.0` 公开目录和 `private/v4-suite` 私有评分目录。部署到其他服务器时只能复制 `private/v4-suite` 到非静态私有目录，不能把该目录放入 `src/main/resources/static` 或镜像公开下载路径。

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
`CRYPTOBENCH_V4_SUITE_ROOT` 设为该目录。运行时镜像安装了 Python 3 和
`python-sat==1.9.dev25`；第 165 题使用该版本中的 CaDiCaL 后端。

从源码直接启动 Windows 版本时，先安装隔离的判题依赖：

```powershell
.\setup-v4-judge.cmd
```

`start.cmd` 会自动使用 `.tools/v4-judge-venv` 中的 Python。显式设置
`CRYPTOBENCH_V4_PYTHON` 时以显式值为准。依赖锁定在
`requirements-v4-judge.txt`，Docker 构建使用同一文件。

## 对外发布门禁

技术就绪检查：

```powershell
npm.cmd run check:v4-readiness
```

该命令检查 `python-sat`/CaDiCaL、571 个私有 judge 文件、第 165 题可执行、
共享 judge、7 个代表性专用验证器，以及 170 题公开发布合同。检查同时确认
170 题来源映射齐全，并核对 148–151 的论文来源与私有权利说明。

对外发布还必须取得权利人的书面或可审计确认。`private/v4-suite/RIGHTS.md`
明确说明上游未提供统一开放许可，因此署名记录本身不能替代发布授权。复制
`docs/v4-publish-approval.example.json` 到
`private/v4-publish-approval.json`，由权利人填写审批人、权利范围、依据和日期，
然后运行：

```powershell
npm.cmd run check:v4-publish
```

只有检查返回 `publicationReady: true` 后才能切换对外发布状态。审批文件属于私有
材料，不能放入静态资源目录或公开下载包。

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
      "url": "data/cryptobench-sc-v4/4.2.0/tasks/.../public/attachments/input.json",
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

当前私有目录 `evaluation/cryptobench-sc-v4/4.2.0/tasks.json` 的 170 题分布：

- 共享 judge 路由：103 题，使用 `grade_submission.py` 和 `score_run_v3.py`。
- 专用验证器路由：67 题，使用 `validation_routes.json` 指定的实现。
- 自动检查题：023、057、110、121、129、130、149、170。
- 121、129、130、149 只验证固定实例记录，标记为不进入完成率分母。

运行服务端只执行受信任的 judge 代码和验证器，不执行 Agent 提交的代码。无法启动 Python、
缺少私有套件或验证器依赖时会记录为失败，不会退化为前端猜测分数。

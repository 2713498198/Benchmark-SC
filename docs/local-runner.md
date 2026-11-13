# 本地 Runner

本地 Runner 运行在用户自己的电脑上。网站只通过 Runner 发起的 HTTPS/HTTP 出站请求下发任务，因此用户不需要开放本地 Agent 端口，也不需要把 Docker socket 暴露给网站。

## 工作流程

1. 登录网站的“本地 Runner”页面，生成十分钟有效的一次性配对码。
2. 在项目根目录执行页面给出的 `node runner/src/cli.mjs pair` 命令。
3. Runner 将长 Token 的哈希注册到网站，明文 Token 只写入用户目录的配置文件。
4. 执行 `node runner/src/cli.mjs run`，Runner 主动轮询任务。
5. Runner 在本机调用 Agent 的 `POST /analyze`，或按配置启动临时 Docker Agent。
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

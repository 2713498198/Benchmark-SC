# CryptoBench 本地 Runner

Runner 在用户本机主动连接网站，不需要把本地 Agent 端口暴露到公网。长 Token、Agent 访问凭证和 Docker 配置只保存在本机 Runner 配置文件中；网站只保存 Runner Token 的哈希和公开能力信息。

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

## Docker Agent

配对时增加 `--docker-image`，Runner 会为每道任务创建临时容器，使用 CPU、内存、PID、只读文件系统、丢弃 Linux capabilities 和禁止特权提升等限制：

```powershell
node runner/src/cli.mjs pair --url http://127.0.0.1:4173 --token <配对码> --docker-image cryptobench-agent:local
```

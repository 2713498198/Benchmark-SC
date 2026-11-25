# CryptoBench Runner

这是 Windows x64 便携版 Runner。压缩包已经包含 Node.js，不需要安装 Node.js，也不需要下载项目源码。

## 使用前准备

1. 启动或配置本机 Agent。
2. 登录 CryptoBench 网站并进入“本地 Runner”页面。
3. 在该页面生成一次性配对码。

## 配对

在解压后的 `CryptoBench-Runner` 目录打开 PowerShell。

HTTP Agent：

```powershell
.\cryptobench-runner.cmd pair `
  --url <网站地址> `
  --token <配对码> `
  --agent-url http://127.0.0.1:8000 `
  --model <模型名>
```

命令 Agent：

```powershell
.\cryptobench-runner.cmd pair `
  --url <网站地址> `
  --token <配对码> `
  --agent-config .\examples\codex.json
```

使用前复制并修改 `examples` 中的配置。命令参数只保存在本机配置中。

## 检查配置

```powershell
.\cryptobench-runner.cmd doctor
```

## 运行

```powershell
.\cryptobench-runner.cmd run
```

保持该窗口开启，然后回到测评页选择“本地 Runner”。

本机配置默认写入 `%USERPROFILE%\.cryptobench\runner.json`。不要分享该文件，其中包含 Runner Token 和本机 Agent 配置。

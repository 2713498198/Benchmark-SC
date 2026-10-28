# CryptoBench Windows 安装与启动

## 安装器

下载 Release 中的 `CryptoBench-x.y.z.exe`，双击运行。安装器支持选择安装目录，默认按当前 Windows 用户安装，不需要管理员权限。安装完成后可以从开始菜单或桌面快捷方式启动 CryptoBench。

启动器会运行本地服务，并在服务就绪后打开：

```text
http://127.0.0.1:4173
```

安装器包含 Java 21 运行时，使用安装包不需要另行安装 Java 或 Maven。Docker Desktop 只在选择 Docker Agent 沙箱执行来源时需要。

## 便携版

下载 `CryptoBench-vx.y.z-portable.zip`，解压到有写入权限的目录，运行其中的 `CryptoBench\CryptoBench.exe`。便携版不会写入安装注册表，也不会创建快捷方式。

## 数据与升级

本地账号、报告和 H2 数据库位于应用工作目录下的 `var\data`。升级时请继续选择原安装目录，以保留已有数据。卸载程序不会把用户数据作为安装文件删除；卸载前仍建议备份 `var\data`。

## 校验下载文件

Release 同时提供 `SHA256SUMS.csv` 和 `release-manifest.json`。在 PowerShell 中运行：

```powershell
Get-FileHash .\CryptoBench-x.y.z.exe -Algorithm SHA256
```

将输出的 Hash 与 `SHA256SUMS.csv` 中对应文件的值比较。

## 启动失败排查

如果浏览器没有自动打开，请手动访问 `http://127.0.0.1:4173`。如果端口被占用，先关闭其他 CryptoBench 实例或占用 4173 端口的进程。Docker Agent 执行失败时，确认 Docker Desktop 已启动，并在本地部署页面检查 Docker 状态。

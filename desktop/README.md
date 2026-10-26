# Windows 桌面包

桌面包是当前 Spring Boot 网站的本地启动器，启动后会在浏览器打开 `http://127.0.0.1:4173`。它不会改变服务端认证、数据库或 Agent 执行逻辑。

在 Windows PowerShell 中运行：

```powershell
.\scripts\package-windows.ps1
```

默认生成无需安装器的 `app-image`，输出在 `output\desktop\CryptoBench`。如果已经安装 WiX Toolset，可以生成 EXE 安装器：

```powershell
.\scripts\package-windows.ps1 -Installer
```

脚本会先执行 Maven 打包，并要求 JDK 21 自带的 `jpackage`。当前仓库不提交生成的桌面产物；发布前需在 Windows 机器上运行脚本并做启动、升级和卸载验证。

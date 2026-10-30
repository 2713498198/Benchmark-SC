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

运行安装器时会显示安装目录选择页。安装器默认使用当前 Windows 用户范围安装，
不需要管理员权限；可在该页面改为其他有写入权限的本地目录。
安装器还会创建开始菜单和桌面快捷方式；便携 ZIP 不创建快捷方式，也不写入卸载注册表。

Release 资产包括安装器、便携 ZIP、`SHA256SUMS.csv`、`release-manifest.json` 和
[`docs/INSTALL-WINDOWS.md`](../docs/INSTALL-WINDOWS.md)。升级时请继续使用原安装目录，
以保留该目录下的本地数据库和报告。

构建后可运行桌面包健康检查：

```powershell
npm run test:desktop
```

脚本会复用源码启动器准备 JDK 21，通过项目 Maven Wrapper 打包，再使用 JDK 自带的 `jpackage`。生成的安装器和便携包包含 Java，最终用户无需安装 JDK、Maven 或 Node.js。当前仓库不提交生成产物；发布前需在 Windows 机器上做启动、升级和卸载验证。下载、代理和手动安装说明见 [启动指南](../docs/SETUP.md)。

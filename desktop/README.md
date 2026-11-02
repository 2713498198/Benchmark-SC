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
npm run test:desktop:contents
npm run test:desktop
```

打包使用独立临时输入目录，只复制主应用 JAR。`PropertiesLauncher` 加载桌面入口，服务就绪后使用实际绑定端口打开系统浏览器；默认端口为 4173。无法打开浏览器时会记录可手动访问的地址，服务继续运行。普通 `java -jar` 启动入口不变。

自动验证包括桌面入口、实际随机端口、就绪后调用浏览器适配器一次、浏览器失败后服务仍可访问，以及无效 `JAVA_HOME` 下使用内置运行时。内容检查要求应用载荷中只有主 JAR 和 jpackage 元数据，排除构建目录中的测试类、日志、数据库和 outbox。CI 还验证 EXE 安装、升级、卸载、H2 账号在升级后仍可登录，以及用户文件保留。

安装器使用 jpackage 的 `CryptoBench-post-image.wsf` 构建钩子，移除默认 WiX 配置中的递归目录清理，并在维护操作中恢复用户选择的安装目录，使 MSI 能正常删除该目录下的安装文件。钩子在 WiX 结构变化时拒绝继续构建，防止升级或卸载误删用户创建的 `var`。此行为由两版安装器的完整生命周期测试验证。

发布前仍需在可交互的 Windows 桌面上双击快捷方式，确认默认浏览器实际显示主页。无界面自动化不能代替这一人工检查；可用安装目录和普通 JAR 的启动日志排查问题。完整统计口径见 [报告耗时与分类成绩](../docs/report-metrics.md)。

脚本会复用源码启动器准备 JDK 21，通过项目 Maven Wrapper 打包，再使用 JDK 自带的 `jpackage`。生成的安装器和便携包包含 Java，最终用户无需安装 JDK、Maven 或 Node.js。当前仓库不提交生成产物；发布前需在 Windows 机器上做启动、升级和卸载验证。下载、代理和手动安装说明见 [启动指南](../docs/SETUP.md)。

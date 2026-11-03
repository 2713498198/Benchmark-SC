# Windows 桌面包开发与验证

Windows 包使用 jpackage 启动 Spring Boot 本地网站，专用 `desktop` profile 提供真实管理员初始化、单实例、托盘、正常退出和默认文件日志。普通 `java -jar` 与源码启动器的入口保持独立。

## 构建

```powershell
npm run package:windows
npm run package:windows:installer
```

默认输出 `output\desktop`。EXE 构建需要 WiX 3.14.1 的 `candle.exe`、`light.exe`；可将经过 SHA-256 校验的官方二进制放入 `.tools\wix314`。

构建先生成临时产物，经载荷白名单和文件哈希验证后再替换输出。已有 `var`、未知内容、链接路径或使用中的 app-image 会被拒绝覆盖；旧构建缺少载荷清单时也应更换输出目录。构建失败保留旧产物。

使用 `-AppVersion 1.0.1` 指定三段版本。`-Release` 要求仓库 `jdk-distributions.tsv` 指定的 Temurin 21 补丁版本和 Windows x64 架构。开发构建可使用其他完整 JDK 21，但元数据会记录实际版本。jlink 第一轮保留全部模块，移除调试信息、头文件和手册；`support/build-info.json` 记录裁剪前后体积。

同一版本先构建 app-image，再用 `-Installer -PreparedAppImage <目录>` 封装，可确保 EXE 与便携 ZIP 使用相同的应用载荷。最终发布目录不用于运行测试，测试使用隔离副本。

## 自动验证

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tests/windows-data-safety.ps1
node tests/windows-package-content.mjs output/desktop/CryptoBench
node tests/windows-app-image.mjs output/desktop/CryptoBench/CryptoBench.exe
```

桌面冒烟检查采用真实默认数据库、日志和密钥路径，在含中文和空格的副本目录运行，并从不同工作目录启动。仅动态选择测试端口、使用无界面模式和显式测试管理员；检查真实账号、私有报告、体验登录禁用、重复启动及正常退出，不将 taskkill 当作成功退出。

`tests/windows-installer.ps1` 支持单个安装器的安装/卸载验收和两版本升级验收。它拒绝触碰已有同名安装或默认目录，并实际通过开始菜单快捷方式启动，验证运行中维护拦截和用户数据保留。被拒绝的升级必须保留旧版本注册信息和原可执行文件，卸载前后比对数据库、密钥和配置哈希。测试保留 MSI 路径参数的内层引号，兼容 jpackage EXE 对空格参数的再次引用。`-LegacyInitial` 仅用于上一正式版本尚不支持桌面控制协议的情况；新版安装器也必须拒绝正在运行的旧版进程。历史进程测试可使用同步写入后的强制清理，新版必须正常退出。

本机已有正式安装时，可使用独立测试身份构建：

```powershell
.\scripts\package-windows.ps1 -ApplicationName CryptoBenchQA -UpgradeUuid 806246e3-cfb0-4d1e-ae06-9e496f0c73ab -OutputDir output\qa
.\scripts\package-windows.ps1 -Installer -ApplicationName CryptoBenchQA -UpgradeUuid 806246e3-cfb0-4d1e-ae06-9e496f0c73ab -PreparedAppImage output\qa\CryptoBenchQA -OutputDir output\qa-installer
.\tests\windows-installer.ps1 -ApplicationName CryptoBenchQA -InitialInstaller output\qa-installer\CryptoBenchQA-1.0.0.exe -InstallRoot "$env:TEMP\CryptoBench QA"
```

测试名称、升级 UUID、快捷方式组均与正式版本隔离。CI 在干净 Windows runner 上继续验证正式名称和固定升级 UUID。

## 发布与签名配置

Release checkout 后通过 `git rev-parse HEAD` 校验标签并记录实际提交。流水线验证最终 ZIP、最终 EXE，以及从上一正式 Release 安装器到候选包的升级；历史版本存在但下载或校验失败时不得跳过。没有历史正式版本时明确记录 `first-release`。PR 的 Windows CI 也会下载上一正式 EXE，使用其补丁号加一的未发布测试版本验证历史升级；此步骤不创建 Release 或标签。

签名使用 `scripts/sign-windows.ps1`，`-SigningMode Required` 要求 Windows SDK `signtool.exe` 在 PATH，证书及私钥已配置在执行账户的 CurrentUser 证书存储区。配置 `CRYPTOBENCH_SIGNING_THUMBPRINT` 选择证书，使用 SHA-256 和 RFC 3161 时间戳。

GitHub 发布环境：

- `CRYPTOBENCH_SIGNING_ENABLED=true`：要求签名；未配置时发布明确标注的未签名预发布测试包。
- `CRYPTOBENCH_SIGNING_THUMBPRINT` secret：签名证书指纹。私钥不得提交到仓库或写入日志。
- `WINDOWS_SIGNING_RUNNER` variable：已准备证书和 SDK 的 Windows runner 标签；未设置时使用 `windows-latest`，它默认没有签名证书。

先签启动器，再封装并签安装器，验证后压缩便携包、生成哈希及清单。证书提供和正式签名验收属于发布环境接入，不能仅凭脚本存在宣称完成。

WiX 构建钩子保留用户文件，并在维护及升级时恢复原安装目录；本地锁及进程检查阻止运行中维护，禁止依赖 Restart Manager 强制关闭数据库。旧版卸载安排在运行状态检查通过之后、安装事务之内，避免拒绝升级前已经移除旧版。钩子遇到未知 WiX 结构或升级顺序时拒绝继续构建。

## 人工验收

在有交互桌面的 Windows 会话可单独运行对话框测试：

```powershell
.\mvnw.cmd --batch-mode '-Dtest=DesktopUiTest' '-Dcryptobench.test.desktopUi=true' '-Djava.awt.headless=false' test
```

该测试仅操作自身 JVM 的窗口，验证首次设置取消、重新打开、错误后重试、密码字段清理及启动失败的日志目录按钮，截图写入 `target/desktop-ui-*.png`。它模拟管理员服务；真实账户逻辑另由服务测试和安装包集成测试验证，不替代最终包的完整人工验收。

完整源码桌面生命周期可在交互会话中单独验证：

```powershell
.\mvnw.cmd --batch-mode '-Dtest=DesktopLifecycleUiTest' '-Dcryptobench.test.desktopLifecycle=true' '-Djava.awt.headless=false' test
```

此测试启动真实桌面服务和本机 Agent，通过原生窗口创建真实管理员，打开系统默认浏览器，并在测评进行中验证拒绝无确认退出、取消退出、确认退出及重启后进度保留。仅使用临时数据库，证据写入 `target/desktop-lifecycle-ui-evidence.json`；测试会打开本机临时页面，不在普通 CI 中自动启用。

自动化不替代交互式桌面验收。发布前在 Windows 10/11 上记录：首次设置与取消重试、开始菜单和桌面快捷方式、默认浏览器主页、托盘菜单、关闭浏览器后后台状态、正常退出，以及端口冲突和目录不可写提示。签名证书未配置、Docker 引擎不可用或人工验收未完成时，应如实记录验证限制。

用户操作与完整备份步骤见 [Windows 安装说明](../docs/INSTALL-WINDOWS.md)。

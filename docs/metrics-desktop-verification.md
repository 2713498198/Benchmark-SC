# 耗时、分类成绩与 Windows 交付验证

2026-11-02 在当前工作区完成本地验证。本次修改未提交、未发布；保留了工作区已有的其他修改。

## 已通过

- `npm ci`、`npm run check`：90 个 JavaScript 模块语法检查和 16 个 Vue 模板编译通过。
- `npm run test:report-metrics`：累计与平均耗时、零值与缺失值、历史报告推导、不改写原始数据、PK 隔离、分类对齐、榜单排序、图表数值和 CSV 兼容通过。
- `.\start.cmd --batch-mode --offline verify`：128 项测试，126 项通过、2 项为依赖外部环境的按配置跳过项。包含桌面真实随机端口与就绪回调、浏览器失败后服务存活、按实际选题聚合、恢复后的最终计时与 PK API。
- `npm run test:workflow:mock`、`npm run test:run-state`：历史明细不足时显示未知、完整历史明细推导平均值与分类、历史 PK 汇总、原始 JSON 导出及运行恢复通过。
- `npm run test:workflow:integration`：独立 H2、测试管理员、测试邮件 outbox 和本地确定性 Agent。三道题、两个分类、失败答案、普通报告、双方成绩不同的 PK、公开榜单、图表提示、对比弹窗和 CSV/JSON 导出通过。
- `tests/leaderboard-chart-browser.mjs`：91 项图表浏览器检查通过，包括横轴切换、标签布局与交互。
- Windows app-image：主 JAR 白名单、构建残留隔离、内置 Java、无效系统 `JAVA_HOME`、含空格的程序路径、实际随机端口、无浏览器时的 URL 提示与 HTTP 200 通过。
- `tests/windows-installer.ps1`：两版 EXE 的自定义目录安装、快捷方式、升级、原 H2 账号登录、用户文件保留、卸载及快捷方式清理通过。测试数据库启用同步写入，避免测试强制终止进程时丢失刚写入的夹具。
- 普通 JAR 的主入口仍为 `JarLauncher` / `CryptoBenchApplication`；便携 ZIP 包含内置 Java 和经过白名单检查的应用载荷。

本地诊断文件位于 `target/metrics-desktop-verify.log`、`target/metrics-workflow.log`、`target/metrics-installer-lifecycle.log` 和 `output/metrics installer lifecycle/`。普通与 PK 报告截图位于 `tmp/verification/workflow-live/`。

生成的可检查产物为 `output/metrics-installer/CryptoBench-1.0.0.exe`、`output/metrics-installer-upgrade/CryptoBench-1.0.1.exe` 和 `output/metrics-desktop/CryptoBench-v1.0.0-portable.zip`。这些构建产物不提交到 Git。

## 验证边界

默认浏览器在可交互 Windows 桌面上实际弹出主页，仍需双击快捷方式人工确认。自动化已经验证桌面入口、服务就绪后恰好一次调用浏览器适配器，以及系统浏览器不可用时服务继续运行。

`tests/leaderboard-authority-browser.mjs` 在原有“360px 首屏至少两条记录”布局断言处失败。使用改动前的榜单、数据和图表代码重新验证，得到相同结果：首条记录顶部约 675px，800px 高度内仅一条完整记录。本次未改变该原有布局；诊断见 `target/metrics-leaderboard-diagnostic.log` 与 `target/metrics-leaderboard-baseline.log`。

GitHub Actions 已加入统计、隔离浏览器流程和桌面内容验证；本次未推送代码，因此没有新的远端 CI 运行结果。旧安装器升级的数据备份要求见 [Windows 安装说明](INSTALL-WINDOWS.md)。

# CryptoBench Windows 安装与使用

支持 Windows 10/11 x64。安装器和便携 ZIP 均包含 Java 21 运行时，无需另装 Java、Maven 或 Node.js。

使用与系统语言不同的安装路径（例如英文 Windows 上的中文目录）时，需要 Windows 10 1903 或更新版本；Windows 11 支持此功能，无需修改系统区域设置。

## 安装与首次使用

下载 `CryptoBench-x.y.z.exe` 后双击安装。默认按当前用户安装，可选择有写入权限的目录，无需管理员权限。开始菜单和桌面均提供快捷方式。

首次启动通过本机窗口创建真实管理员：输入姓名、真实邮箱和 8～24 位密码。邮箱不能使用 `admin@cryptobench.local` 或 `researcher@cryptobench.local`。已有邮箱不会被自动提升为管理员或覆盖密码；取消设置会退出，下一次启动可重试。

已有真实管理员时直接启动。旧体验账号及报告保留，但桌面版默认禁用免密码体验登录并撤销旧体验会话。普通用户仍可在网页注册。

服务就绪后打开 `http://127.0.0.1:4173`。再次双击会打开已有实例的页面，不启动第二个数据库服务。关闭浏览器后服务继续运行；使用系统托盘中的“退出”停止服务。托盘不可用时会提供控制窗口。存在未完成任务时，退出前会提示确认。

## 便携版

下载 `CryptoBench-vx.y.z-portable.zip`，完整解压至有写入权限的目录，再运行 `CryptoBench\CryptoBench.exe`。便携版不创建快捷方式或卸载注册表。移动便携版时先退出，并移动整个文件夹，包括 `var`。

## 数据、配置与日志

所有默认路径均以程序所在目录为基准，与启动快捷方式或命令行的工作目录无关：

| 路径 | 内容 |
| --- | --- |
| `var/data/cryptobench.mv.db` | 账号、任务与报告数据库 |
| `var/secrets/mail.key` | 邮件及验证码相关加密密钥 |
| `var/config/desktop.properties` | 用户桌面配置 |
| `var/logs/cryptobench.log` | 默认运行日志 |
| `var/run` | 临时实例锁与本机控制信息，不需恢复 |

日志按日期和大小滚动，保留 14 天、总量上限 200 MB。托盘可打开数据和日志目录。配置文件在重启后加载，例如用 `server.port=4180` 调整端口。桌面服务固定监听本机。

桌面版默认不配置邮件投递，也不将找回密码邮件写入开发用 outbox。需要邮件功能时在桌面配置中按邮件文档配置 SMTP 或邮件网关；界面显示就绪后才可使用找回密码和邀请邮件。请保护包含邮件凭据的配置文件，不要将其提交到源码仓库。

## 备份与恢复

先退出 CryptoBench，确认任务已经停止，再执行备份。必须保存整个持久化 `var`，不能只保存数据库：丢失 `var/secrets/mail.key` 会影响依赖旧密钥的邮件和验证码数据。

在安装目录打开 PowerShell，运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\support\desktop-data.ps1 -Action Backup -Archive D:\CryptoBenchBackups\backup-2026-11-03.zip
```

备份位置必须在安装目录外，文件名不能覆盖已有备份。工具记录版本和文件校验值，保留空目录，排除临时 `var/run`，并拒绝运行中备份。备份含账号数据和密钥，请妥善保管。

恢复前先退出应用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\support\desktop-data.ps1 -Action Restore -Archive D:\CryptoBenchBackups\backup-2026-11-03.zip
```

工具先检查备份格式、路径和文件校验，再在备份所在目录保存当前数据的独立备份，最后恢复。备份归档和恢复后的密钥限制为当前 Windows 用户访问；应使用日常运行 CryptoBench 的账户执行工具。不要将新版数据库直接交给旧版本程序；回退版本时应使用升级前备份。

## 升级与卸载

升级前退出应用并完成备份；安装新版时继续选择原目录。新安装器拒绝在应用运行期间升级或卸载，不强制关闭任务。卸载只移除安装文件、快捷方式及安装注册信息，保留 `var`。

早期安装包的旧卸载逻辑可能递归清理目录，无法由新版安装器直接修复。若来源版本没有明确的数据保留保证：先停机，将整个 `var` 复制到安装目录外，再升级；如果数据被旧卸载逻辑移除，在首次启动新版前将备份 `var` 放回原位置。不要在程序仍运行时复制 H2 数据库。

重新运行源码打包命令时，若目标 app-image 存在 `var` 或未知文件，构建会拒绝覆盖。应更换构建输出目录，不要为了打包删除自己的数据目录。

## 准备 Docker 沙箱

仅使用网站及外部 HTTP Agent 时不需要 Docker。使用 Docker Agent 沙箱时，需要安装并启动 Docker Desktop，切换至 Linux 容器模式。

安装包包含必要构建源文件，无需下载源码仓库。在安装目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\support\prepare-desktop-docker.ps1
```

首次准备需要联网获取基础镜像。工具构建与应用版本对应的代理镜像和示例 Agent，全部成功后更新 Docker 专用配置，并保留其他用户设置。失败时不启用沙箱，可检查网络或代理后重试。完成后重启 CryptoBench，在本地部署页面检查状态。

需要可选模型网关镜像时增加 `-IncludeGateway`。该参数仅准备镜像，提供商路由和网关启用仍需明确配置。

## 启动诊断

启动失败弹窗会显示诊断文件位置，并提供“打开日志目录”按钮；即使服务尚未启动，也能查看日志。

- 无法自动打开浏览器：手动访问日志或提示窗口中的地址，或使用托盘“打开主页”。
- 端口占用：退出其他实例，或在桌面配置中调整端口；程序不会静默切换到未知地址。
- 安装目录不可写：选择当前用户有写入权限的目录。启动器会尝试将诊断写入用户临时目录并显示位置。
- 数据库升级失败：保留数据库和升级前备份，根据启动日志排查，不要删除数据库重试。
- 无交互环境首次启动：预先设置 `CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL`、`CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME` 和 `CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD`；成功后移除初始化凭据。密码规则与窗口一致。

自动化可调用 `CryptoBench.exe --desktop-command=status`、`open` 或 `stop`。它们使用本机认证控制通道；有未完成任务时 `stop` 返回失败，应通过托盘确认退出。控制通道不属于网页 API，不监听外部网络。

## 下载校验与签名

Release 提供 `SHA256SUMS.csv` 和 `release-manifest.json`，清单记录真实构建提交、运行时、签名状态和升级测试基线。

```powershell
Get-FileHash .\CryptoBench-x.y.z.exe -Algorithm SHA256
Get-AuthenticodeSignature .\CryptoBench-x.y.z.exe
```

将 Hash 与校验文件中的对应项比较。未接入证书的构建标记为 `Unsigned test build` 并作为预发布提供；正式签名包要求启动器、安装器签名有效且具有时间戳。签名和时间戳完成后才生成最终下载哈希。

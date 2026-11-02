# CryptoBench

专用于比较智能体在密码分析任务中通过率、耗时与资源消耗的评测网站。项目采用 Java 21、Spring Boot 3.5.7 和 Vue 3，前后端由同一个 Spring Boot 进程提供服务。

榜单、图表和实验对比使用平均每题耗时，报告同时展示累计耗时，按实际选题分类列出通过数与通过率；PK 双方分别统计。历史报告保持原始数据，明细不足时显示“未知”。字段、CSV 兼容规则与测试命令见 [报告耗时与分类成绩](docs/report-metrics.md)。

首次阅读代码可参考 [项目阅读顺序与建议](docs/reading-guide.md)，按启动入口、请求链路、业务模块和前端题库逐步理解。

## 快速启动

根据使用方式选择入口，无需修改个人电脑路径：

| 使用方式 | 启动入口 | 需要准备 |
| --- | --- | --- |
| Windows 直接使用 | 下载本仓库 GitHub Releases 的 EXE 或便携 ZIP | 安装包内置 Java，无需 Maven、Node.js；见 [安装说明](docs/INSTALL-WINDOWS.md) |
| 拉取源码运行网站 | `docker compose up -d --build` | Docker + Compose v2，无需本机 Java 或预先构建 JAR |
| 修改源码或 IDE 调试 | Windows `start.cmd`；macOS/Linux `bash start.sh` | 脚本检查 JDK 21，缺少时自动下载并校验；Maven Wrapper 固定 Maven 3.9.11 |

在项目根目录双击 `start.cmd`，或运行：

```powershell
.\start.cmd
```

macOS/Linux 在项目根目录执行 `bash start.sh`。首次源码启动需要联网下载缺少的 JDK、Maven 和依赖；Linux/macOS 需要 Bash、curl、tar、unzip 和 SHA-256 工具。工具缓存位于 `.tools/`，依赖缓存在 `.mvn/repository/`，不会更改系统 Java。完整环境、代理、离线准备与排错说明见 [跨机器启动指南](docs/SETUP.md)。

浏览器访问 http://127.0.0.1:4173 。本地默认使用 H2 文件数据库，启动时自动创建表，无需额外安装数据库；数据保存在 `var/data/cryptobench.mv.db`，重启后保留。普通 Compose 仅启动网站，需要 Docker Agent 测评时使用 [sandbox 配置](docker/README.md)。

两种 Compose 均仅向本机发布端口，默认关闭免密码体验登录，sandbox 同样持久化账号、运行和报告。普通用户可注册；真实管理员初始化、临时开启体验和升级备份步骤见 [Docker 部署说明](docker/README.md)。`start.cmd` 的本地体验默认不变；不要让源码服务和 Compose 同时占用同一 H2 数据目录。

前端 Vue 运行时已随项目放在 `src/main/resources/static/vendor/vue/`，启动网站不需要 Node.js 或 npm。

## 在 IntelliJ IDEA 中运行

1. 使用 IDEA 打开项目根目录的 `pom.xml`，选择作为 Maven 项目加载；已有项目可在 Maven 面板点击“添加 Maven 项目”并选择该文件。
2. 先执行 `start.cmd -PrepareOnly`（macOS/Linux：`bash start.sh --prepare-only`），按输出的 `JAVA_HOME` 在 Project Structure 中设置 Java 21；Maven Runner JRE 选同一 JDK，Maven 使用项目 Wrapper。
3. 等待 Maven 同步完成，运行 `src/main/java/com/cryptobench/benchmark/CryptoBenchApplication.java` 的 `main` 方法。项目也提供了共享运行配置 `CryptoBench`。
4. 工作目录使用项目根目录。可在 Run Configuration 的 Environment variables 中设置 `PORT`、`SPRING_PROFILES_ACTIVE` 等参数。

默认 profile 是 `local`。不依赖 IDEA 的 Spring 专属插件，普通 Java Application 配置即可启动。

## 在 VS Code 中运行

安装 `Language Support for Java(TM) by Red Hat`（`redhat.java`）和 `Debugger for Java`（`vscjava.vscode-java-debug`），避免同时启用多个 Java 语言服务。打开项目根目录，在“运行和调试”中选择共享配置 `CryptoBench (Spring Boot)`，按 F5 调试或 Ctrl+F5 运行。

先运行上述准备工具命令，将输出的 JDK 路径配置到 VS Code **用户设置**的 `java.configuration.runtimes`，不要把个人路径提交到工作区。Java 语言服务优先使用扩展自带的运行时；它可能需要比 Java 21 更新的版本，项目编译和运行仍使用 JDK 21。IDE 的 Maven 缓存可以使用默认位置，无需复制个人缓存路径。

切换 JDK 或扩展后执行 `Developer: Reload Window`，等待 Maven 项目导入完成。若仍显示旧诊断，执行 `Java: Clean Java Language Server Workspace`。启动脚本设置的环境仅对启动进程生效，不会自动重配已经打开的 IDE。

## 打包部署

```powershell
.\start.cmd clean verify
```

生成的 `target/cryptobench-1.0.0-SNAPSHOT.jar` 包含前端、公开题库和后端。推荐通过上述 Docker 入口部署；如果直接执行 `java -jar target/cryptobench-1.0.0-SNAPSHOT.jar`，运行该命令的终端必须已配置 Java 21，构建脚本不会修改父终端环境。启动工作目录决定本地数据库相对路径，请固定工作目录；关闭程序后再备份 H2 数据文件。健康检查地址为 `/actuator/health`。

## MySQL 配置

`prod` profile 使用 MySQL 8 或以上版本。先在 MySQL 中创建数据库，并为应用创建具有该数据库建表、迁移和读写权限的独立账号：

```sql
CREATE DATABASE cryptobench CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

在 PowerShell 中设置环境变量后启动：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/cryptobench?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC'
$env:DB_USERNAME = 'cryptobench'
$env:DB_PASSWORD = '<应用数据库账号密码>'
java -jar target/cryptobench-1.0.0-SNAPSHOT.jar
```

Flyway 在启动时执行 `src/main/resources/db/migration/` 中的版本化迁移。后续变更应新增 `V2__...sql` 等迁移文件，不修改已经执行的迁移。

生产环境默认关闭体验账号，并使用 Secure Cookie，需通过 HTTPS 访问。服务器对外监听时设置 `SERVER_ADDRESS=0.0.0.0`，使用反向代理提供 HTTPS。仅在本机通过 HTTP 验证 MySQL 配置时，可临时设置 `SECURE_COOKIE=false`。

Compose 会读取 `.env` 做变量替换，并只向容器传入配置中明确映射的变量；可从 `.env.example` 复制需要的配置。直接运行 Spring Boot 不会自动加载 `.env`，请在系统环境、PowerShell 或 IDEA 运行配置中设置。修改 Compose 环境变量后需重新创建容器，`restart` 不会更新容器环境。

## 目录与开发约定

```text
pom.xml                                  Maven 构建与依赖
.mvn/maven.config                        项目级 Maven 配置
.run/CryptoBench.run.xml                 IDEA 共享启动配置
src/
  main/
    java/com/cryptobench/benchmark/
      CryptoBenchApplication.java        Spring Boot 启动入口
      auth/                              账号、会话与认证
      connection/                        智能体连接元数据
      report/                            报告存储、同步与公开
      dataset/                           自定义测评集版本发布
      workspace/                         工作台聚合接口
      common/                            异常处理与 JSON 工具
      config/                            安全、请求限制及配置
      migration/                         旧版 JSON 数据迁移
    resources/
      application.yml                    公共配置
      application-local.yml              本地 H2 配置
      application-prod.yml               MySQL 配置
      db/migration/                      Flyway 数据库迁移
      static/
        index.html                       Vue 页面入口
        js/app.js                        Vue 应用与路由
        js/vue/                          页面组件
        css/                             页面样式
        assets/                          图片、字体
        vendor/vue/                      Vue 浏览器运行时及许可证
        data/cryptobench-sc-v4/           公开题库和下载包
  test/java/                             Java 集成测试
  test/resources/                        隔离测试数据库配置
scripts/                                 题库导入与前端依赖同步
tests/                                  前端与端到端验收脚本
docs/archive/                           旧页面实现及历史设计说明
var/                                     本地运行数据，不提交 Git
target/                                 编译和打包产物，不提交 Git
```

代码按业务模块组织，各模块内部使用 `controller`、`service`、`repository`、`dto`、`model` 分层。Controller 处理 HTTP 和参数校验，Service 承担业务规则与事务，Repository 使用 Spring JDBC 访问数据库。类名采用大驼峰，包名采用小写，SQL 表名与字段使用下划线。

前端样式、页面布局、标题动画和 Vue 组件继续沿用。前端通过同源 `/api` 调用后端，页面使用 hash 路由，不需要额外配置前端开发服务器。旧静态服务器 `server.mjs` 已由 Spring Boot 替换。

## 业务接口与安全

已迁移注册、登录、退出、工作台、连接管理、私有报告、报告同步与公开、自定义测评集发布。会话使用 HttpOnly / SameSite Cookie，数据库只保存会话令牌摘要；新密码使用 PBKDF2。私有资源按用户隔离，发布报告后不可修改，自定义测评集的同一 ID 与版本不可覆盖。

修改数据的请求需要 CSRF 校验：先调用 `GET /api/auth/csrf`，携带返回的 Cookie，并将响应中的 `token` 作为 `headerName` 对应的请求头。现有前端已处理该流程。

主要接口：

| 接口 | 用途 |
| --- | --- |
| `GET /api/auth/me` | 当前账号 |
| `GET /api/auth/csrf` | CSRF 令牌 |
| `POST /api/auth/register`、`login`、`logout` | 注册、登录、退出 |
| `POST /api/auth/demo` | 体验账号：源码 local 默认启用，Compose 默认关闭、可显式开启 |
| `GET /api/bootstrap` | 工作台与公开数据 |
| `GET/POST /api/connections` | 连接列表和新增 |
| `DELETE /api/connections/{id}` | 删除自己的连接 |
| `GET/POST /api/reports` | 报告列表和保存 |
| `GET /api/reports/{id}` | 自己的报告或已公开报告 |
| `GET /api/reports/public` | 所有公开报告，登录身份不影响返回范围；供首页成本效果图自动刷新 |
| `POST /api/reports/{id}/sync`、`publish` | 同步、公开自己的报告 |
| `POST /api/datasets/custom` | 管理员发布测评集版本 |
| `GET /api/admin/users`、`GET /api/admin/audit` | 管理员查看账号与操作审计 |
| `POST /api/admin/invitations` | 管理员发出一次性管理员邀请（邮件或本地 outbox） |
| `PATCH /api/admin/users/{id}/role`、`status` | 管理员变更角色、启用或停用账号 |
| `POST /api/admin/users/{id}/sessions/revoke` | 撤销目标账号全部会话 |
| `POST /api/auth/admin-invitations/accept` | 受邀管理员设置密码并创建账号 |

注册接口只创建研究者，不能通过客户端指定管理员角色。本地体验入口可创建管理员体验账号；生产管理员通过一次性环境变量初始化首个管理员，后续使用管理员邀请和后台账号管理。设置 `CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL`、`CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME`、`CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD` 后启动一次即可创建首个管理员；密码要求为 8 至 24 位，启动成功后应移除密码环境变量。邀请令牌只保存哈希，邮件内容可通过 `PASSWORD_RESET_OUTBOX` 或内部邮件网关投递。

## 旧版本数据迁移

如果旧 Node 服务存在 `data/runtime/db.json`，可在全新的目标数据库上进行一次性导入：

```powershell
$env:LEGACY_IMPORT_ENABLED = 'true'
$env:CRYPTOBENCH_DB_FILE = '<old-project>/data/runtime/db.json'
.\start.cmd
```

导入用户、连接、报告和已发布测评集，保留旧版 scrypt 密码兼容；原文件不修改，旧会话不导入，用户重新登录。目标数据库已有用户时拒绝导入。完成后必须移除 `LEGACY_IMPORT_ENABLED` 或将其设为 `false`，再正常启动。请先备份源文件；H2 切换 MySQL 不会自动迁移已有数据。

## 前端开发与验证

仅修改现有 HTML、CSS、JS 时，直接编辑 `src/main/resources/static/`。需要升级 Vue 或运行浏览器验收时再安装 Node.js 20+ 与 npm 依赖：

```powershell
npm install
npm run vendor:vue
npm run check
node tests/leaderboard-model.mjs
node tests/leaderboard-chart-model.mjs
python -B -X utf8 tests/verify-import.py
.\start.cmd verify
```

浏览器验收需要 npm 安装的 Playwright 与本机 Chrome。先启动一个隔离的测试服务，设置 `TEST_URL`，再依次运行：

```powershell
$env:TEST_URL = 'http://127.0.0.1:4189'
node tests/imported-dataset.mjs
node tests/auth-isolation.mjs
node tests/spring-boot-browser.mjs
```

题库验收应首先在空数据库执行，其余脚本会写入测试账号及数据。启动隔离服务时可传入 `--server.port=4189` 和 `--spring.datasource.url=jdbc:h2:file:./target/test-data/cryptobench;MODE=MySQL;DATABASE_TO_LOWER=TRUE`（PowerShell 中请给整个 URL 参数加引号），避免使用日常开发数据库。

## 题库与当前功能边界

已导入 CryptoBench-SC V4：127 道公开分组密码分析题目、26 个公开附件。可搜索、筛选、查看题干、下载附件和公开任务 ZIP。来源：https://gitee.com/xiandianzidaxue/cryptobench-sc 。重新导入：

```powershell
python -B -X utf8 scripts/import_cryptobench.py '<题库源目录>'
```

只公开题干、附件及必要元数据；隐藏答案、参考解法和评分器不进入静态目录或 ZIP。

本项目已实现服务端运行调度、真实 Agent 接入、Mini-SPN 服务端验证、Docker 沙箱执行来源、可信报告和密码找回流程。运行记录支持排队、运行、完成、失败、超时、取消、日志和重试；客户端提交的成绩不会覆盖服务端验证结果。CryptoBench-SC V4 仍标记为 `executionReady: false`，因为它还没有绑定服务端任务目录和验证器，不会生成模拟成绩。正式材料交付后，可用 `node scripts/validate-evaluator-catalog.mjs <private-tasks.json> cryptobench-sc-v4 4.0.0 --public-catalog src/main/resources/static/data/cryptobench-sc-v4/catalog.json` 检查私有目录是否完整覆盖 127 道公开题目；校验器不会输出隐藏答案。

本地 Agent 的协议、接口和 Docker 镜像要求见 [本地 Agent 测试指南](docs/local-agent.md)。启动确定性 Mock Agent：

```powershell
npm run agent:mock
```

V4 隐藏材料交付后，先使用 `node scripts/validate-v4-materials.mjs <manifest.json> --public-catalog src/main/resources/static/data/cryptobench-sc-v4/catalog.json --materials-root <private-materials-dir>` 校验材料清单、文件哈希和 127 道题覆盖；清单及材料目录必须位于静态资源目录之外，校验输出不包含答案内容。

登录平台后，在“开始测评”中使用 `http://127.0.0.1:8000`，点击“检查本地 Agent”，再创建 Mini-SPN 测评。服务端会轮询运行状态和日志，并在完成后生成带 `runId`、验证器版本和可信状态的私有报告。公开榜单接口 `GET /api/reports/public` 只返回已公开报告，不会返回其他用户的私有记录。

Docker 执行使用 `source: "docker"` 和 Agent 镜像名。平台为每次运行创建 internal Agent network，并使用平台自有的 `cryptobench-agent-proxy:local` 代理镜像把请求限制在宿主机 `127.0.0.1`；Agent 容器本身没有发布端口或直接外网连接。启用可选模型网关后，Agent 可经受控 API 调用允许的供应商和模型。平台同时限制 CPU、内存、进程数、文件系统和 Linux capabilities，并在结束、失败或取消后清理本次 Agent、代理、网关容器和网络。Docker 不可用或镜像无法启动时运行直接失败，不会回退到浏览器模拟结果。

Agent 的 `/capabilities` 若声明 `datasets`，服务端会在执行前校验测评集与版本；题目目录声明的必需工具和能力也会在发送 `/analyze` 前检查。V4 目前仍没有服务端题目目录与验证器，因此会被明确拒绝执行，不会生成伪造分数。

密码找回接口为 `POST /api/auth/forgot` 和 `POST /api/auth/reset`。令牌只保存哈希且一次性使用；邮件投递可通过 `PASSWORD_RESET_DELIVERY_URL` 配置内网邮件网关，本地联调可用 `PASSWORD_RESET_OUTBOX` 写入临时 outbox 文件。

管理员邀请与密码重置共用加密持久队列，支持显式 SMTP、HTTP 网关和本地测试邮箱。管理后台可查看投递状态、重发和撤销；接口不返回明文令牌。SMTP 配置、重试和密钥备份见 [邮件投递](docs/mail-delivery.md)。

忘记密码支持所有符合格式的注册邮箱：获取 6 位邮件验证码 → 验证邮箱 → 设置并确认新密码（8～24 位）→ 返回登录。验证码 10 分钟有效，60 秒后可重发，改密会撤销原登录会话。QQ SMTP 为可选发件通道，不限制收件邮箱服务商；本地 outbox 不发送真实邮件。接口、限流和 QQ 配置见 [邮箱验证码找回密码](docs/password-recovery.md)。

Windows 配置 QQ 发信可运行 `configure-mail.cmd`，在本机窗口输入邮箱授权码并验证保存；授权码使用 Windows DPAPI 加密存储。重启后通过 `start.cmd` 启动会自动加载该配置。IDE 直接启动不经过此加载流程。

应用重启后未开始的排队任务继续调度，结果未确认的运行进入暂停；已保存的题目结果保持不变。补录凭证并确认后继续原 Run，或取消后按原配置重试。运行链接支持刷新，公开报告链接可由游客直接打开。接口、暂停原因和升级方法见 [运行恢复与报告](docs/run-recovery.md)。

管理员可上传 `cryptobench-custom-dataset-v1` JSON，预览后发布不可覆盖的版本；题目与输入公开，评分规则私有。支持精确 JSON 匹配和十进制数值容差，旧格式仅保留浏览能力。格式及示例见 [自定义题库](docs/custom-datasets.md)。本轮实际测试与待验收环境见 [六项功能实施记录](docs/implementation-2026-11-01.md)。

模型价格由管理员在“管理后台 → 模型价格”维护，也可通过 `GET /api/pricing/active` 读取当前公开价格。价格版本保存后不可修改；可复制完整价格表、编辑多模型价格并保存新版本，启用前查看差异。每次运行创建时固定价格快照和哈希，恢复与重试使用原快照；启用新价不回算旧报告。未知模型或缺失用量保留为空费用，历史非 USD 价格不冒充 USD。详见 [价格版本与快照](docs/pricing.md)。Windows 桌面包由 `.github/workflows/release.yml` 在推送 `v*.*.*` 标签后构建并发布到 GitHub Release，同时提供 EXE 安装器、便携版 ZIP 和 SHA-256 校验文件；页面“下载公开安装包”会打开最新 Release。

## 本机 Agent 与模型网关

通过 `start.cmd` 在本机启动网站后，可选择 Agent HTTP 服务或 Docker 沙箱进行测评。HTTP 地址中的 `localhost` 指运行网站后端的电脑。

Docker Agent 需要云模型时，在实际执行机器构建模型网关镜像，并由管理员配置供应商 HTTPS Base URL、模型白名单及可选的价格模型 ID 映射。使用 `CRYPTOBENCH_GATEWAY_ENABLED=true` 与 `CRYPTOBENCH_GATEWAY_PROVIDERS` JSON，或在管理员 YAML 中配置 `benchmark.gateway.providers`。用户 API Key 在发起运行时填写；不要写入 `.env`、供应商配置或报告。

```powershell
docker build -f tests/fixtures/mock-agent.Dockerfile -t cryptobench-mock-agent:local tests/fixtures
docker build -f docker/agent-proxy.Dockerfile -t cryptobench-agent-proxy:local docker
docker build -f docker/model-gateway.Dockerfile -t cryptobench-model-gateway:local docker
```

需要网站容器本身管理 Docker 时使用 `compose.sandbox.yaml` 的 sandbox profile，它会构建三个镜像。网关默认关闭，启用必须配置供应商与模型；`.env.example` 给出不含 Key 的配置格式。源码 `start.cmd` 不自动读取 `.env`。配置与协议见 [模型网关](docs/model-gateway.md)，Compose 操作见 [Docker 部署](docker/README.md)。

独立前端回归可运行 `node tests/run-state.mjs`、`node tests/pricing-admin.mjs`、`node tests/model-gateway.mjs`；它们不读写用户数据库，也不调用真实模型。

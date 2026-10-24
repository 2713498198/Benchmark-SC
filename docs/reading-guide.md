# CryptoBench 项目阅读顺序与建议

这份指南按当前仓库实现整理，适合第一次接触项目时使用。建议按“了解用途 → 启动与配置 → 跟通一个请求 → 业务模块 → 前端与题库 → 测试”的顺序阅读。

文中的路径均以 `Benchmark-SC/` 为根目录。为方便查找，下面用两个缩写：

- `J/`：`src/main/java/com/cryptobench/benchmark/`
- `S/`：`src/main/resources/static/`

## 1. 先建立整体认识（约 15 分钟）

先读 [README.md](../README.md)，重点看“目录与开发约定”“业务接口与安全”“题库与当前功能边界”。

项目是 Java 21 + Spring Boot 3.5.7 + Vue 3 的密码分析智能体测评网站。后端按业务分包，使用 Spring JDBC 访问数据库；前端使用浏览器 ES Module 和 hash 路由，由同一个 Spring Boot 进程提供服务。

先分清当前实现的三类内容，后面阅读会容易很多：

| 内容 | 当前实现 | 主要位置 |
| --- | --- | --- |
| 账号、会话、连接元数据、报告与自定义测评集 | 已有业务接口和数据库持久化 | `J/auth/`、`connection/`、`report/`、`dataset/` |
| CryptoBench-SC V4 公开题库 | 127 道题的浏览、筛选、公开附件和 ZIP 下载 | `S/data/cryptobench-sc-v4/`、`ResourcesPage.js` |
| Agent 执行与评分 | 真实调度、隔离执行、服务端评分尚未接入；现有流程包含模拟连接和模拟成绩 | `S/js/vue/ArenaPage.js` |

阅读目标：能说清“网站保存了什么数据”“哪些数据来自题库”“页面上的成绩如何产生”。

## 2. 从你当前打开的启动入口开始（约 20 分钟）

按以下顺序阅读：

1. [pom.xml](../pom.xml)：确认 Java 版本、Spring Boot 版本，以及 Web、Validation、Security、JDBC、Flyway、H2、MySQL 等依赖的用途。
2. [CryptoBenchApplication.java](../src/main/java/com/cryptobench/benchmark/CryptoBenchApplication.java)：理解 `main()` 启动 Spring、`@SpringBootApplication` 负责自动配置和组件扫描、`@ConfigurationPropertiesScan` 扫描配置属性。这里排除了默认用户服务的自动配置，项目使用自己的账号与会话逻辑。
3. [application.yml](../src/main/resources/application.yml)：找默认 profile、监听地址、端口和业务开关。默认使用 `local`，地址为 `127.0.0.1:4173`。
4. [application-local.yml](../src/main/resources/application-local.yml) 和 [application-prod.yml](../src/main/resources/application-prod.yml)：比较 H2 / MySQL、体验账号和 Cookie 配置。
5. [start.cmd](../start.cmd)、[IDEA 运行配置](../.run/CryptoBench.run.xml)、[Maven 配置](../.mvn/maven.config)：理解启动时的工作目录、JDK/Maven 查找方式与依赖缓存位置。

建议：入口类只需读懂职责，不必停留太久。实际业务逻辑在 Controller 和 Service 中。正常启动网站无需 Node.js；[package.json](../package.json) 主要服务于前端依赖同步和测试。

本地启动可在项目根目录执行 `mvn spring-boot:run`，或双击 `start.cmd`。README 中存在旧目录示例，请以你实际的 `D:\Benchmark-SC2\Benchmark-SC` 为准。`.env.example` 是参数参考，Spring Boot 不会自动加载它。

## 3. 先跟通首页初始化请求（约 25 分钟）

这是理解前后端如何连接的最短路径：

```text
S/index.html
  → S/js/app.js：hydrate() 发起 GET /api/bootstrap
  → J/workspace/controller/WorkspaceController.java：bootstrap()
  → ConnectionService / ReportService / DatasetService
  → 各模块 Repository → 数据库
  → BootstrapResponse → app.js：applyBootstrap() → Vue 页面
```

具体阅读文件：

- [index.html](../src/main/resources/static/index.html)：页面挂载点 `#app` 和模块入口。
- [app.js](../src/main/resources/static/js/app.js)：第一遍只看 `state`、`api()`、`hydrate()`、`applyBootstrap()` 和路由选择。
- [WorkspaceController.java](../src/main/java/com/cryptobench/benchmark/workspace/controller/WorkspaceController.java)：查看游客与登录用户分别返回哪些数据。
- [BootstrapResponse.java](../src/main/java/com/cryptobench/benchmark/workspace/dto/BootstrapResponse.java)：对照响应字段理解前端状态结构。

建议：启动后在浏览器开发者工具的 Network 中找到 `/api/bootstrap`，把 JSON 字段与 `state` 对照。此时无需逐行阅读 Vue 模板或 CSS。

阅读目标：能解释用户、自己的报告、公开报告、连接和自定义测评集分别从哪里进入页面。

## 4. 用连接管理学会后端分层（约 30 分钟）

`connection` 模块体量较小，适合作为第一个完整阅读的业务模块。

| 顺序 | 文件（位于 `J/`） | 关注点 |
| --- | --- | --- |
| 1 | `connection/controller/ConnectionController.java` | 列表、新增、删除接口；`@AuthenticationPrincipal` 如何提供当前用户 |
| 2 | `connection/dto/ConnectionRequest.java` | 请求字段和校验注解 |
| 3 | `connection/service/ConnectionService.java` | ID、时间等字段生成，以及业务处理 |
| 4 | `connection/model/AgentConnection.java` | 数据库模型与对外响应的区别 |
| 5 | `connection/repository/ConnectionRepository.java` | `JdbcTemplate`、参数化 SQL、`owner_id` 条件与查询结果映射 |

再对照 [建表脚本](../src/main/resources/db/migration/V1__create_benchmark_schema.sql) 中的 `benchmark_connections`，确认 Java 字段如何落到数据库列。

前端入口在 `app.js` 的 `saveConnection()`，列表和删除操作在 `CommunityPage.js`。按“新增一个连接”的操作追踪一次，比一次性读完所有 Controller 更容易掌握结构。

注意：这里保存名称、地址、模型等元数据；保存成功不表示已经连接到 Agent 服务。

## 5. 再看登录、会话与权限（约 40 分钟）

先看 [SecurityConfiguration.java](../src/main/java/com/cryptobench/benchmark/config/SecurityConfiguration.java) 中的接口访问规则，再按两条线阅读：

```text
注册 / 登录：AuthController → AuthService → UserRepository / PasswordService
后续请求：SessionAuthenticationFilter → SessionService → SessionRepository
```

重点文件都在 `J/auth/` 下：`controller/AuthController.java`、`service/AuthService.java`、`service/SessionService.java`、`security/SessionAuthenticationFilter.java`。最后补读 `service/PasswordService.java` 和 `repository/`。

需要弄清楚的几个问题：

- 登录后怎样设置会话 Cookie，后续请求怎样恢复当前用户？
- 为什么数据库保存会话令牌摘要，而新密码使用带盐的 PBKDF2 哈希？
- 前端 `app.js` 的 `api()` 为什么在写请求前获取 CSRF 令牌？
- 为什么注册只能创建研究者，发布测评集要求管理员？
- 为什么接口级访问规则之外，Service / Repository 仍要检查资源归属？

建议：第一遍先理解数据流，密码算法细节与旧版 scrypt 兼容可以后读。遇到错误响应时，再查 `J/common/ApiException.java`、`ApiExceptionHandler.java`；请求体限制对应 `J/config/RequestSizeFilter.java`。

## 6. 阅读报告和测评集的业务规则（约 40 分钟）

先读 `J/report/`，依次为 `controller/ReportController.java` → `service/ReportService.java` → `model/BenchmarkReport.java` → `repository/ReportRepository.java`。

建议把 `ReportService` 当作重点：

- `save()`：生成或校验 ID，检查归属，控制同步与公开状态；已公开报告不可修改。
- `sync()`：记录同步状态，报告仍保持私有。
- `publish()`：要求先同步、任务范围为 `full`，并拒绝 PK 报告公开。
- `get()`：公开报告可读取，私有报告需要校验当前用户。

对照建表脚本理解报告的存储方式：ID、归属和公开状态是独立列，报告内容放在 JSON `payload` 中。`J/common/JsonDocuments.java` 提供 JSON 读写和标识符校验。

然后读 `J/dataset/controller/DatasetController.java` → `service/DatasetService.java` → `repository/DatasetRepository.java`。重点理解管理员权限、非空任务数组、同一 ID 与版本不可覆盖，以及前端提交的 `tasks` 数组如何转为 `taskPackage` 和任务数量。

建议：同时打开 `BenchmarkApiIntegrationTest.java` 中相关测试，用具体请求和断言确认业务规则。公开报告表示用户公开了记录，不代表服务器已验证成绩。

## 7. 按职责深入前端与题库（约 45 分钟）

已读过 `app.js` 后，推荐顺序如下：

| 顺序 | 文件（位于 `S/js/`） | 阅读重点 |
| --- | --- | --- |
| 1 | `vue/shared.js` | `provide/inject` 共享应用状态，`useApp()` 与图标组件 |
| 2 | `vue/CommunityPage.js` | 登录、注册、工作台、连接操作、自定义测评集发布 |
| 3 | `vue/ResourcesPage.js`、`vue/ResourceInfo.js` | 题库展示、筛选、题干加载、下载和说明内容 |
| 4 | `data.js`、`cryptobench-v4.js` | 内置示例数据与真实公开题库元信息的来源 |
| 5 | `vue/ArenaPage.js` | 测评配置、模拟连接、进度、报告、同步与公开 |
| 6 | `leaderboard-model.js` → `vue/LeaderboardPage.js` → `leaderboard-chart.js` | 先理解榜单过滤和排序，再看界面及图表 |
| 7 | `vue/HomePage.js`、`core.js`、`S/css/` | 首页表现、工具函数和样式，按需查阅 |

当前前端没有 `.vue` 单文件组件，也没有必须运行的 Vite 开发服务。组件的逻辑和 `template` 字符串写在 `.js` 中；路由由 `app.js` 解析 URL 的 `#` 部分并选择页面。

阅读 `ArenaPage.js` 时，重点找 `checkConnection()`、`begin()` 和 `score()`：连接检查只验证地址格式，计时器推进演示进度，成绩由前端演示函数产生。真实题库的 `executionReady: false` 会阻止进入该演示执行流程。

公开题库的数据流建议这样读：

```text
外部题库源目录
  → scripts/import_cryptobench.py
  → S/js/cryptobench-v4.js（元信息）
  → S/data/cryptobench-sc-v4/（catalog、公开题干、附件、ZIP）
  → data.js / ResourcesPage.js 展示
```

先查看 `catalog.json` 的结构，再选一道 `tasks/*/public/question.md` 作为样本即可。`cryptobench-v4.js` 是导入脚本生成的文件，需要更新题库时应先读生成脚本。隐藏答案、参考解法和评分器不进入公开静态资源。

## 8. 最后用测试和迁移补齐理解（约 30 分钟）

| 文件 | 用途 |
| --- | --- |
| `src/test/java/com/cryptobench/benchmark/BenchmarkApiIntegrationTest.java` | 主要业务样例：注册、会话、用户隔离、报告状态、管理员发布、CSRF 与请求体大小 |
| `src/test/java/com/cryptobench/benchmark/ProductionSecurityIntegrationTest.java` | 生产配置关闭管理员体验登录 |
| `src/test/java/com/cryptobench/benchmark/auth/service/PasswordServiceTest.java` | 新密码哈希和旧版 scrypt 验证 |
| `tests/leaderboard-model.mjs`、`tests/leaderboard-chart-model.mjs` | 榜单和图表计算规则 |
| `tests/auth-isolation.mjs`、`tests/spring-boot-browser.mjs` | 从浏览器验证用户流程及数据隔离 |
| `tests/verify-import.py`、`tests/imported-dataset.mjs` | 公开题库导入结果与页面行为 |
| `J/migration/LegacyDataImporter.java` 和 `LegacyDataImportIntegrationTest.java` | 从旧 Node 服务的 JSON 数据文件导入；有迁移需求时再细读 |

常用验证命令在项目根目录运行：

```powershell
mvn verify
npm run check
node tests/leaderboard-model.mjs
node tests/leaderboard-chart-model.mjs
python -B -X utf8 tests/verify-import.py
```

其中 npm 检查需先安装依赖。浏览器测试另需 Playwright、本机 Chrome 和正在运行的服务；按 README 设置 `TEST_URL`，使用独立端口与测试数据库，因为这些脚本会创建账号和业务数据。

## 阅读方法与后续建议

- **每次跟通一个动作。** 推荐练习顺序：打开首页 → 登录 → 新增连接 → 保存演示报告 → 同步 → 公开 → 查看榜单。每一步记录“页面函数、API、Service、SQL 表”。
- **先读函数，再读长模板。** 前端文件中部分模板行很长，先定位 `setup()`、`computed` 和事件处理函数，再回到模板查看绑定关系。
- **按业务模块纵向阅读。** 读完一个 Controller 后顺着它调用的 Service 和 Repository 向下追踪，并对照 DTO、Model 与 SQL；遇到校验和权限问题时再补相关公共代码。
- **把测试当作可执行的业务说明。** 重点看输入、响应和断言，再回到实现解释原因。文档与代码不一致时，用实现和测试共同确认。
- **区分数据库、前端状态与缓存。** `app.js` 会写 localStorage，但启动时通过 bootstrap 取得服务端数据；不能把浏览器缓存当作账号私有数据的最终来源。
- **第一遍跳过依赖和产物。** `vendor/`、`.mvn/repository/`、`target/`、`var/`、字体文件、`tests/artifacts/` 不必逐个阅读；`docs/archive/` 保留历史实现，理解当前版本后再看。
- **若计划继续开发真实评测，先定义执行结果的来源。** 当前已有账号、报告与公开流程，可在理解这些接口后规划任务调度、执行记录和服务端验证结果，再对接前端进度与榜单。

时间有限时，先完成第 1～4 步，再精读 `ReportService.java` 与 `ArenaPage.js`。这条路线能先掌握架构、请求流和当前功能边界。

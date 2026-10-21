# CryptoBench

专用于比较智能体在密码分析任务中通过率、耗时与资源消耗的评测网站。项目采用 Java 21、Spring Boot 3.5.7 和 Vue 3，前后端由同一个 Spring Boot 进程提供服务。

## 快速启动

环境要求：JDK 21、Maven 3.6.3 或以上。当前启动脚本兼容 `D:\develop\jdk` 和 `D:\develop\apache-maven-3.9.15`；Maven 依赖缓存保存在项目的 `.mvn/repository/` 中。

在项目根目录双击 `start.cmd`，或运行：

```powershell
cd D:\develop\Benchmark
mvn spring-boot:run
```

浏览器访问 http://127.0.0.1:4173 。首次运行需要联网下载 Maven 依赖。本地默认使用 H2 文件数据库，启动时自动创建表，无需额外安装数据库；数据保存在 `var/data/cryptobench.mv.db`，重启后保留。

前端 Vue 运行时已随项目放在 `src/main/resources/static/vendor/vue/`，启动网站不需要 Node.js 或 npm。

## 在 IntelliJ IDEA 中运行

1. 使用 IDEA 打开项目根目录的 `pom.xml`，选择作为 Maven 项目加载；已有项目可在 Maven 面板点击“添加 Maven 项目”并选择该文件。
2. 在 Project Structure 中将 Project SDK 与语言级别设置为 Java 21；Maven 的 Runner JRE 也选用该 JDK。
3. 等待 Maven 同步完成，运行 `src/main/java/com/cryptobench/benchmark/CryptoBenchApplication.java` 的 `main` 方法。项目也提供了共享运行配置 `CryptoBench`。
4. 工作目录使用项目根目录。可在 Run Configuration 的 Environment variables 中设置 `PORT`、`SPRING_PROFILES_ACTIVE` 等参数。

默认 profile 是 `local`。不依赖 IDEA 的 Spring 专属插件，普通 Java Application 配置即可启动。

## 打包部署

```powershell
mvn clean verify
java -jar target/cryptobench-1.0.0-SNAPSHOT.jar
```

可执行 JAR 包含前端、公开题库和后端。启动工作目录决定本地数据库相对路径，请固定工作目录；关闭程序后再备份 H2 数据文件。健康检查地址为 `/actuator/health`。

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

`.env.example` 仅作为参数参考，Spring Boot 不会自动加载 `.env`；请在系统环境、PowerShell 或 IDEA 运行配置中设置。

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
| `POST /api/auth/demo` | 本地体验账号，仅 local 默认启用 |
| `GET /api/bootstrap` | 工作台与公开数据 |
| `GET/POST /api/connections` | 连接列表和新增 |
| `DELETE /api/connections/{id}` | 删除自己的连接 |
| `GET/POST /api/reports` | 报告列表和保存 |
| `GET /api/reports/{id}` | 自己的报告或已公开报告 |
| `POST /api/reports/{id}/sync`、`publish` | 同步、公开自己的报告 |
| `POST /api/datasets/custom` | 管理员发布测评集版本 |

注册接口只创建研究者，不能通过客户端指定管理员角色。本地体验入口可创建管理员体验账号；生产管理员需要通过受控的数据初始化或账号管理流程设置。

## 旧版本数据迁移

如果旧 Node 服务存在 `data/runtime/db.json`，可在全新的目标数据库上进行一次性导入：

```powershell
$env:LEGACY_IMPORT_ENABLED = 'true'
$env:CRYPTOBENCH_DB_FILE = 'D:/develop/Benchmark/data/runtime/db.json'
mvn spring-boot:run
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
mvn verify
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

本次实现了 Spring Boot 业务后端及数据库持久化。真实 Agent 调度、隔离执行、服务端评分和邮件找回密码仍待接入；其余示例题库和示例榜单延续已有标注。报告指标仍来自现有前端流程，不能视为服务端认证成绩；真实题库继续禁止生成模拟成绩。

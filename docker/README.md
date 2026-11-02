# CryptoBench 本地部署

## 启动网站容器

安装并启动 Docker（Windows/macOS 推荐 Docker Desktop），在项目根目录运行。无需本机 JDK、Maven 或预先生成 JAR：

```powershell
docker compose up -d --build
```

多阶段构建使用固定的 Java 21 镜像和项目 Maven Wrapper 编译源码，再将 JAR 放入独立运行镜像。`unzip` 仅用于构建阶段的 Maven ZIP 校验与解压；运行镜像中的 `wget` 用于 `/actuator/health` 健康检查。

访问 `http://127.0.0.1:4173`，停止服务使用 `docker compose down`。数据保存在项目的 `var/` 目录，重建容器仍然保留。可在 `.env` 设置 `HOST_PORT=4180` 更换宿主机端口，或设置 `CRYPTOBENCH_DATA_DIR` 指定数据目录。首次需要下载镜像与 Maven 依赖；代理配置见 [启动指南](../docs/SETUP.md)。

基础 Compose 和 sandbox 均只将网站端口发布到宿主机 `127.0.0.1`，默认关闭研究者和管理员的免密码体验登录。普通用户可以注册后登录；管理员按下面步骤初始化。这两种配置用于本地使用，不是带 HTTPS/MySQL 的生产部署栈。容器内部仍监听 `0.0.0.0:4173`，用于端口转发和健康检查。

## 初始化真实管理员

在项目根目录创建或编辑已被 Git 忽略的 `.env`，填写一个尚未注册的真实邮箱、姓名及 12～128 位密码。以下值仅为格式示例，需自行替换：

```dotenv
CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL=your-admin@example.com
CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME=Platform Admin
CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD=replace-with-your-own-strong-password
```

不要使用体验地址 `admin@cryptobench.local` 或 `researcher@cryptobench.local`。三项齐全时应用创建管理员；未提供时仍可启动并注册普通用户。若邮箱已经注册，初始化不会覆盖密码或提升角色，请换用尚未注册的邮箱。

首次使用基础 Compose 执行：

```powershell
docker compose up -d --build
```

若容器已经运行，修改 `.env` 后执行下方重建命令。确认能通过密码登录并进入管理页面后，从 `.env` 移除上述三项；若终端或系统环境中也设置了它们，一并移除，因为终端环境优先于 `.env`。再次重建网站容器，使初始化密码不再保留在新容器的环境变量中。账号和密码哈希仍保存在数据库。

| 使用方式 | 更新环境变量后重建网站容器 |
| --- | --- |
| 基础 Compose | `docker compose up -d --force-recreate --no-deps cryptobench` |
| 已启动的 sandbox | `docker compose -f compose.yaml -f compose.sandbox.yaml --profile sandbox up -d --force-recreate --no-deps cryptobench` |

`docker compose restart` 只重启旧容器，不会加载修改后的环境变量。首次启动 sandbox 时使用下一节的完整命令，先构建所需 Agent 镜像。

## 临时开启与关闭体验登录

仅需临时演示时，可在 `.env` 中设置：

```dotenv
CRYPTOBENCH_COMPOSE_DEMO_ENABLED=true
```

使用上表对应命令重建网站容器。此时出现研究者和管理员体验入口，但宿主机端口仍只绑定本机。演示结束将该值改为 `false` 或删除该项，并再次重建容器。

关闭体验后，后端拒绝新的体验登录，并在启动阶段撤销固定体验 ID 和上述两个精确体验邮箱对应的旧会话。体验账号及其报告不会删除；普通真实账号会话不受影响。重新开启体验也不会恢复已撤销的 Cookie，需要重新点击体验登录。

Compose 会读取 `.env` 进行变量替换，但只有 Compose 明确映射的变量才进入容器。直接用 `start.cmd` 或 Java 启动不会自动读取 `.env`，源码 local 的体验默认值保持不变。

## 完全自包含的本地沙箱部署

普通 `compose.yaml` 只启动网站，保持网站容器与 Docker 引擎隔离。需要在一台安装 Docker Desktop 的开发机上同时运行网站和真实 Docker Agent 测评时，使用显式的 sandbox profile：

```powershell
docker compose -f compose.yaml -f compose.sandbox.yaml --profile sandbox up -d --build
```

访问 `http://127.0.0.1:4173`。停止并清理：

```powershell
docker compose -f compose.yaml -f compose.sandbox.yaml --profile sandbox down --remove-orphans
```

该 profile 会构建 Mock Agent、平台代理和模型网关镜像，网站容器通过 Docker Desktop 的 socket 创建每次运行的受限 Agent 容器、internal network 和代理，并在运行结束后清理它们。模型网关只有在管理员启用并为本次运行选择供应商时才启动。Docker CLI 和 socket 只存在于这个显式的 sandbox 应用容器，默认网站 Compose 不获得这些权限。

sandbox 继承基础 Compose 的体验开关、管理员初始化和文件数据库，账号、运行记录、报告均保存在宿主机数据目录；停止、重启或重建网站容器不会清除这些数据。

## 数据目录、模式切换与备份

两种 Compose 使用同一容器路径 `/app/var/data/cryptobench.mv.db`，默认对应宿主机 `var/data/cryptobench.mv.db`。需要独立实例时，在各自 `.env` 中指定不同数据目录及端口，例如：

```dotenv
HOST_PORT=4180
CRYPTOBENCH_DATA_DIR=./var/sandbox
```

自定义数据目录中的数据库和 outbox 属于私有运行数据；应放在仓库外或使用已被 Git 忽略的 `./var/` 子目录。

基础模式切换到 sandbox：先 `docker compose down`，再执行 sandbox 的完整启动命令。切回基础模式：先执行 sandbox 的 `down --remove-orphans`，再 `docker compose up -d --build`。保持同一项目及 `CRYPTOBENCH_DATA_DIR` 即可复用数据。

同一 H2 文件库只允许一个应用进程使用；不要让 `start.cmd`、基础 Compose 或 sandbox 同时打开同一数据库。目录不可写或数据库被占用时应排查启动失败，不要通过换成内存库绕过错误。

备份前停止使用该目录的应用，再复制整个数据目录到独立备份位置；恢复时保持应用停止，恢复完整目录后启动。数据库包含账号、会话与报告，备份应按私有数据保存。`down` 不删除宿主机绑定目录，主动删除或更换该目录会导致数据丢失或看到一个新数据库。

**旧 sandbox 升级：** 旧配置使用内存数据库，已结束进程中的数据无法恢复。如果旧服务仍在运行且有重要报告，应在停止前导出可导出的报告；报告 JSON 不是完整数据库备份，也不能恢复账号或全部运行记录。此次修复保证新配置的数据持久化，不提供旧内存数据库自动迁移。已有基础 Compose 文件库路径不变，升级前停机备份后可以继续使用。

## 部署回归测试

```powershell
npm run test:compose:config
npm run test:compose
npm run test:compose:sandbox
```

配置检查不需要运行中的 Docker 引擎；后两项会构建并启动容器，使用独立项目名、随机本机端口和 `tmp/` 下的测试数据库，不读取个人 `.env`。测试覆盖体验关闭/开启/旧会话撤销、管理员初始化，以及 sandbox 报告在重启、重建和切回基础模式后保留。测试结束移除测试容器，临时数据保留供排错；不会删除共享的 fixture 镜像。

sandbox 测试串行运行并使用独立 Compose 项目。资源创建和启动回收都按 instance 标签隔离，Compose 默认使用项目名作为 instance；自定义 `CRYPTOBENCH_INSTANCE_ID` 时应确保同时运行的实例使用不同值。旧版本没有 instance 标签的资源不会被自动全局删除。

## Agent 沙箱

普通 `compose.yaml` 只负责网站容器，正式 Agent 沙箱由 Spring Boot 运行时按每次运行动态创建。若 Spring Boot 运行在宿主机，可按下面方式启用 Docker 执行：

```powershell
docker build -f tests/fixtures/mock-agent.Dockerfile -t cryptobench-mock-agent:local tests/fixtures
$env:CRYPTOBENCH_DOCKER_ENABLED = 'true'
```

普通网站 Compose 不挂载 Docker socket，也不会代替服务端创建动态 Agent 容器。上面的显式 sandbox profile 才会把受控 Docker CLI 和 socket 注入网站容器。Docker 沙箱执行完成后，平台会按 Run ID 清理容器和专属网络。

## 模型网关配置

默认关闭。复制 `.env.example` 为个人 `.env` 后，配置公开的供应商路由和模型名单，例如以下格式（请替换示例域名和模型 ID）：

```dotenv
CRYPTOBENCH_GATEWAY_ENABLED=true
CRYPTOBENCH_GATEWAY_PROVIDERS='[{"id":"example","name":"Example","baseUrl":"https://your-provider.example/v1","models":["provider-model-id"],"priceModelIds":{"provider-model-id":"model-id-in-price-table"}}]'
```

这里不填写 API Key。用户在测评页提供本次运行的 Key；网关以 stdin 接收，Docker inspect 中不存在该 Key。变更 `.env` 后按上表重建网站容器，单纯 restart 不更新变量。sandbox 自动使用 `cryptobench-model-gateway:compose`；宿主机启动使用 `:local` 镜像。

完整 YAML、多模型映射、计量和临时凭证规则见 [模型网关文档](../docs/model-gateway.md)。

# CryptoBench 本地部署

## 启动网站容器

先在项目根目录构建 JAR：

```powershell
mvn clean verify
docker compose up -d --build
```

镜像构建阶段会安装 `wget`，用于 Compose 的 `/actuator/health` 健康检查；运行时不会安装额外开发工具。

访问 `http://127.0.0.1:4173`，停止服务使用 `docker compose down`。数据保存在项目的 `var/` 目录。

## 完全自包含的本地沙箱部署

普通 `compose.yaml` 只启动网站，保持网站容器与 Docker 引擎隔离。需要在一台安装 Docker Desktop 的开发机上同时运行网站和真实 Docker Agent 测评时，使用显式的 sandbox profile：

```powershell
mvn clean verify
docker compose -f compose.yaml -f compose.sandbox.yaml --profile sandbox up -d --build
```

访问 `http://127.0.0.1:4173`。停止并清理：

```powershell
docker compose -f compose.yaml -f compose.sandbox.yaml --profile sandbox down --remove-orphans
```

该 profile 会构建 Mock Agent 和平台代理镜像，网站容器通过 Docker Desktop 的 socket 创建每次运行的受限 Agent 容器、internal network 和代理，并在运行结束后清理它们。Docker CLI 和 socket 只存在于这个显式的 sandbox 应用容器，默认网站 Compose 不获得这些权限。

## Agent 沙箱

普通 `compose.yaml` 只负责网站容器，正式 Agent 沙箱由 Spring Boot 运行时按每次运行动态创建。若 Spring Boot 运行在宿主机，可按下面方式启用 Docker 执行：

```powershell
docker build -f tests/fixtures/mock-agent.Dockerfile -t cryptobench-mock-agent:local tests/fixtures
$env:CRYPTOBENCH_DOCKER_ENABLED = 'true'
```

普通网站 Compose 不挂载 Docker socket，也不会代替服务端创建动态 Agent 容器。上面的显式 sandbox profile 才会把受控 Docker CLI 和 socket 注入网站容器。Docker 沙箱执行完成后，平台会按 Run ID 清理容器和专属网络。

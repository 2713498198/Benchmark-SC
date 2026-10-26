# CryptoBench 本地部署

## 启动网站容器

先在项目根目录构建 JAR：

```powershell
mvn clean verify
docker compose up -d --build
```

访问 `http://127.0.0.1:4173`，停止服务使用 `docker compose down`。数据保存在项目的 `var/` 目录。

## Agent 沙箱

当前 compose 只负责网站容器。正式 Agent 沙箱需要 Spring Boot 运行在宿主机并访问同一台 Docker Desktop：

```powershell
docker build -f tests/fixtures/mock-agent.Dockerfile -t cryptobench-mock-agent:local tests/fixtures
$env:CRYPTOBENCH_DOCKER_ENABLED = 'true'
```

网站容器不会挂载 Docker socket，也不会代替服务端创建动态 Agent 容器。Docker 沙箱执行完成后，平台会按 Run ID 清理容器和专属网络。

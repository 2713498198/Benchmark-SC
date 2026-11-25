# 生产部署与运维手册

本文按 `compose.prod.yaml` 描述服务器部署。生产使用 MySQL 8、prod profile、Caddy 自动 HTTPS。

## 前置条件

- 一台有公网 IP 的 Linux 服务器，域名 A 记录解析到该 IP。
- 已安装 Docker 和 Compose v2。
- 防火墙只放行 80/443（Caddy），不要直接暴露应用端口。

## 首次启动

1. 复制变量模板并填写真实值：

   ```bash
   cp .env.prod.example .env.prod
   ```

   至少填写 `DOMAIN`、`MYSQL_ROOT_PASSWORD`、`DB_PASSWORD`。所有生产命令统一从
   项目根目录显式加载 `.env.prod`：

   ```bash
   docker compose --env-file .env.prod -f compose.prod.yaml <命令>
   ```

2. 启动：

   ```bash
   docker compose --env-file .env.prod -f compose.prod.yaml up -d --build
   docker compose --env-file .env.prod -f compose.prod.yaml ps
   ```

3. 访问 `https://<DOMAIN>`，在页面使用邮箱和密码注册账号。Caddy 会申请并自动续期证书。

## 正式榜单

正式公开榜单只纳入满足以下条件的完整测评：

- 答案由服务端评测器验证；
- 模型调用经过平台模型网关，且网关确认实际供应商与模型；
- 供应商返回完整 Token 用量；
- 价格快照存在，全部费用可计算。

本地直连 Agent 和 Runner 报告仍可公开查看，但 `trust.rankingEligible=false`，
不会进入正式榜单。Agent 名称与版本始终标记为用户声明，不作为平台认证身份。

## 邮件

生产建议使用 SMTP：在 `.env.prod` 设置 `CRYPTOBENCH_MAIL_MODE=smtp` 及
`CRYPTOBENCH_SMTP_*`。邮件加密密钥持久化在 `app-var` 卷的
`/app/var/secrets/mail.key`，务必纳入备份，丢失会导致排队中的改密/邀请邮件无法投递。

## 备份与恢复

备份（默认保留 14 份）：

```bash
BACKUP_DIR=/var/backups/cryptobench ./scripts/backup-prod.sh
```

恢复步骤：

1. 停掉应用，保留 MySQL 容器运行。
2. 解压备份，把 `cryptobench.sql` 导入 MySQL，恢复 `secrets/` 到 `app-var` 卷的
   `/app/var/secrets`。
3. 重新启动 `app`，Flyway 会在启动时校验并补齐迁移。

## 升级

1. 备份数据库（见上）。
2. 拉取新代码并重建镜像：

   ```bash
   git pull
   docker compose --env-file .env.prod -f compose.prod.yaml build app
   docker compose --env-file .env.prod -f compose.prod.yaml up -d app
   ```

3. 应用启动时执行 Flyway 版本化迁移，确认健康检查通过：

   ```bash
   curl -fsS https://<DOMAIN>/actuator/health
   ```

   若域名或证书尚未就绪，也可从应用容器内部检查：

   ```bash
   docker compose --env-file .env.prod -f compose.prod.yaml exec -T app \
     wget -qO- http://127.0.0.1:4173/actuator/health
   ```

## 安全与限流

- prod profile 的会话与 CSRF Cookie 使用 Secure 属性。
- 容器入口首次启动会修正 `/app/var` 归属，然后降权到非 root 的 `app` 用户运行 Java。
- 登录按邮箱、注册按 IP 做滚动窗口限流，默认参数可用
  `CRYPTOBENCH_AUTH_LOGIN_*` / `CRYPTOBENCH_AUTH_REGISTER_*` 覆盖。
- 运行创建按用户限制活跃数量、每日创建数量，并限制平台全局活跃运行数。
  默认值为每用户 2 个活跃运行、每日 10 次创建、全平台 100 个活跃运行。
  可通过 `CRYPTOBENCH_RUN_MAX_ACTIVE_PER_USER`、
  `CRYPTOBENCH_RUN_MAX_CREATED_PER_DAY_PER_USER` 和
  `CRYPTOBENCH_RUN_MAX_ACTIVE_GLOBAL` 调整。
- 数据库和 `app-var` 卷不要挂到公共可写路径；`.env.prod` 已在 `.gitignore` 中。

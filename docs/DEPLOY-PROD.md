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

   至少填写 `DOMAIN`、`MYSQL_ROOT_PASSWORD`、`DB_PASSWORD`。

2. 初始化首个管理员：在 `.env.prod` 中同时设置
   `CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL`、`CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME`、
   `CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD`（密码 8～24 位）。

3. 启动：

   ```bash
   docker compose -f compose.prod.yaml up -d --build
   docker compose -f compose.prod.yaml ps
   ```

4. 首个管理员创建成功后，删除 `.env.prod` 中三个 `CRYPTOBENCH_BOOTSTRAP_ADMIN_*`
   变量，然后重建应用容器使其不保留引导密码：

   ```bash
   docker compose -f compose.prod.yaml up -d --force-recreate app
   ```

5. 访问 `https://<DOMAIN>`。Caddy 会申请并自动续期证书。

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
   docker compose -f compose.prod.yaml build app
   docker compose -f compose.prod.yaml up -d app
   ```

3. 应用启动时执行 Flyway 版本化迁移，确认健康检查通过：

   ```bash
   curl -fsS http://127.0.0.1:4173/actuator/health
   ```

## 安全与限流

- prod profile 默认关闭体验账号，会话与 CSRF Cookie 使用 Secure 属性。
- 登录按邮箱、注册按 IP 做滚动窗口限流，默认参数可用
  `CRYPTOBENCH_AUTH_LOGIN_*` / `CRYPTOBENCH_AUTH_REGISTER_*` 覆盖。
- 数据库和 `app-var` 卷不要挂到公共可写路径；`.env.prod` 已在 `.gitignore` 中。

# 邮箱验证码找回密码

在登录页选择“忘记密码”，输入已注册邮箱后获取验证码。收到邮件后输入完整 6 位数字（例如 `003917`），点击“下一步”；验证通过后填写新密码、确认密码，成功后使用新密码重新登录。密码长度为 8～24 位。

支持 QQ、163、Outlook、Gmail 和企业邮箱等通用地址。网站使用哪家的 SMTP 发件，不决定用户必须使用哪家的邮箱。申请成功仅表示请求已提交，不能证明邮件已经进入收件箱；应检查垃圾邮件，管理员可检查邮件通道和队列状态。

验证码验证时若网络中断或网关返回服务异常，页面会清空验证码，并提供“重新检查验证状态”。服务器可能已经接受验证码并设置授权 Cookie；检查成功即可继续设置密码，无需重发邮件。若服务器未接受验证码，可重新输入原有效验证码；若检查发现原改密授权已失效，则清空新密码并回到验证邮箱步骤。

## 部署交接与实际收件验收

仓库已具备邮箱验证码方案，无需因为尚未配置发件账号而更换找回方式。建议由部署方准备专用发件邮箱及 SMTP 授权码，并在目标服务器配置 SMTP、持久化邮件密钥及 HTTPS。授权码、密钥、outbox 内容均不得提交到 Git。若服务器限制 SMTP 出站，可使用已有 HTTP 邮件网关接口，用户仍走相同的验证码流程。

上线验收应使用已注册的受控真实邮箱：

1. 在目标网站申请验证码，确认收件箱或垃圾邮件中确实收到邮件；队列的 `provider_accepted` 仅表示发件服务接受，不等于已进入收件箱。
2. 输入验证码，设置并确认新密码，再使用新密码登录；确认旧密码与之前登录的会话失效。
3. 确认旧码、重复使用的码及过期码不可用，重发间隔和发送限额生效。
4. 邮箱失效等无法收信的情况，通过管理员人工核验身份后处理；不能只凭邮箱字符串直接改密。

自动化测试和本地 outbox 联调不能替代第 1 步。下文历史验收记录属于对应日期及当时环境，不能证明新的部署环境已具备真实发信能力。

## 有效期与限流

验证码 10 分钟有效、只可验证一次；验证后改密授权 5 分钟有效。每个邮箱发送间隔至少 60 秒，每小时最多 5 次；每个来源 IP 每小时最多 20 次。单个验证码输错 5 次作废，邮箱累计每 15 分钟最多 10 次失败；重发不会清零累计失败计数。来源 IP 每 5 分钟最多 50 次验证。限额保存在数据库，对存在和不存在的邮箱都执行，重启与多实例不能绕过；不锁定正常登录。

获准重发后旧码和已有改密授权立即失效。旧邮件即使稍后到达，里面的码也不能使用。密码修改成功后撤销该账号的全部登录会话、旧验证码、改密授权、旧重置链接和未完成重置邮件，不自动登录。

验证码和密码只保存在当前页面内存中。验证前刷新需要重新申请；验证后刷新可在授权有效期内继续设置密码。授权 Cookie 为 HttpOnly、SameSite=Strict，路径为 `/api/auth/reset`；HTTPS/正式部署使用 Secure，不能用于正常登录。账号切换或离开页面清空敏感输入。

服务端可通过以下 `benchmark.password-reset-code` 属性覆盖默认值；所有值必须为正整数。

| 属性 | 默认值 |
|---|---|
| `code-lifetime-seconds` | 600 |
| `grant-lifetime-seconds` | 300 |
| `resend-seconds` | 60 |
| `sends-per-email-per-hour` | 5 |
| `sends-per-ip-per-hour` | 20 |
| `failures-per-challenge` | 5 |
| `failures-per-email-per-quarter-hour` | 10 |
| `verifications-per-ip-per-five-minutes` | 50 |

## HTTP 接口

写接口均要求原有 CSRF 请求头。身份相关响应不缓存。

| 接口 | 请求 / 返回 |
|---|---|
| `POST /api/auth/forgot` | `{email}` → `{ok,challengeId,expiresInSeconds,retryAfterSeconds}`，不存在邮箱也返回相同结构 |
| `POST /api/auth/reset/verify` | `{challengeId,code}` → `{ok:true}` 并设置短时 `cb_reset_grant` Cookie |
| `GET /api/auth/reset/status` | `{canReset,challengeId,maskedEmail,expiresAt}`；无有效授权时 `canReset=false` |
| `POST /api/auth/reset/complete` | `{challengeId,password,confirmPassword}` + Cookie → `{ok:true}`；挑战必须与授权一致 |
| `POST /api/auth/reset` | 兼容旧链接的 `{token,password}`；新申请不再生成链接 |

验证码无效、过期、已使用返回统一失败提示；限流返回 HTTP 429、`Retry-After` 和 `retryAfterSeconds`。邮件通道/稳定密钥不可用时对所有邮箱一致返回暂不可用，不创建不可投递的挑战。限制通过连接来源 IP 计算，不信任请求自行提供的 `X-Forwarded-For`；反向代理部署需考虑代理出口的共享限额。

## QQ SMTP 与本地联调

先在 QQ 邮箱设置中开启 SMTP 并取得授权码。授权码留在本机，勿写入 Git 或聊天。使用 `benchmark.mail` 配置：

Windows 本机可双击项目根目录的 `configure-mail.cmd`，在掩码输入框中填写 QQ 发件邮箱及授权码，点击“验证并保存”。窗口仅测试到 `smtp.qq.com:465` 的加密连接与 SMTP 登录，不发送邮件。验证成功后，凭证以当前 Windows 用户绑定的 DPAPI 密文保存在已忽略的 `var/secrets/qq-smtp.credential.xml`，文件权限仅允许该用户访问。重启网站后，通过普通 `start.cmd` 启动会自动加载；以后无需重复输入。IDE 直接启动 Java 不经过此加载脚本。

显式设置 `CRYPTOBENCH_MAIL_MODE` 时以该模式为准；打包、测试和 `PrepareOnly` 不加载本机发信凭证。凭证损坏或无法解密会明确报错，不会默默退回本地 outbox。重新运行配置窗口可以更换授权码。

```yaml
benchmark:
  mail:
    mode: smtp
    smtp-host: smtp.qq.com
    smtp-port: 465
    smtp-username: ${CRYPTOBENCH_SMTP_USERNAME}
    smtp-password: ${CRYPTOBENCH_SMTP_PASSWORD}
    from: ${CRYPTOBENCH_MAIL_FROM}
    smtp-ssl: true
    smtp-starttls: false
```

账号、发件人填写已开启 SMTP 的 QQ 邮箱，密码填写其授权码。也可使用 `.env.example` 中的对应环境变量，按原启动方式重新启动应用。生产环境仍需提供稳定邮件密钥并开启 HTTPS/Secure Cookie。

本地 outbox 模式从指定 JSONL 文件查看 `type=password_reset_code` 的 `code`，不会发送真实邮件。该文件属于测试邮箱，含明文验证码，应限制访问；队列数据库使用 AES-GCM 加密正文，终止投递后清除密文。

HTTP 网关需要支持新载荷 `{messageId,type:"password_reset_code",email,code,expiresAt}`，`code` 是字符串。自动重试使用相同消息标识、验证码和到期时间，不延长有效期。完整渠道与密钥说明见 [邮件投递](mail-delivery.md)。

## 升级与验收

新增 V11 迁移，保留账号及旧重置令牌数据；旧链接在原有效期内继续可用，新的申请或任一种改密成功都会使其他方式失效。升级前备份数据库和对应邮件密钥，回退需要匹配的数据库备份。

自动化测试使用隔离 H2/MySQL、回环 SMTP/HTTP 服务和本地 outbox，覆盖验证码流程、错误次数、限流、并发一次性消费、旧链接兼容、密钥轮换及会话撤销。真实 QQ 邮件验收需在授权码配置完成后单独执行并由收件人确认，不能用自动化测试成功代替。

### 2026-11-01 验收记录

| 检查 | 结果 |
|---|---|
| Java 完整回归及打包 | 132 项：131 通过、1 项可选 Docker 模型网关测试未启用；含真实临时 MySQL 8.4 升级验证 |
| 最终找回密码、邮件和认证回归 | 60 项全部通过；含密钥缺失不扣用户错误次数、旧链接、并发、会话撤销、SMTP/HTTP/outbox、文件 H2 升级与重开、登录注册及生产安全配置 |
| 前端静态检查 | 88 个 JavaScript 模块语法、17 个 Vue 模板通过 |
| 状态逻辑和浏览器模拟 | 非 QQ 邮箱、倒计时、429、验证码过期、密码确认、刷新、晚响应隔离、清除敏感输入、移动端布局、旧链接兼容通过 |
| 最终 JAR 的真实浏览器流程 | 隔离数据库注册非 QQ 邮箱 → 本地 outbox 收码 → 验证 → 刷新第二步 → 改密 → 新密码登录通过；旧密码和两个旧会话失效；调试日志没有本轮验证码、密码或改密授权 |
| 真实 QQ SMTP 收件 | 已通过：2026-11-01 23:36（北京时间）通过网站申请验证码，投递日志确认 `transport=smtp, result=provider_accepted`，随后由收件人确认已收到；验证码和授权码未写入验收记录 |

可复验命令：`npm run check`、`npm run test:password-recovery`；使用 JDK 21 构建后运行 `npm run test:password-recovery:integration`，该命令创建独立随机端口、文件 H2 和本地 outbox，不使用日常站点。Java 测试使用项目 Maven Wrapper；MySQL 迁移用例需要专用临时 `cryptobench_test_*` 数据库。

本次真实投递排障确认：从 VS Code 直接启动 Java 会绕过 `scripts/run.ps1` 的本机加密邮箱配置加载。已正常关闭该实例并通过标准启动脚本重新启动；随后 SMTP 投递及收件均成功。此电脑后续请通过 `start.cmd` 启动，避免重新落入默认 outbox 模式。

# 密码重置验证码邮件

找回密码采用邮箱验证码：输入注册邮箱并获取 6 位数字验证码，验证通过后填写新密码和确认密码，成功后返回登录页。收件地址支持通用邮箱，不限制 QQ；QQ SMTP 只是网站的发件通道。验证码有效期 10 分钟，验证后的一次性改密授权有效期 5 分钟。新申请使旧验证码、旧改密授权失效，并取消之前尚未完成的消息。重置成功后撤销该账号全部登录会话，不自动登录。注册和重置密码均保持 8–24 位规则。

升级前已经发出的重置链接继续按原 30 分钟有效期工作，但新申请或任一种重置成功都会使其他凭证失效。操作、接口和验收说明见 [邮箱验证码找回密码](password-recovery.md)。

## 配置投递方式

所有新配置使用 `benchmark.mail` 前缀，可通过 Spring 配置文件、命令行属性或项目 `.env.example` 列出的环境变量设置。默认 `mode=auto` 仅迁移旧行为：先选择已配置的 HTTP 网关，其次选择本地 outbox，两者均没有时禁用。SMTP 必须显式设置 `mode=smtp`，仅填写 SMTP 主机不会切换真实发信通道。

| 属性 | 作用与默认值 |
|---|---|
| `mode` | `auto`、`smtp`、`http`、`outbox`、`disabled`，默认 `auto` |
| `delivery-url` | HTTP 邮件网关地址；为空时兼容 `benchmark.password-reset.delivery-url` / `PASSWORD_RESET_DELIVERY_URL` |
| `outbox` | 本地 JSONL 测试邮箱路径；为空时兼容旧 `PASSWORD_RESET_OUTBOX` |
| `smtp-host`、`smtp-port` | SMTP 主机和端口，默认端口 587 |
| `smtp-username`、`smtp-password` | SMTP 登录账号和密码/服务商授权码，支持无认证的受控测试服务器 |
| `from` | SMTP 发件人邮箱，SMTP 模式必填 |
| `security` | 可选 `ssl`、`starttls`、`none`；也可通过 `SMTP_SECURITY` 设置，优先级高于下方两个兼容开关 |
| `smtp-starttls` | 默认 true，要求服务器提供 STARTTLS |
| `smtp-ssl` | 默认 false；开启时使用隐式 TLS，取代 STARTTLS，通常配合端口 465 |
| `key` | 可选，Base64 编码的 32 字节 AES 密钥；优先于密钥文件 |
| `key-file` | 默认 `var/secrets/mail.key`，内容是 Base64 编码的 32 字节密钥 |
| `previous-keys` | 密钥轮换时的旧 `keyId: Base64密钥` 映射 |
| `worker-enabled` | 默认 true；false 暂停自动领取邮件，适合维护和隔离测试 |
| `poll-ms` | 默认 5000，轮询间隔 |

SMTP 配置示例（密码从部署 secret 注入，不写入版本控制）：

```yaml
benchmark:
  mail:
    mode: smtp
    smtp-host: smtp.163.com
    smtp-port: 465
    smtp-username: nsslab@163.com
    smtp-password: ${MAIL_SMTP_PASSWORD}
    from: CryptoBench <nsslab@163.com>
    security: ssl
    key-file: /run/secrets/cryptobench-mail-key
```

HTTP 网关收到 JSON，通用字段为 `messageId`、`type`、`email`、`expiresAt`。验证码邮件 `type=password_reset_code` 增加字符串 `code`（保留前导零），不包含 `token` 或 `resetUrl`。`Idempotency-Key` 请求头与 messageId 相同，网关应据此去重。2xx 表示接受；408、429、5xx 及网络临时错误会重试，其他 HTTP 失败终止本次消息。网关地址只由部署配置指定。

SMTP 4xx 响应按临时失败重试，5xx 响应、认证失败和无效收件地址终止本次消息。失败状态只保留 `smtp_451`、`smtp_550` 等固定错误码，不保存服务商返回的正文或收件人细节。

outbox 模式把同样的 JSON 追加到指定文件，状态为 `local_outbox_written`。它不发送到真实邮箱，文件中包含能使用的验证码或临时链接，只适合受控本地联调，应限制访问并在联调结束后清理。队列终止时清除数据库密文，不会替用户删除已写入的 outbox 文件。

## 密钥、队列与恢复

验证码挑战、失败次数、发送限流、一次性改密授权和邮件投递队列均保存在 MySQL。单实例本地部署不需要 Redis；数据库事务和行锁已经覆盖一次性消费与并发校验。只有在需要跨多实例共享高频缓存时，才值得另行评估 Redis，并应保留数据库作为最终状态。

随机链接和改密授权只存摘要。六位验证码则使用用途隔离的 HMAC-SHA256，密钥通过 HKDF-SHA256 从稳定邮件密钥派生，校验值绑定挑战、账号和用途，避免仅凭数据库就枚举数字码。为支持重启后重试，队列中的邮件正文使用 AES-256-GCM 加密，消息 ID、用途和关联记录作为附加认证数据。API 和普通日志不输出验证码、授权或密文。

在非 prod 环境中，第一次启用邮件功能会在密钥路径生成稳定的本地密钥文件，设置仅文件所有者可访问的权限，后续启动复用该文件。密钥目录不进入 Git。不要每次启动更换密钥，否则已有队列不能解密。

在 `prod` profile 下不会自动生成密钥，必须提供 `key` 或预先挂载的 `key-file`。缺少通道或密钥时，邮件功能显示未就绪；忘记密码对所有邮箱统一返回暂不可用，不生成无法投递的新验证码，也不透露账号存在与否。

轮换时设置新主密钥，并通过 `previous-keys` 暂时保留旧密钥。keyId 为密钥 SHA-256 的前 32 个十六进制字符。等旧消息成功、失败、取消或过期并清除密文，且旧密钥下仍有效的验证码均已消费或过期后，再移除旧密钥。密钥丢失或无法认证的消息会失败并清除正文，需要重新发起密码重置。

每次领取有 60 秒租约，进程中断后租约到期可再次领取；每条消息最多 5 次尝试，四次重试的间隔依次为 1、5、15、30 分钟，且不越过验证码有效期。消息到期、最终失败、取消或被邮件服务接受后清除密文、nonce、keyId。调用网络服务不持有账号事务或邮件锁。

外部发送与本地数据库不能构成一个事务。如果外部服务已接受但本地尚未记录成功时进程崩溃，可能再次尝试。HTTP 网关应支持幂等键；SMTP 使用稳定 Message-ID，但 SMTP 和本地 JSONL 不能保证绝对不重复。验证码和改密授权始终一次性使用。

备份数据库与邮件密钥时分别保管，并在恢复时使用匹配密钥。验证码不能在哈希丢失后重建，需要由用户重新发起找回密码。

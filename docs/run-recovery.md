# 运行、恢复与报告接口

运行保存服务端生成的 `cryptobench-execution-plan-v1` 配置。数据库只保存公开任务信息、参赛方标识、能力版本和价格快照；Agent Bearer Token、模型 API Key 只在执行进程内存中使用。旧运行没有完整快照时保留记录，不推断旧费用或把当前价格伪装成历史价格。

## 三种操作

| 操作 | ID / 结果 | 配置和费用 |
| --- | --- | --- |
| 继续执行 | 原 Run ID，保留已保存的通过/未通过题目 | 原种子、目录、评分器、价格快照 |
| 按原配置重试 | 新 Run ID，全部重新执行，记录 retryOf | 继承原配置和价格快照；客户端额外配置字段不会合入 |
| 新建实验 | 新 Run ID，允许修改条件 | 创建时取得当前价格快照 |

`GET /api/runs/{id}` 返回 `seed`、安全 `config`、进度、任务状态、`pauseReason`、`credentialsRequired`、`canResume`。该接口只对运行所有者开放。网页路由 `#/evaluate?run=<id>` 或 `#/arena?run=<id>` 能在刷新后重建页面；此链接不赋予其他账号访问本人运行的权限。

`POST /api/runs/{id}/resume` 用于暂停运行；`POST /api/runs/{id}/retry` 用于已完成、失败、超时或已取消运行。无凭证的重试兼容空请求体，需要凭证时发送：

```json
{
  "credentials": {
    "main": {"token": "current-session-agent-token"},
    "gateway": {"token": "current-session-model-api-key"}
  }
}
```

PK 使用 `a`、`b` 替代 `main`。仅发送界面列出的参赛方。重复继续正在排队或执行的运行返回现状，不再启动第二个执行者。

## 暂停与不确定结果

| 原因 | 处理 |
| --- | --- |
| credentials_required | 补录本次执行凭证 |
| worker_restarted | 执行进程关闭或连接中断，确认任务状态后继续 |
| catalog_changed | 原目录/评分器不能匹配，基于配置新建实验 |
| agent_changed | Agent 版本或能力不再兼容，基于配置新建实验 |

已提交任务的结果不确定时，任务记录为 `interrupted`。继续可能再次调用模型，不能保证第三方计费只发生一次。只有 Agent 声明 `independentTasks: true`、`idempotency: true`，且能力版本仍匹配时才继续已开始的运行；旧 Agent 可取消后按原配置重试。

Agent `/analyze` 请求包含公开的 `task.input`、`task.question`、运行 ID、参赛方、题目 ID、尝试次数、绝对截止时间，以及稳定的 `idempotencyKey = runId:participant::taskId`。标准答案和评分规则不发送给 Agent。实现幂等的 Agent 应按稳定标识缓存并复用已确认结果。

运行先与全部任务记录在一个事务中保存，后台按可用容量从数据库领取。有效租约和执行代次保护结果写入。取消会使旧代次失效；任务进度不能把取消反转。完成时最终报告和运行终态在同一事务内提交。进程异常退出后未过期租约会先等待到期，随后暂停；未开始的排队记录继续调度。

## 报告与费用

`GET /api/reports/{id}` 支持游客读取公开报告，私有报告仅所有者可见。前端直接按路由 ID 加载，查看他人报告不会写入“我的报告”。响应的 `permissions` 只描述当前访问者可见操作，服务端仍在同步和公开接口重新授权；JSON 导出会剔除这个临时字段。

报告保存 `evaluatorVersion`、公开 `catalogSha256`、`pricingSnapshot`、`pricingSnapshotHash`、`costCurrency` 和逐题 `costStatus`。未知费用使用 null，不把缺单价或缺用量视为免费。历史报告不重新计价。

测评由运行网站后端的本机执行。`usageSource` 为 `agent_reported` 或 `provider_gateway`；服务端验证答案，Agent 自报的模型身份、硬件和资源用量不是独立认证结果。网关缺少完整供应商用量时费用保持未知，不与 Agent 自报数字相加。历史报告保留原执行位置和用量来源，不改写成新的本机执行结果。

## 升级与回退

升级前停止接收新实验，等待或暂停现有任务，并备份数据库。只执行新增迁移；不要修改已经部署过的 V1～V4。部署邮件队列后，加密密钥应独立备份并保持稳定。回退需要旧应用与配套数据库备份，单独替换 JAR 不能撤销数据库迁移。

2026-11-02 移除设备接入功能：保留历史 V10 迁移，新增 V12 删除三张设备专用表。旧设备运行和报告保留；仍排队的旧设备运行暂停为 `executor_removed`，已有暂停记录保留原原因，执行中断记录按原重启恢复规则暂停。所有旧设备运行仍可查看，未结束的可取消，继续和原配置重试返回 `409`。新建运行仅支持本机执行，指定其他 `executorType` 返回 `400`；如需重做旧实验，应新建运行并填写本机 Agent 地址或 Docker 镜像。

验证使用隔离 H2/MySQL、测试 HTTP Agent、测试 SMTP 和模型替身。生产 HTTPS 和真实邮箱到达情况需要在相应环境单独验收；不使用本地模拟结果替代这些记录。

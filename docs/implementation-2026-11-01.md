# 六项功能实施记录

> 本文记录 2026-11-01 的历史实施与验收。2026-11-02 按用户要求移除“我的执行设备”、Bridge 客户端、设备协议及其交付入口；下文 Bridge 测试属于历史记录，不代表当前版本仍支持。HTTP Agent、Docker 和模型网关继续保留。

基线：`feat/backend-update` 当前工作区，保留用户已有未提交改动。

本记录跟踪用户批准的第 3、5、6、8、9、11 项完整计划。未通过对应验收的项目不算完成。

| 项目 | 实施范围 | 状态 |
| --- | --- | --- |
| 3 | JSON 内联自定义题库、精确 JSON / 数值评分、私有材料隔离、发布与浏览 | 已实现，API/浏览器/实际评分通过 |
| 5 | 执行快照、数据库调度、暂停/继续/重试、凭证补录、前端恢复 | 已实现，真实应用关闭重启及并发状态测试通过 |
| 6 | 按链接加载公开报告、权限与错误状态、导出 | 已实现，游客访问及真实页面流程通过 |
| 8 | USD 多模型价格、版本指针、BigDecimal、每次运行固定快照 | 已实现，多模型管理、调价/重试/恢复测试通过 |
| 9 | SMTP / HTTP / 本地邮箱、加密持久队列、邀请列表/重发/撤销 | 已实现，三种测试渠道及故障重试通过 |
| 11 | 用户侧 Bridge、设备协议、Docker 模型网关、Windows 程序 | 已实现，设备协议、DPAPI、Docker 链路通过；真实双机待验收 |

## 范围约定

- 保留 8～24 位密码策略，不修改现有账号或日常运行数据库。
- 本轮不实现 V4 正式评分材料、附件 ZIP、上传评分代码、跨部署报告同步或 PK 双方独立设备配置界面。
- 模型网关采用用户自带密钥、OpenAI 兼容协议；密钥仅保留在运行内存。
- Bridge 为手动启动/退出程序，不安装系统服务。
- 新增 Flyway 迁移，保留既有 V1～V4；H2/MySQL 都需验证。
- 自动测试使用隔离数据库及邮件/模型替身，不发送真实邀请、不消耗模型额度。

## 迁移分配

- V5/V6：自定义题库结构和旧数据迁移。
- V7：价格版本指针和修订号。
- V8：邀请与邮件队列。
- V9：运行恢复与任务尝试。
- V10 起：Bridge 设备、配对与任务租约。

## 验收记录

本轮在 Windows / Java 21 / Docker 29.9.2 环境执行。测试未访问日常 H2、未发送真实邀请、未调用付费模型。下列场景为实际运行结果，不以源码检查代替集成测试。

| 验证 | 证据与结果 |
| --- | --- |
| Java 全套 | `mvnw.cmd ... package`：106 项，0 失败、0 错误、1 项默认跳过（Docker opt-in）；日志 `target/full-pause-package.log`。包含临时 MySQL 实测。最终 Java 改动定向回归通过（`target/final-refresh-package.log`），Docker 另实跑通过，因此本轮 106 个检查均有通过记录。 |
| 题库与隔离 | 规则、API、H2 历史迁移通过；测试十进制精度、容差边界、重复 ID、2,000,000 字节上限、非法规则、旧包只读及标准答案不进入公开响应。公开摘要只覆盖白名单内容，私有包完整性摘要独立保存。 |
| 执行恢复 | `RunRecoveryIntegrationTest` 通过：种子 0/987、激活新价后重试旧价、补录凭证、保存两题后继续、旧尝试拒写、旧 Agent 不宣称断点续跑、26 个任务运行排队不丢失、PK 分别凭证及旧无快照费用未知。 |
| 真正关闭重启 | `RunApplicationRestartIntegrationTest` 通过：完整 Spring 应用及连接池关闭，再启动新上下文读取同一临时文件 H2；前两题结果/耗时/费用保持，补凭证后第三题再次尝试，原种子/价格及唯一报告保持。不是直接调用 pause 的替代测试；尚未做强制杀进程测试。 |
| 报告/前端状态 | `tests/run-state.mjs`、`tests/workflow-browser.mjs` 通过：路由竞争、身份切换、种子 0、轮询可见性、断网恢复、凭证清空、报告权限/导出、暂停设备禁选、费用原因与用量来源展示。 |
| 真实页面主流程 | `tests/run-workflow-integration.mjs` 通过：真实 JAR + 独立 H2 + loopback Agent；管理员发布题库、复制多模型价格并激活、seed 987 运行及刷新、100 分 / USD 0.00003、报告导出/同步/公开/游客访问、同配置重试、邀请重发和撤销。最终第二轮新增匿名报告/公开题库实际下载及来源文案检查也通过，日志 `target/workflow-integration-RxJ1T4/server.log`；测试进程已关闭。 |
| 价格 | 价格生命周期、快照计算、并发启用、旧非 USD 兼容及界面测试通过。缺模型/用量返回 null，合法零价为 0。 |
| 邮件 | `MailTransportTest` 6 项、`MailDeliveryIntegrationTest` 11 项、`MailCipherTest` 3 项通过：测试 SMTP/HTTP/outbox、451 重试与550永久失败、1/5/15/30 分钟间隔、租约恢复、一次性链接、撤销、加密与正文清理。 |
| Bridge | API、客户端、实际 Run 调度与模拟设备联调通过：跨账号隔离、配对/撤销、断线暂停/续跑、重复结果、迟到拒收、暂停接收保留当前任务、Windows DPAPI、设备独占锁、停止等待及过期任务不执行。 |
| 模型网关 | `tests/model-gateway.mjs` 通过普通 JSON/SSE、预算/模型/期限限制、取消、无自动重试、用量缺失。`GatewayRunIntegrationTest` 4 项通过：供应商 11+7 tokens 覆盖 Agent 自报值、按价格映射计算 USD 0.000078、用量缺失为 null、20/40 次预算、秘密脱敏及结束/取消关闭资源。 |
| 实际 Docker | `ModelGatewayDockerTest` 最终于 21:06:04 实跑通过，1 项无跳过、10.76 秒：Java 启动 sidecar、internal Agent 网络到测试供应商、Key 不在 Docker 配置、跨实例保护；有效运行租约保留且仍能调用模型，失效资源容器/网络全部回收。日志 `.tools/gateway-docker-test.log`。 |
| MySQL | 官方 MySQL 8.4 一次性容器，全新 `cryptobench_test_*` 库先迁到 V4 并写历史样例，再升级至 V10；验证私有材料迁移和新表。临时容器及匿名卷已删除。没有改原 V1～V4。 |
| 静态与页面 | `npm run check`：82 个 JS 模块语法检查、16 个 Vue 模板编译通过；题库浏览器及多模型价格 UI 测试通过；Compose 基础/sandbox 配置解析通过。 |
| Windows 产物 | 最终 `target/release-package.log` 打包 exit 0；`tests/bridge-app-image.ps1` 通过专用入口、限定时间退出、未启动网站/创建网站数据库检查。网站与 Bridge 内嵌 JAR 的 SHA-256 均为 `28F003F1C1BB16DE0DE4D9182DFECB04C7AFAA8F8B3C63CC70BA8FDB446AC96D`。 |

沙箱曾限制 Chrome/Node 子进程、Docker named pipe 和 Windows 私有 ACL 设置。对应检查经自动审批使用正常本机权限执行通过；没有放宽生产 ACL、DPAPI、设备认证或容器限制来绕过测试。

## 交付入口

- [自定义题库格式](custom-datasets.md)、[运行恢复/API/升级](run-recovery.md)、[价格与快照](pricing.md)。
- [邮件配置/密钥备份](mail-delivery.md)、[模型网关](model-gateway.md)。
- 网站启动沿用 `start.cmd`。Bridge 的启动脚本、打包入口和便携程序已移除。
- Java 自动化使用独立数据库。真实浏览器测试须使用 `tests/run-workflow-integration.mjs` 创建的隔离服务，不能把测试账号操作指向日常环境。

## 当时尚待相应环境验收

以下为历史待验收项；Bridge 移除后，第 1、2 项不再属于当前交付范围。

1. 两台独立 Windows 电脑经实际 HTTPS 部署完成 HTTP Agent 和 Docker/网关测评，包括跨网络中断再连接。当前模拟设备联调不算真实双机验收。
2. 打包 Bridge 的人工窗口操作、安装器安装/卸载与真实用户电脑 Docker 版本兼容性。DPAPI、运行生命周期、打包入口自检属于已执行自动检查，不能替代这些人工步骤。
3. 生产 SMTP/邮件网关的实际收件箱到达、TLS/授权码，以及真实模型供应商额度和账单。自动化只使用替身服务，`provider_accepted` 不等于送达。
4. 用户现有生产数据副本的备份恢复演练和进程强制终止故障演练。当前 H2/MySQL 历史样例迁移、完整应用关闭重启已经通过。

上述环境检查未执行的部分保留为待验收；不宣称已经部署上线或完成真实双机运行。

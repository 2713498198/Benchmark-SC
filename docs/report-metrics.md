# 报告耗时与分类成绩

新报告保留 `time` 的历史累计耗时含义，同时添加 `totalTimeSeconds`（任务累计秒数）和 `averageTimeSeconds`（平均每题秒数）。普通报告和 PK 每一方均独立计算；PK 顶层摘要继续对应 A 方。

累计值只包含本次实际选题的最终持久化 `elapsedMs`，包含失败答案，不包含排队、容器准备和暂停等待。同一道题恢复后的结果只计一次；平均值除以该方实际选题数量，快速体验不会使用题库总题数。缺失或无效的逐题计时使新耗时字段为 `null`；零耗时有效。

排行榜、性能图、提示框和对比弹窗统一使用平均每题耗时。报告同时展示平均和累计值。历史报告优先读取明确的新字段；缺少这些字段时，只有完整、无重复且计时有效的逐题结果才能推导。仅有旧 `time` 时显示“未知”，不会把累计值当成平均值。内置演示成绩明确标注两种耗时。

分类来自本次选题的 `TaskDefinition.category`，去除首尾空格，空分类归入“未分类”，顺序按本次选题首次出现。每类包含 `name`、`passed`、`total`、`score`，通过率保留一位小数，各类总数之和等于该方选题数。新逐题结果带 `category` 和 `participantKey`，PK 双方均带自己的分类；页面按分类名称对齐双方的数量和通过率。

历史分类优先展示报告已有汇总；只有全部逐题结果都有分类和判定时才推导分类，否则保留“全部任务”。不使用当前题库反推旧题分类，不改写历史原始 JSON，不增加数据库迁移。

榜单 CSV 保留旧 `time_s` 列，末尾追加 `total_time_s` 和 `average_time_s`。逐题 CSV 追加 `category` 和 `participant_key`，JSON 导出保留原始字段。

## 验证

```powershell
npm ci
npm run check
npm run test:report-metrics
.\start.cmd --batch-mode verify
npm run test:workflow:mock
npm run test:workflow:integration
```

首次运行浏览器测试可执行 `npx playwright install chromium`。测试优先使用已安装的 Chrome，也支持 `CHROME_PATH`；CI 安装 Playwright Chromium。集成脚本自动创建独立 H2 数据库、测试管理员、本地 HTTP Agent 和测试 outbox，不使用日常数据库或真实邮件。覆盖多分类、失败答案、PK 双方、报告展示、榜单、图表、对比和导出；恢复测试核对最终保存的任务计时，防止重复累计旧尝试。

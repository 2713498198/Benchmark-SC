# 模型价格与运行快照

每个价格版本是一张完整价格表，保存后不可修改。管理员可以在保存前添加、删除和修改多行，也可以复制当前完整价格表到新版本。启用前显示新增、调整、移除模型的差异；移除模型需要明确确认。模型名称统一大小写、连字符、下划线和空白后判重。

新版本仅接受 USD，价格以 `DECIMAL(20,8)` 保存，不进行自动汇率转换。API 的价格金额使用十进制字符串，避免浏览器和 JSON 往返丢失精度。费用计算使用 `BigDecimal`，按输入/输出 Token 单价计算后四舍五入至 6 位小数，再通过兼容的 Double 接口返回。缺少价格或有效用量时费用为 null，而非零；实际零用量对应零费用。

`benchmark_pricing_state` 的唯一记录保存当前版本和递增 revision。启用操作在同一事务中锁定该行、核对差异预览的 revision、更换当前版本并保存管理员审计。两个管理员依据相同 revision 启用时只有一个成功，另一个收到 HTTP 409，需重新预览。版本保存与审计也在同一事务中完成。

API：

- `GET /api/pricing/active`：公开当前完整价格及 activeRevision，不返回创建者身份。
- `GET /api/admin/pricing`：管理员查询全部保存版本。
- `POST /api/admin/pricing`：保存 `{version, prices:[{model,inputPerMillionTokens,outputPerMillionTokens,currency:"USD"}]}`；不存在修改已保存版本的 PUT/PATCH 接口。
- `GET /api/admin/pricing/{version}/diff`：返回新增/移除/调整项与 expectedRevision、expectedTableRevision。
- `POST /api/admin/pricing/{version}/activate`：新客户端发送预览中的两个 revision。旧客户端不传 body 的调用仍兼容，但不具有用户预览级别的冲突确认语义。

`ServerCostCalculator.snapshot()` 返回新建的 JSON 对象，包含 schema、version、revision、currency、capturedAt、snapshotHash 和完整 prices。snapshotHash 对递归按字段名排序的 UTF-8 JSON 计算 SHA-256（排除 hash 字段自身，包含本次 capturedAt）；新建运行生成一次，恢复与重试保留原快照。金额保存为十进制字符串。调用方应在创建运行时由服务端取得并持久化该快照，后续每个任务、恢复、重试和报告只使用这份快照；不要采用客户端提交的价格快照。`calculate(snapshot, model, response)` 和 `costStatus(snapshot, model, response)` 不读取当前价格。

费用状态：`calculated`、`missing_snapshot`、`missing_price`、`missing_usage`、`invalid_usage`、`unsupported_currency`。旧运行没有快照时不能将当前价格冒充当时价格；历史报告不回算。既有非 USD 数据仍保留原币种，不能作为 USD 计费，也不能直接再次启用；复制它时必须重新填写确认过的 USD 金额。

数据库迁移 V7 从旧 active 行确定一个当前版本；已有价格和报告金额保持不变。原多个 active 行仅整理状态，不删除历史内容。

验证使用独立内存 H2，不访问用户数据库、不发送邮件或真实模型请求：

```text
mvnw.cmd -Dtest=ServerCostCalculatorTest,PricingApiIntegrationTest,PricingLifecycleIntegrationTest test
node tests/pricing-admin.mjs
```

测试覆盖固定快照、JSON 精度、缺失费用、旧币种、非法输入事务回滚、管理员审计、并发启用冲突、保存后不可修改，以及前端多行复制和启用确认。

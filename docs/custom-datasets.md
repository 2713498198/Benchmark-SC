# 自定义题库

管理员在“管理后台 → 测评集管理”使用 JSON 模板导入、服务端校验并发布固定版本。此流程支持 JSON 内联输入；不接受附件 ZIP、文件路径或上传评分脚本。V4 正式材料和验证器不属于此功能。

```json
{
  "schema": "cryptobench-custom-dataset-v1",
  "id": "custom-example",
  "version": "1.0.0",
  "name": "结构化结果样例",
  "type": "分组密码",
  "algorithm": "教学 SPN",
  "description": "检查公开输入中的轮数。",
  "tasks": [{
    "id": "rounds-001",
    "question": "返回公开输入中的轮数。",
    "input": {"rounds": 4},
    "category": "结构识别",
    "verification": {"type": "exact-json-v1", "expected": 4}
  }]
}
```

`id` 为 2 至 61 位小写字母、数字及连字符，首位为字母。内置题库 ID 不可使用。版本为不含前导零的三个数字段，例如 `1.10.0`，同 ID/版本不可覆盖。每包 1 至 1000 道任务，实际 UTF-8 请求体不超过 2,000,000 字节；公开输入和标准答案的 JSON 嵌套不超过 32 层。题目 ID 不重复，最多 120 字符，允许字母、数字、点、下划线和连字符。

任务必须提供 `question`、对象类型的 `input` 和 `verification`。任务 ID 的唯一性不区分大小写，例如 `Task-A` 与 `task-a` 不可出现在同一版本中，以兼容 MySQL 默认排序规则。可选 `category`、`requiredTools`、`requiredCapabilities`。整个包可选 `difficulty`。未知字段会被拒绝，旧版 `objective/expected` 格式需按模板显式转换。

Agent 收到公开 `task.question`、`task.input` 等内容，在 `artifact.answer` 中返回答案，状态应为 `completed`。私有 `verification` 不随请求发送。

- `exact-json-v1`：JSON 精确比较。对象键顺序不影响比较，数组顺序影响比较，字符串与数字不同；数值 `1` 与 `1.0` 等价。缺少 answer 与显式 answer=null 不同。
- `numeric-v1`：expected 和 answer 均为有限数字；可指定非负 `absoluteTolerance` 与 `relativeTolerance`，默认均为 0。通过条件为 `abs(answer - expected) <= max(absoluteTolerance, relativeTolerance * abs(expected))`。使用十进制计算边界。

每题通过计 1 分；未通过计 0 分。公开 `catalogSha256` 仅对公开目录内容计算 SHA-256，不包含隐藏答案和私有规则。完整包另有仅服务端保存并校验的私有指纹；评分器版本为 `declarative-evaluator-1.0.0`。发布、持久化、Agent JSON 传输和评分保留十进制精度，避免高精度数值经浮点舍入误判。

| API | 行为 |
| --- | --- |
| `POST /api/datasets/custom/validate` | 管理员预检；返回 valid、字段 errors 和仅含公开内容的 preview，不写数据库 |
| `POST /api/datasets/custom` | 管理员发布；重新校验，201 返回公开摘要，重复版本返回 409 |
| `GET /api/datasets/{id}/versions/{version}/catalog` | 公开题目目录，包含 dataset 摘要及公开 tasks |

数据库通过后续 Flyway 迁移增加私有包表，并把已有混合内容迁入私有表。旧题库保留 ID、版本和公开浏览内容，标记为 `legacy_unbound`，不会自动把旧 `expected` 描述当成可评分答案。管理员需要发布新版本才能执行。旧 Node 数据导入也采用同一隔离方式。

公共初始化接口、发布响应、预览和目录只使用白名单投影；不返回 `taskPackage`、`verification` 或标准答案。数据库私有包只供服务端评分，不应被放进静态目录或浏览器存储。

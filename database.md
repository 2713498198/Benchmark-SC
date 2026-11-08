#### 数据库设计
##### 用户和登录
| 表名 | 作用 |
|---|---|
| `benchmark_users` | 保存用户账号、邮箱、密码哈希、角色和账号状态 |
| `benchmark_sessions` | 保存登录会话的 Token 哈希和过期时间 |
| `benchmark_connections` | 保存用户配置过的 Agent 地址、名称和模型，不保存访问凭证 |

用户登录成功后，后端会：
1. 生成一个随机的 64 位十六进制 Token。
2. 对 Token 做 SHA-256。
3. 把摘要、用户 ID、过期时间写入 benchmark_sessions。
4. 把原始 Token 放入浏览器 Cookie

##### 测评集和数据材料
| 表名 | 作用 |
|---|---|
| `benchmark_datasets` | 保存测评集的公开信息、版本、拥有者和执行状态 |
| `benchmark_dataset_packages` | 保存测评集的完整私有材料，例如隐藏任务和验证材料 |

benchmark_datasets 更像目录和摘要，benchmark_dataset_packages 保存受保护的完整内容。

#### 本地 Runner
| 表名 | 作用 |
|---|---|
| `benchmark_runners` | 保存已经配对的 Runner、所属用户、Token 哈希、版本、在线时间和能力信息|
| `benchmark_runner_pairings` | 保存一次性配对码的哈希、所属用户、有效期和使用状态 |
| `benchmark_runner_jobs` | 保存发给本地 Runner 的测评任务、租约、执行结果和错误信息 |


#### 详解benchmark_runners
| 字段 | 含义 |
|---|---|
| `id` | Runner 的唯一 ID，例如 `runner_xxx`，相当于 Runner 的身份证号 |
| `owner_id` | 所属用户 ID，表示这个 Runner 属于哪个网站账号 |
| `display_name` | Runner 的显示名称，例如“我的办公电脑 Runner” |
| `token_hash` | Runner 长期认证 Token 的 SHA-256 摘要 |
| `runner_version` | Runner 程序版本，例如 `1.0.0` |
| `status` | Runner 状态，例如 `online`、`revoked` |
| `agents_payload` | Runner 上可用 Agent 的公开能力信息，JSON 字符串格式 |
| `accepting_tasks` | 是否接受新任务，`TRUE` 表示可以接任务 |
| `created_at` | Runner 第一次配对成功的时间，Unix 毫秒时间戳 |
| `last_seen` | Runner 最近一次心跳或轮询的时间 |
| `revoked_at` | Runner 被撤销的时间；`NULL` 表示没有被撤销 |

#### 详解benchmark_runner_pairings
| 字段 | 含义 |
|---|---|
| `id` | 这条配对记录的数据库唯一 ID |
| `owner_id` | 配对码属于哪个网站用户 |
| `code_hash` | 配对码的 SHA-256 摘要 |
| `expires_at` | 配对码的过期时间 |
| `used_at` | 配对码实际被使用的时间 |

#### 详解benchmark_runner_jobs
网站每生成一道测评题，就会在这个表中创建一条任务记录；本地 Runner 从这里领取任务，执行后再把结果写回这里。
| 字段 | 含义 |
|---|---|
| `id` | Runner 任务的唯一 ID |
| `runner_id` | 负责执行该任务的 Runner ID |
| `owner_id` | 任务所属用户 ID |
| `run_id` | 所属测评运行 ID |
| `generation` | 测评运行的执行代次 |
| `task_id` | 具体任务 ID |
| `attempt` | 第几次尝试执行 |
| `status` | 任务当前状态 |
| `request_payload` | 网站发给 Runner 的任务内容 |
| `result_payload` | Runner 返回的执行结果 |
| `error_code` | 任务失败时的错误类型 |
| `error_message` | 任务失败时的详细错误信息 |
| `created_at` | 任务创建时间 |
| `lease_until` | 当前 Runner 对任务的占用截止时间 |
| `finished_at` | 任务完成、失败或取消的时间 |



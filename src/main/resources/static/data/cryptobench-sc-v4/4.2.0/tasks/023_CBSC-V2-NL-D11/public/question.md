# Question 023. 19 轮 SM4 密码分析复核

## 问题描述

背景。固定条件如下：
- 对象：SM4
- 轮数：19
- 任务类型：密码分析复核
- 分析模型：differential

任务。采用标准 SM4 round-core，在 single-key XOR-differential characteristic 模型下搜索一条 19 轮高概率 characteristic。状态和线性层定义为

```text
X_(i+4) = X_i XOR L(tau(X_(i+1) XOR X_(i+2) XOR X_(i+3)))
L(B) = B XOR ROL_2(B) XOR ROL_10(B) XOR ROL_18(B) XOR ROL_24(B),
```

其中 `tau` 是四个并行的标准 SM4 8-bit S-box。输入差分必须非零，输出自由；每个 S-box 转移的权重由标准 S-box 的精确 DDT count 计算，总 characteristic weight 为各轮局部权重之和。本题的判定目标是：能否构造并复放一条总权重不超过 123（概率至少 `2^-123`）的 19 轮 characteristic。若声称 123 是全局最优，还必须排除所有更低权重；否则只能报告已验证 witness 和当前边界。不要把单条 characteristic 概率写成聚合 differential probability。

## 交付要求

把完整证书保存为工作区根目录的 `characteristic.json`。一次提交对应一个可行性结论。证书有四个字段：

| 字段 | 内容 |
| --- | --- |
| `schema` | 固定字符串 `cbsc_sm4_19_difference_v1` |
| `claim` | 固定字符串 `feasible`，表示该见证在题定权重以内 |
| `state_words` | 按时间顺序给出 23 个 32 位状态差分字，先列初始四字，再依次列每轮新产生的一字；沿用题中递推顺序 |
| `substitution_outputs` | 依轮次列出 19 个 32 位差分字，每字对应四个 S-box 的输出、进入线性层之前的值，按原字的字节位置排列 |

字可以是无符号 JSON 整数，也可以是八位十六进制字符串（可带 `0x` 前缀）。证书使用 UTF-8 编码，大小上限为 16 KiB。运行归档时也可将同名文件放在运行根目录；如两处都有，内容须逐字节相同。请将其他说明放在单独的文件中。

验证器读取这两个数组，复查非零输入、全部 19 轮的衔接和每项差分的实际计数，再用整数精确比较整条概率和题定下限。通过即计为本题可验证完成。权重低于 123 的见证同样有效。可另附程序、模型、日志或报告记录求解过程。该证书只登记可行性，任何更强的最优性声明都需要单独证明。

## 沙箱工具与求解器说明

正式测试时，你只能看到本题公开题干和当前工作区。请不要读取 benchmark 包中的 `hidden/`、`oracle/`、`docs/` 或历史 `runs/`。

你可以在当前工作区中编写和运行代码。常见可用方式包括：

- Python 3 脚本、枚举程序、验证脚本或 replay/checker；
- SAT/SMT 工具或 Python 包，例如 `z3-solver`、`python-sat`、`cvc5`、`bitwuzla`、`kissat`、`cadical` 等；若当前沙箱未预装，可以在预算允许时自行安装或改用等价方法；
- MILP/LP/MIP 模型。若当前 run 启用了 Gurobi broker，可以通过环境变量 `CBSC_GUROBI_CLIENT` 提交 LP/MPS 模型。

可以先运行工具预检脚本：

```bash
python3 "$CBSC_SANDBOX_TOOL_CHECK" --json --out sandbox_tools.json
```

该脚本只报告当前沙箱中可见的 Python 包、SAT/SMT CLI 和 Gurobi broker 状态，不读取任何隐藏答案。

Gurobi broker 用法：

```bash
cat > model.lp <<'LP'
Maximize
 obj: x + y
Subject To
 c0: x + 2 y <= 4
Bounds
 0 <= x <= 10
 0 <= y <= 10
Generals
 x y
End
LP

python3 "$CBSC_GUROBI_CLIENT" model.lp \
  --param TimeLimit=600 \
  --output-json gurobi_result.json
```

`gurobi_result.json` 中会包含 `status`、`objective`、`best_bound`、`mip_gap_percent`、`solution_text` 和日志摘要。请在答案中说明 solver status 的含义：`OPTIMAL` 可以支持最优性结论，`FEASIBLE` 只说明找到可行解，`TIME_LIMIT` 或 `UNKNOWN` 不能单独证明最优性或不存在性。

无论选择哪种方法，请尽量保留：

1. 模型或程序文件；
2. 运行命令；
3. 关键日志或输出；
4. witness、trail、counterexample、proof、replay/checker 结果；
5. 最终结论及其适用边界。

# Question 077. 6 轮 SPECK32 VTDP 积分区分器代码复核

## 问题描述

背景。SPECK32 使用两个 16-bit word，6 轮 ARX 结构：x1=(ROR_7(x0)+y0) mod 2^16，x2=x1 xor k，y1=ROL_2(y0) xor x2。输入 activity mask 中 1 表示活跃比特，0 表示固定比特。

任务。使用 VTDP（三子集变体 division property）或严格等价模型，计算 6 轮后输出 bit 的 `0/*/?` pattern。SPECK32/64 状态由两个 16-bit words 构成，一轮定义为

```text
x1 = (ROR_7(x0) + y0) mod 2^16
x2 = x1 XOR round_key
y1 = ROL_2(y0) XOR x2
```

输入 mask 中 bit `1` 表示该 plaintext bit 遍历活动变量，bit `0` 表示固定，因此两个 mask 的 Hamming weight 均应由代码计算并转换为数据复杂度。canonical 输出顺序为第一个 16-bit word 后接第二个 word；每个 word 内按 MSB 到 LSB 打印，并写成 8 个 4-bit 分组。

输出分类采用：`?` 表示 VTDP 未得到 non-unknown 结论；`0` 表示可证明 even parity；`*` 表示 VTDP 得到 non-unknown/even-or-odd 信息但不归为 `0`。`*` 不是普通活跃 bit 通配符。

VTDP 必须同时跟踪 `K` 和保留重复项的 `L`，使用 variant XOR 规则；模加传播必须处理 carry 的非线性，不能把加法替换为 XOR。round-key XOR 也不能直接删除：`L` 中的信息可能生成向量并插入 `K`。仅有 K 的普通 two-subset model 不能作为第二个 case 的完整 VTDP 证据；严格等价的 SAT/SMT/MILP/BDD/代数模型可以接受，但必须说明映射。

对每个 case 的 32 个输出 unit vectors 分别查询或推导，再由逐 bit 日志自动生成 pattern。需要验证两个输入 mask：

1. (0xffff, 0xffdf)
2. (0xffff, 0xffbf)

对每个输入输出 canonical pattern、非 unknown bit 的 word-local LSB0 位置和解释。必须返回可执行代码和运行日志；不得硬编码最终位置，不得用随机抽样或部分明文枚举冒充完整积分证明，并明确结果是 6 轮积分区分器而不是完整 key-recovery attack。

## 交付要求

1. 返回可执行代码或可复核的形式化模型，形式可以是 Python、Sage、C/C++、MILP、SAT/SMT、Gurobi LP/MPS、验证脚本或等价程序。
2. 给出运行命令、关键输出和最终结论；最终结论必须直接回答题目要求的数值、YES/NO 判定、trail、概率/权重、表格项或证明边界。
3. 说明代码或模型如何对应本题固定条件，包括轮数、变体、bit/word 顺序、边界条件、目标函数或判定条件。
4. 对 solver 结果给出可信性解释：区分 `OPTIMAL`、`FEASIBLE`、`UNSAT/INFEASIBLE`、`TIME_LIMIT`、`UNKNOWN`，不要用不充分的 solver 状态过度声明。
5. 若未能完全求解，返回已经执行的代码、日志、当前最强边界、可行 witness 或反例，并明确说明未证明的部分。

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

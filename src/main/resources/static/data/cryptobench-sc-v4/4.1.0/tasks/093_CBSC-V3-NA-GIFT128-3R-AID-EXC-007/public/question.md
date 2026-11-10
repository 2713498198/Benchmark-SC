# Question 093. 3 GIFT-128 local truncated propagation

## 问题描述

背景。固定条件如下：
- 对象：GIFT 128
- 轮数：3
- 任务类型：local truncated propagation
- 分析模型：fault induced truncated cluster exception

任务。请只基于本题给出的条件和你本次运行产生的代码/计算结果作答。

Consider the 128-bit GIFT bit permutation below. Bits are numbered from 0 as the least significant bit. If input bit `i` is active before the permutation, then output bit `P[i]` is active after the permutation.

```
P = [
   96,  1, 34, 67, 64, 97,  2, 35, 32, 65, 98,  3,  0, 33, 66, 99,
  100,  5, 38, 71, 68,101,  6, 39, 36, 69,102,  7,  4, 37, 70,103,
  104,  9, 42, 75, 72,105, 10, 43, 40, 73,106, 11,  8, 41, 74,107,
  108, 13, 46, 79, 76,109, 14, 47, 44, 77,110, 15, 12, 45, 78,111,
  112, 17, 50, 83, 80,113, 18, 51, 48, 81,114, 19, 16, 49, 82,115,
  116, 21, 54, 87, 84,117, 22, 55, 52, 85,118, 23, 20, 53, 86,119,
  120, 25, 58, 91, 88,121, 26, 59, 56, 89,122, 27, 24, 57, 90,123,
  124, 29, 62, 95, 92,125, 30, 63, 60, 93,126, 31, 28, 61, 94,127
]
```

Use this truncated model for GIFT-128 rounds:

- The S-box layer is nibble-wise. If any bit in a nibble is active before the S-box layer, then the possible output support of that nibble is the full 4-bit nibble.
- The pLayer is the bit permutation `P` above.
- An admissible input difference is a full active 8-bit window `((1 << 8) - 1) << k` for one integer `k` with `0 <= k < 120`.

After exactly three rounds, enumerate the 120 admissible windows and answer:

1. How many values of `k` produce full 128-bit support after three rounds?
2. Which `k` values are they?
3. For these exceptional windows, should they be treated as forming a useful three-round truncated cluster, or as fully diffused cases? Give a reproducible derivation or check.

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

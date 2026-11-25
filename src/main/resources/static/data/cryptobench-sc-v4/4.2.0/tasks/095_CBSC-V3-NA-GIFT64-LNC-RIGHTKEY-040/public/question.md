# Question 095. 8-round core of related-key characteristic GIFT-64 differential dependency right key space

## 问题描述

背景。固定条件如下：
- 对象：GIFT 64
- 轮数：8 round core of related key characteristic
- 任务类型：differential dependency right key space
- 分析模型：gift64 linearised nonlinear constraint probability adjustment

任务。A reduced GIFT-64 related-key differential characteristic has a stated Markov
probability of `2^-50`. A value-restriction analysis finds the following
constraints on round-key bits for the characteristic to be compatible:

1. `k87 xor k95 = 0`
2. `k30 = 1`
3. `k68 = 1`
4. `k76 = 1`
5. `k80 xor k88 = 1`
6. `k17 xor k25 = 0`
7. `k4 xor k12 xor k38 xor k40 xor k80 xor k95 xor k121 xor k123 = 1`
8. `k12 xor k88 xor k122 xor k123 = 1`

All equations are over `F_2`. Treat the listed key-bit variables as independent
master-key coordinates unless the equations relate them.

Classify the following claims as `TRUE` or `FALSE`, with short reasoning:

| claim | statement |
|---|---|
| C1 | The eight listed constraints have rank 8 over `F_2`. |
| C2 | The right-key space is a `2^-8` fraction of the whole key space. |
| C3 | The remaining probability over right keys is still `2^-50`, because key constraints do not change the differential probability. |
| C4 | The remaining probability not explained by the key-space restriction is `2^-42`. |
| C5 | The two multi-round linearised nonlinear constraints may be ignored when estimating the right-key-space dimension. |

Return a table with rows `C1` to `C5` and a `truth` column containing only
`TRUE` or `FALSE`, followed by concise reasoning.

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

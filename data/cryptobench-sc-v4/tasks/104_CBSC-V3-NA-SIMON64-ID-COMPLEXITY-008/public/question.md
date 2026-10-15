# Question 104. 22-round attack parameter row SIMON-64-128 attack complexity replay

## 问题描述

背景。固定条件如下：
- 对象：SIMON 64 128
- 轮数：22 round attack parameter row
- 任务类型：attack complexity replay
- 分析模型：impossible differential key recovery complexity

任务。请只基于本题给出的条件和你本次运行产生的代码/计算结果作答。

You are given the following log-domain complexity model for an impossible-differential key-recovery attack.

Let the block size be `n`, the master-key size be `kappa`, the bit filters be `cb, cf`, the input/output difference dimensions be `db, df`, the number of involved guessed key bits be `kg`, and the surviving wrong-key exponent parameter be `g`.

Define

```
LG(g) = log2(g) - 0.53
Nexp = cb + cf + LG(g)
```

The data-complexity exponent is

```
D = max(
      min((Nexp + n + 1 - db) / 2,
          (Nexp + n + 1 - df) / 2),
      Nexp + n + 1 - db - df
    )
```

The three dominant time terms have exponents

```
T1 = Nexp
T2 = kg + LG(g)
T3 = kappa - g
```

Assume the partial-encryption cost factor is small enough that the final time exponent is

```
T = max(T1, T2, T3)
```

The memory exponent is

```
M = min(Nexp, kg)
```

For this parameter set

```
n = 64
kappa = 128
cb = 36
cf = 45
db = 46
df = 45
kg = 93
g = 30
```

compute the exponents `D`, `T`, and `M` to two decimal places. Also state which term dominates the time exponent. Give a reproducible calculation.

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

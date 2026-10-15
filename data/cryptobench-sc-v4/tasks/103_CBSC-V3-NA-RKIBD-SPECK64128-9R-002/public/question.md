# Question 103. 9 轮 Speck64/128 related key impossible boomerang verification

## 问题描述

背景。固定条件如下：
- 对象：Speck64/128
- 轮数：9
- 任务类型：related key impossible boomerang verification
- 分析模型：speck rk ibd
- word bits：32
- ROR/alpha：8
- ROL/beta：3

任务。请只基于本题给出的条件和你本次运行产生的代码/计算结果作答。

Use the standard Speck64/128 round on two 32-bit words:

```text
x_(i+1) = (ROR_8(x_i) + y_i mod 2^32) xor k_i
y_(i+1) = ROL_3(y_i) xor x_(i+1)
```

The 128-bit master key is written from most-significant to least-significant 32-bit words as `(l_2,l_1,l_0,k_0)`. Generate 27 round keys with

```text
l_(i+3) = (ROR_8(l_i) + k_i mod 2^32) xor i
k_(i+1) = ROL_3(k_i) xor l_(i+3),     i = 0,...,25.
```

The modular addition makes this key schedule nonlinear at the XOR-difference level. Derive round-key differences from concrete paired master keys or an exact related-key transition model; do not XOR-expand the master-key difference as if the schedule were linear.

Consider four master keys `K0,K1,K2,K3`. The two-related-keys quartet uses

```text
K0 xor K1 = kappa
K2 xor K3 = kappa
K1 xor K2 = 0
K0 xor K3 = 0
kappa = 0x00000080000000000000000000000000.
```

Thus only two distinct master keys occur, but all four quartet branches and their round-key sequences must be modeled. Analyze 9 rounds for

```text
alpha  = 0x0000000000000000
alpha' = 0x0000000000000000
beta   = 0x8000000080000004
beta'  = 0x8000000080000000.
```

A compatible RT3 related-key boomerang trail is a four-branch sequence satisfying the exact Speck rounds and key schedules above, the two input boundaries `(alpha,alpha')`, and the two output boundaries `(beta,beta')`. The tuple is an impossible boomerang distinguisher exactly when no such 9-round RT3 trail exists. An ordinary two-branch differential UNSAT check is not equivalent.

An accepted proof strategy may split the 9 rounds into a probability-one related-key differential prefix followed by a central RT3 impossibility check. If using that strategy, derive and connect the prefix and central boundary explicitly; merely asserting that such a prefix exists is not evidence.

Decide YES or NO and provide reproducible evidence: a contradiction/certificate over all compatible RT3 trails, an exact state/key-difference propagation verifier, or a concrete counterexample quartet. A timeout or failure to find a quartet is not a proof of impossibility.

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

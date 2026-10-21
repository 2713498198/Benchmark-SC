# Question 102. 15 and 16 Midori-64-style zero-key permutation invariant boundary check

## 问题描述

背景。固定条件如下：
- 对象：Midori 64 style zero key permutation
- 轮数：15 and 16
- 任务类型：invariant boundary check
- 分析模型：nonlinear invariant projection

任务。请只基于本题给出的条件和你本次运行产生的代码/计算结果作答。

Consider the following Midori-64-style permutation on 16 nibbles. The state is a list

```
x[0], x[1], ..., x[15]
```

where every entry is a 4-bit integer. The S-box is

```
Sb0 = [c, a, d, 3, e, b, f, 7, 8, 9, 1, 5, 0, 2, 4, 6]
```

`ShuffleCell` maps a state `x` to `y` by

```
ShuffleCell = [0, 10, 5, 15, 14, 4, 11, 1, 9, 3, 12, 6, 7, 13, 2, 8]
y[i] = x[ShuffleCell[i]]
```

`MixColumn` is applied independently to each group of four consecutive nibbles:

```
(a, b, c, d) -> (b xor c xor d, a xor c xor d, a xor b xor d, a xor b xor c)
```

The 15 round-constant rows are:

```
RC[0]  = [0,0,0,1,0,1,0,1,1,0,1,1,0,0,1,1]
RC[1]  = [0,1,1,1,1,0,0,0,1,1,0,0,0,0,0,0]
RC[2]  = [1,0,1,0,0,1,0,0,0,0,1,1,0,1,0,1]
RC[3]  = [0,1,1,0,0,0,1,0,0,0,0,1,0,0,1,1]
RC[4]  = [0,0,0,1,0,0,0,0,0,1,0,0,1,1,1,1]
RC[5]  = [1,1,0,1,0,0,0,1,0,1,1,1,0,0,0,0]
RC[6]  = [0,0,0,0,0,0,1,0,0,1,1,0,0,1,1,0]
RC[7]  = [0,0,0,0,1,0,1,1,1,1,0,0,1,1,0,0]
RC[8]  = [1,0,0,1,0,1,0,0,1,0,0,0,0,0,0,1]
RC[9]  = [0,1,0,0,0,0,0,0,1,0,1,1,1,0,0,0]
RC[10] = [0,1,1,1,0,0,0,1,1,0,0,1,0,1,1,1]
RC[11] = [0,0,1,0,0,0,1,0,1,0,0,0,1,1,1,0]
RC[12] = [0,1,0,1,0,0,0,1,0,0,1,1,0,0,0,0]
RC[13] = [1,1,1,1,1,0,0,0,1,1,0,0,1,0,1,0]
RC[14] = [1,1,0,1,1,1,1,1,1,0,0,1,0,0,0,0]
```

For a parameter `b`, adding a round-constant row means replacing every nibble `x[j]` by

```
x[j] xor (RC[r][j] << b)
```

Use zero whitening key and zero round keys. For `R` rounds, the permutation is:

```
initial whitening xor by zero key;
for r = 0..R-2:
    SubCell on all nibbles;
    ShuffleCell;
    MixColumn on the four consecutive columns;
    add RC[r] into bit b;
    xor zero round key;
final SubCell;
final whitening xor by zero key.
```

Define the output projection

```
G(x) = xor over j=0..15 of (bit0(x[j]) xor bit2(x[j]))
```

where `bit0` is the least significant bit of a nibble.

For `b = 1`, decide both of the following statements:

```
A. For R = 15, G(E_R(x)) xor G(x) is constant over all 64-bit inputs x.
B. For R = 16, G(E_R(x)) xor G(x) is constant over all 64-bit inputs x.
```

Report YES or NO for each statement. If a statement is YES, report the constant. Give a reproducible derivation or computation and include at least one sanity check for the zero plaintext.

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

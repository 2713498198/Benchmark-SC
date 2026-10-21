# Question 105. 14 轮 SKINNY-128 差分特征权重下界验证

## 问题描述

背景。固定条件如下：
- 对象：SKINNY 128
- 轮数：14
- 任务类型：差分特征权重下界验证
- 分析模型：large sbox sat probability bound
- 判定问题：exists characteristic with total weight <= 130

任务。请只基于本题给出的条件和你本次运行产生的代码/计算结果作答。

Analyze differential characteristics of 14 rounds of SKINNY-128. The state is a 4 x 4 byte array. A round applies SubCells, AddConstants/AddRoundTweakey, ShiftRows, and MixColumns. Constants and tweakey additions do not affect XOR differences.

Use this 8-bit S-box table, where the row is the high nibble and the column is the low nibble:

```
65 4c 6a 42 4b 63 43 6b 55 75 5a 7a 53 73 5b 7b
35 8c 3a 81 89 33 80 3b 95 25 98 2a 90 23 99 2b
e5 cc e8 c1 c9 e0 c0 e9 d5 f5 d8 f8 d0 f0 d9 f9
a5 1c a8 12 1b a0 13 a9 05 b5 0a b8 03 b0 0b b9
32 88 3c 85 8d 34 84 3d 91 22 9c 2c 94 24 9d 2d
62 4a 6c 45 4d 64 44 6d 52 72 5c 7c 54 74 5d 7d
a1 1a ac 15 1d a4 14 ad 02 b1 0c bc 04 b4 0d bd
e1 c8 ec c5 cd e4 c4 ed d1 f1 dc fc d4 f4 dd fd
36 8e 38 82 8b 30 83 39 96 26 9a 28 93 20 9b 29
66 4e 68 41 49 60 40 69 56 76 58 78 50 70 59 79
a6 1e aa 11 19 a3 10 ab 06 b6 08 ba 00 b3 09 bb
e6 ce ea c2 cb e3 c3 eb d6 f6 da fa d3 f3 db fb
31 8a 3e 86 8f 37 87 3f 92 21 9e 2e 97 27 9f 2f
61 48 6e 46 4f 67 47 6f 51 71 5e 7e 57 77 5f 7f
a2 18 ae 16 1f a7 17 af 01 b2 0e be 07 b7 0f bf
e2 ca ee c6 cf e7 c7 ef d2 f2 de fe d7 f7 df ff
```

ShiftRows rotates rows 0,1,2,3 right by 0,1,2,3 bytes respectively. MixColumns applies this binary matrix independently to each column over bytes:

```
1 0 1 1
1 0 0 0
0 1 1 0
1 0 1 0
```

For a characteristic, the weight of an S-box transition is `-log2(DDT_count/256)`. The total characteristic weight is the sum over all active S-box transitions.

Decide the bounded optimality question:

```
Does there exist any 14-round SKINNY-128 differential characteristic with total weight <= 130?
```

The input difference must be nonzero and the output difference is free. Report YES or NO. If YES, provide a replayable characteristic. If NO, provide reproducible evidence strong enough to distinguish a true lower-bound/optimality result from an unsuccessful search.

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

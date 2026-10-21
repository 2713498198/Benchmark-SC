# Question 061. Simon32 trail 与 distinguisher 相关性排序复核

## 问题描述

背景。固定条件如下：
- 对象：Simon32 fourteen round trail versus distinguisher ordering
- 任务类型：trail 与 distinguisher 相关性排序复核

公开实验记录。三个 14 轮 Simon32 differential-linear 候选的单条 trail 与固定端点聚合 distinguisher 数据如下；三元组是 `(Rd,Rm,Rl)`：

| split | `CorT` | `CorD` |
|---|---:|---:|
| `(5,5,4)` | `2^-18.63` | `2^-15.63` |
| `(5,6,3)` | `2^-17.49` | `2^-16.75` |
| `(7,3,4)` | `2^-20.00` | `2^-15.36` |

`CorT` 是一条中心 DL trail 的绝对相关性；`CorD` 是在相同输入差分和输出 mask 下枚举兼容 differential/linear trails、计算中间有符号 DLCT contribution 后的截断聚合相关性。

任务。用脚本分别按 `CorT` 和 `CorD` 从高到低排序，输出两个排名及每行 `CorD/CorT` 倍率。判断最高 `CorT` 是否一定给出最高 `CorD`，并从聚合贡献角度解释排序反转。结论只能约束给定三个候选，不能外推成所有 DL trails 的普遍单调定理。

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

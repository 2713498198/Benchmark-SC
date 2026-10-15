# Question 068. Simon32 差分线性变换复核

## 问题描述

背景。Simon32 使用 Simon-like 轮函数。目标是复核一个 14 轮 differential-linear distinguisher：该实例需要比较单条 DL trail 的 correlation 与经过 transformation 后得到的 distinguisher correlation。这里的重点不是搜索任意最优 trail，而是判断“最高 trail correlation 是否必然导出最高 distinguisher correlation”。

任务。下面给出一个 14 轮 Simon32 differential-linear 变换实验的完整公开输入。三元组 `(Rd,Rm,Rl)` 分别表示 differential part、中间 connective part 和 linear part 的轮数。

对给定输入差分 `Delta_I` 和输出 mask `Lambda_O`，变换流程为：

1. 枚举 `Rd` 轮内从 `Delta_I` 出发、概率不低于阈值的兼容 differential trails，记录中间差分与概率 `(Delta_i,p_i)`；
2. 反向枚举 `Rl` 轮内到达 `Lambda_O`、绝对相关不低于阈值的兼容 linear trails，记录中间 mask 与相关性 `(Lambda_j,q_j)`；
3. 对每个中间 pair 计算 `Rm` 轮的 DLCT/connective contribution `r_ij`；
4. 以 `CorD = sum_i sum_j p_i * r_ij * q_j^2` 汇总固定端点下的 distinguisher correlation。

以下是三个候选 trail 及相同端点聚合后的记录：

| 配置 `(Rd,Rm,Rl)` | 单条 trail 相关性 `CorT` | transformed distinguisher 相关性 `CorD` |
|---|---:|---:|
| `(5,5,4)` | `2^-18.63` | `2^-15.63` |
| `(5,6,3)` | `2^-17.49` | `2^-16.75` |
| `(7,3,4)` | `2^-20.00` | `2^-15.36` |

第三行的输入差分和输出 mask 分别为

```text
Delta_I  = (0x0100, 0x0645)
Lambda_O = (0x8000, 0x2002)
```

对该 pair 使用 `2^32` 个样本得到的实验绝对相关约为 `2^-14.48`。该实验量是有限样本估计，不等于形式化最优性证明。

请完成以下复核：

1. 用脚本把六个相关性写成数值，按 `CorT` 和 `CorD` 分别排序，并计算每行 `CorD/CorT-1` 的百分比。
2. 给出三个配置的对比，判断最高 `CorT` 是否给出最高 `CorD`，并解释兼容 trails 聚合为何可能改变排序。
3. 报告该记录中最佳 transformed distinguisher 的输入差分、输出 mask、estimated correlation 和 experimental correlation。
4. 检查实验值与估计值的数量级关系，并说明有限样本结果支持到什么程度。
5. 明确该实例验证的是差分线性 distinguisher 的变换与聚合效应，不是 key-recovery attack，也不是所有候选上的全局最优性证明。

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

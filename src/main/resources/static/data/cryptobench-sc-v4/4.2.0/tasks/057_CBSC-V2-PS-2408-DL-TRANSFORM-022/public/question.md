# Question 057. DLCT 差分线性变换流程复核

## 问题描述

背景。对象为 Simon-like cipher 的 differential-linear 分析流程。输入是一个 DL trail 或候选 split，目标是得到更强的 transformed distinguisher。

定义。给定一个 `R=Rd+Rm+Rl` 轮 differential-linear trail，其公开信息包括输入差分 `Delta_I`、输出 mask `Lambda_O`、轮数 split `(Rd,Rm,Rl)`、中心 trail 的 differential probability 和 linear correlation。将单条 trail 变换为固定端点 distinguisher 时：

1. 枚举 `Rd` 轮内从 `Delta_I` 出发且 `p_i >= p_min` 的 differential trails，记录中间差分与概率 `(Delta_i,p_i)`；
2. 反向枚举 `Rl` 轮内到达 `Lambda_O` 且 `abs(q_j) >= q_min` 的 linear trails，记录中间 mask 与有符号相关性 `(Lambda_j,q_j)`；
3. 对每个 `(Delta_i,Lambda_j)` 计算 `Rm` 轮的有符号 DLCT/connective contribution `r_ij`；
4. 计算 `CorD = sum_i sum_j p_i*r_ij*q_j^2`。求和必须保留 `r_ij` 的符号，不能把所有贡献先取绝对值。

任务。实现上述变换的可执行框架或精确伪代码。自行构造一个包含正、负和零 `r_ij` 的小型测试实例，验证索引遍历、符号和 `q_j^2` 均被正确处理。分别降低 `p_min` 和 `q_min`，报告枚举项数与 `CorD` 的变化，并解释 lower bounds 如何在枚举成本与截断近似精度之间形成权衡。不要把截断求和结果声明为未经误差界证明的精确 correlation。

## 交付要求

请保留自选试例的输入表，以及三次运行的结果：基准阈值、只降低 `p_min`、只降低 `q_min`。每次写出两个方向各自保留的项数和带符号的总和，并说明截断范围。可以提交可执行程序或足以复核上述步骤的精确伪代码。

自动评分读取工作目录或 artifacts/ 中的 `signed_grid.json`，以该文件为本题的数值交付。字段 `format` 取 `signed_grid_v1`，数组 `p, q` 与二维数组 `r` 按行、列对应。每个 `trials` 项写 `p_min, q_min, n_p, n_q, sum`。分数可写为 `"1/4"`。两个独立的阈值变化各应实际增加相应方向的保留项。选取的非零项中应包括负 q 以及绝对值介于 0 和 1 之间的 q，以检验符号和平方。

完整交付还需给出可复算的伪代码及成本和截断范围。用 `program.row`、`program.column` 表示含等号的行、列筛选条件（例如 `p>=p_min` 或其调换左右的写法；列使用 `abs(q)` 和 `q_min`），`program.reduce` 为 `sum_selected_pairs`。`program.term` 填所用公式，由变量 p、r、q、乘号 *、整数幂 ** 和括号构成。每次试验另报 `pairs_used`（保留的行列对数）和 `omitted_abs_sum`（表内被截去的各项绝对值之和，可为零）。`scope` 取 `finite_table`，说明误差界仅适用于给出的有限表。验证器逐项重算。只完成基础数表复算可得 0.5 分。

1. 给出实现或精确伪代码，并指出它如何遍历试例中的行、列与中间项。
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

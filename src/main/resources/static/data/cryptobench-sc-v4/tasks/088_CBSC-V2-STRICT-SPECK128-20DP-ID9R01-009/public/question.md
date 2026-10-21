# Question 088. 20 轮 Speck128/128 fixed endpoint differential probability 固定端点

## 问题描述

背景。固定条件如下：
- 对象：Speck128/128
- 轮数：20
- 任务类型：fixed endpoint differential probability 固定端点
- word size：64
- 旋转参数：{"alpha": 8, "beta": 3}
- 输入差分：["0x0124000c00000000", "0x0801042004000000"]
- 输出差分：["0x8004000080000124", "0x8420040080000801"]

任务。使用 Speck128/128 的标准两分支 Speck 轮函数，word size 为 64 bit，轮数为 20。旋转参数为 {"alpha": 8, "beta": 3}。

令 n、alpha、beta 分别取上面的 word size 与旋转参数，标准 round-core 明确定义为：x_(r+1) = (ROR_alpha(x_r) + y_r) mod 2^n；y_(r+1) = ROL_beta(y_r) XOR x_(r+1)。差分 pair 的书写顺序始终是 (Delta_x, Delta_y)。round-key XOR 是固定 XOR 常量，不改变 single-key XOR-difference propagation。

本题的 aggregate probability 定义为：固定 Delta_0 和 Delta_R 后，对所有中间 round-boundary differences Delta_1,...,Delta_(R-1)，求每条兼容 characteristic 的逐轮 modular-addition XOR-transition probability 乘积，再对这些乘积求和。局部模加概率必须由精确 carry/DDT 计数得到。单条 trail、当前最好 trail 或只枚举到某个阈值的部分和都不等于未经误差界证明的完整 aggregate probability。给定输入差分 ['0x0124000c00000000', '0x0801042004000000'] 和输出差分 ['0x8004000080000124', '0x8420040080000801']，计算该固定输入/输出差分对的 aggregate XOR differential probability。必须汇总所有兼容 characteristics，不要只报告单条可行 trail 或当前找到的最好 trail。报告有效权重 -log2(p)、概率近似值和模型/枚举证据。

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

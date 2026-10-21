# Question 060. Simon32 差分线性相关性改进复核

## 问题描述

背景。固定条件如下：
- 对象：Simon32 thirteen round DL improvement
- 任务类型：差分线性相关性改进复核

公开实验记录。给定一个 13 轮 Simon32 differential-linear 实例，split 为 `(5,5,3)`。原单条 DL trail 的 5 轮 differential probability 为 `2^-8`，3 轮 linear approximation correlation 为 `2^-4`，连同 5 轮中间部分后，单条 trail 的总估计相关性为 `CorT=2^-16.63`。

变换时从相同输入差分出发枚举 probability 至少 `2^-16` 的兼容 differential trails，并反向枚举绝对相关至少 `2^-8` 的兼容 linear trails；对中间 difference/mask 使用有符号 DLCT contribution，按 `sum_i sum_j p_i*r_ij*q_j^2` 聚合。该截断聚合给出 `CorD=2^-13.94`，使用 `2^32` 个样本的实验估计为 `2^-13.19`。

任务。用脚本把三个相关性转为数值，计算变换相对单条 trail 的倍率，并比较理论聚合值与实验估计的数量级。报告原理论值、改进理论值、实验值和 split，说明改进来自固定端点下兼容贡献的 DLCT 聚合，而不是单纯把一条 trail 的概率调大。明确有限样本实验和截断枚举各自的证据边界。

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

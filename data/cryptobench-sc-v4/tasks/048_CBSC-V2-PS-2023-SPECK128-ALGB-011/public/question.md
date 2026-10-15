# Question 048. SPECK128 差分攻击参数复核

## 问题描述

背景。给定 SPECK128/256 的 plaintext-structure 攻击参数行，需要复核 Algorithm B 的参数关系。

公开实例。考虑 SPECK128/256 的 24 轮 key-recovery 参数比较，其中核心为一条 19 轮 differential trail，概率 `p=2^-119`，并具有 `nBIL=23`、`nFIL=2`。`nBIL`/`nFIL` 分别表示前置和后置模加中可利用的 backward/forward inactive lower bits。

基线使用 split `1+19+4`，报告数据 `2^120`、时间 `2^248`、内存 `2^22`。Type-II structure 的 Algorithm-B 实例使用 split `2+19+3`，把目标 right-pair 系数取为 `mu=3`，报告数据 `mu*2^(119+1)`、时间 `2^231.58`、内存 `2^131`。这些时间和内存指数是该攻击模型的已给定输出，不应被表述成本题脚本重新执行了完整 key recovery。

任务。用脚本复核 `mu*2^(119+1)` 的 log2 数据指数，报告 trail probability、`nBIL`、`nFIL`、两个 split 以及两行数据/时间/内存复杂度。计算新方案相对基线节省的时间指数、增加的数据指数和增加的内存指数，并解释该实例用额外数据与计数器空间换取了多少时间优势。不要把 19 轮 trail、24 轮攻击和完整 SPECK128/256 混为一谈。

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

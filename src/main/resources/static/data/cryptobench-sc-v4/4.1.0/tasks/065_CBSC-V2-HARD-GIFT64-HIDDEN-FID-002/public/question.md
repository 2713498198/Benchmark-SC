# Question 065. GIFT64 不可能差分验证

## 问题描述

背景。固定条件如下：
- 对象：GIFT64
- 任务类型：不可能差分验证
- 分析模型：fuzzy impossible differential reachability

任务。题目：判断一个 5 轮 GIFT64 差分模式是否可达

请只基于下面给出的 GIFT64 轮函数信息，判断指定输入/输出差分模式在 5 轮差分传播中是否可达，并给出可复核证据。

GIFT64 差分传播采用 XOR difference。轮密钥加和轮常数对 XOR difference 没有影响，因此本题只需要建模 S-box 层和 bit permutation 层。

S-box 为：

`S = [1, A, 4, C, 6, F, 3, 9, 2, D, B, 7, 5, 0, 8, E]`

bit permutation 使用如下映射，表示输入 bit `i` 经过线性层后到输出 bit `P[i]`：

`P = [0,17,34,51,48,1,18,35,32,49,2,19,16,33,50,3,4,21,38,55,52,5,22,39,36,53,6,23,20,37,54,7,8,25,42,59,56,9,26,43,40,57,10,27,24,41,58,11,12,29,46,63,60,13,30,47,44,61,14,31,28,45,62,15]`

十六进制差分串按通常 16 个 nibble 从左到右书写。待判断的 fuzzy pattern 是：

- 输入差分：最右侧 nibble 为任意非零值，其他 15 个 nibble 全为 0。
- 输出差分：从左数第 7 个 nibble 为任意非零值，其他 15 个 nibble 全为 0。

这里“任意非零值”表示 `{1,2,...,F}` 中的一个 nibble 值；输入处的非零值和输出处的非零值彼此独立，不要求相等，也都不能取 0。

请回答：

1. 这个 5 轮输入/输出 fuzzy differential pattern 是可达还是不可达？
2. 如果不可达，请说明你怎样把它展开为具体差分对，并给出足够让人复核的证据，例如 SAT/SMT/MILP 模型、DDT 支撑检查、脚本、求解器日志或等价的可执行验证。
3. 请说明你的结论只覆盖什么范围，避免把它误写成完整 GIFT64 攻击或普通高概率差分 characteristic。

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

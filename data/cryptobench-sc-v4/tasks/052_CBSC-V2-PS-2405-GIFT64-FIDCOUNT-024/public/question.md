# Question 052. GIFT64 模糊不可能差分验证

## 问题描述

背景。对象为 5 轮 GIFT64 的 nibble-level fuzzy impossible differential 搜索；符号 * 和 - 都表示非零 nibble，但二者在模式中独立取值。

任务。计算 5 轮 GIFT64 single-active-nibble fuzzy impossible differentials 的完整计数。采用 XOR difference；轮密钥和常数不影响差分。每轮依次应用 nibble-wise S-box 和 bit permutation：

```text
S = [1,A,4,C,6,F,3,9,2,D,B,7,5,0,8,E]
P = [0,17,34,51,48,1,18,35,32,49,2,19,16,33,50,3,
     4,21,38,55,52,5,22,39,36,53,6,23,20,37,54,7,
     8,25,42,59,56,9,26,43,40,57,10,27,24,41,58,11,
     12,29,46,63,60,13,30,47,44,61,14,31,28,45,62,15]
```

`P[i]` 表示输入 bit `i` 移到输出 bit `P[i]`。64-bit 差分写成从左到右 16 个 hexadecimal nibbles。搜索全集为：输入恰有一个 active nibble，输出也恰有一个 active nibble，因此共有 `16*16` 个位置 pattern。输入符号 `*` 和输出符号 `-` 都独立遍历 `{1,...,F}`，每个位置 pattern 展开为 `15*15` 个 concrete endpoint pairs。

如果一个位置 pattern 的 225 个 concrete pairs 在精确 S-box DDT support 和 P-layer 下全部不可达，则称该 symbolic fuzzy pattern 为 impossible。请用 SAT/SMT、双向传播或等价精确程序报告：

1. impossible symbolic position patterns 的数量和完整位置列表；
2. 这些 patterns 展开后的 impossible concrete endpoint pairs 总数；
3. `*`、`-` 的精确定义以及为什么二者不能被当成包含 0 或要求相等的同一通配符。

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

# Question 066. 9 轮 PRESENT BDPT 积分区分器代码复核

## 问题描述

背景。PRESENT 使用 64-bit 状态，S-box 为 [c,5,6,b,9,0,a,d,3,e,f,8,4,7,1,2]，P-layer 为 P(i)=16*i mod 63 (0<=i<=62), P(63)=63。每轮包含 16 个并行 4-bit S-box、P-layer 和 round-key XOR。输入模式中 a 表示活跃明文比特，c 表示固定常量比特。

任务。使用 BDPT（三子集 division property）或严格等价模型，计算 9 轮后输出 bit 的 `b/?` balance pattern。采用标准 PRESENT 64-bit round：16 个并行 4-bit S-box，随后是 bit permutation；轮密钥 XOR 必须按 BDPT 的 key-XOR 规则处理。

```text
S = [c,5,6,b,9,0,a,d,3,e,f,8,4,7,1,2]
P(i) = 16*i mod 63  (0 <= i <= 62),  P(63)=63
```

bit 编号采用 global LSB0；输出 pattern 按 bit 63 到 bit 0 打印，并分成两个 32-bit 字符串。输入中的 `a` 表示该 plaintext bit 遍历活动变量，`c` 表示固定常量，因此数据复杂度为 `2^(a 的数量)`。输出中的 `b` 表示模型证明该 bit 在完整输入集合上 balanced，`?` 表示该模型未证明 balanced，而不是证明 unbalanced。

BDPT 状态必须同时保留集合 `K` 与带重数的集合 `L`。S-box 层应从 PRESENT S-box 的布尔/ANF 或等价真值关系生成局部传播；相同 `L` 向量的偶数重数需要按奇偶性抵消。每轮可视为 17 个部分：16 个 S-box 部分和一个 P-layer 加 round-key XOR 部分；key-XOR 可能把由 `L` 生成的向量插入 `K`，不能简单把轮密钥当成与积分传播无关的常量。若采用其他模型，必须说明它为何与这些判定语义等价。

对每个 case 的 64 个输出 unit vectors 分别做可达性/停止规则查询，并从查询日志自动组装最终 pattern，不得在搜索代码中硬编码平衡位置。需要验证两个输入：

1. (aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, aaaaaaaaaaaaaaaaaaaaaaaaaaaacccc)
2. (aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaac)

对每个输入输出 64-bit pattern、balanced bit 数量、global-LSB0 位置、运行时间和后端。随机抽样、少量明文枚举或论文表格引用不能替代完整符号判定；若模型与原 BDPT 有差异，应报告差异和结论边界。

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

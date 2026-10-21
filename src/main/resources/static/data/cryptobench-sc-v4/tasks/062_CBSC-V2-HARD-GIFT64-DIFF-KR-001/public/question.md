# Question 062. GIFT-64 密钥恢复复杂度复核

## 问题描述

背景。固定条件如下：
- 对象：标准 GIFT-64，完整算法为 28 轮；本题只讨论其轮函数上的约化轮分析。
- 攻击模型：single-key XOR differential key recovery。
- 攻击范围：20 轮，按 `3 + 13 + 4` 分解为前置 3 轮、13 轮差分区分器和后置 4 轮。
- 任务类型：给定研究记录的密钥恢复复杂度复核。

采用以下研究记录作为公开输入：

1. 先在 13 轮上按单条 characteristic 的概率搜索候选。概率为 `2^-62` 的 288 条最优 trails，以及随后概率为 `2^-63` 的 5184 条和概率为 `2^-63.415` 的 6272 条 trails，在扩展为 20 轮攻击时都因需要猜测的子密钥位过多而使时间复杂度超过 `2^128`，不能形成有效攻击。
2. 将 trail 搜索阈值降到 `2^-64` 后，SAT 搜索得到 92,768 条 trails。其中 3,096 条满足参与子密钥枚举的位数 `GSBD < 112`；按相同输入、输出差分合并后得到 2,392 个 distinct differentials。
3. 对每个 differential，继续枚举其中概率大于 `2^-71` 的兼容 trails 并求和估计 differential probability。概率最高的一组包含 32 个 probability `2^-61.313` 的 differentials；其中 `D00` 至 `D15` 的 `GSBD=107`，`D16` 至 `D31` 的 `GSBD=110`。本实例选用 `D03` 作为 13 轮区分器。
4. 该区分器前接 3 轮、后接 4 轮。每个明文结构含 `2^48` 个选择明文并产生 `2^91` 个候选对；结构数取 `S=2^14.58`，因此 `N1=S*2^91`。经过前置筛选后，进入区分器的对数为 `ND=N1*2^-44`。正确密钥和错误密钥下的 right-pair 概率分别取 `p0=2^-61.313` 与 `p1=2^-64`，判定阈值为 1，目标成功率为 70%。
5. 子密钥枚举阶段的时间折算为 `T1=N1*2^15.21` 次 20 轮加密；阈值为 1 时，错误密钥误报概率为 `beta=1-(1-p1)^ND`。剩余 21 位主密钥的穷举项为 `T2=2^128*beta*(1-2^-64)`。总时间取 `max(T1,T2)`；存储量按需要保存的明文/候选对主导项估计。注意这里公开的指数已经过小数截断：直接代入会得到约 `2^125.45`，原研究使用未截断中间量报告为约 `2^125.50`。答案应同时记录重算值与报告值，不能把 0.05 bit 的舍入差异判为模型错误。
6. 对照基线是此前同为 20 轮的单密钥差分攻击，该基线需要完整的 `2^64` codebook。本题不讨论完整 28 轮 GIFT-64 的破译。

任务。基于以上记录完成端到端复核：

1. 说明为什么“概率最高的单条 13 轮 trail”不足以直接成为有效的 20 轮攻击依据，并解释从 trails 到 distinct differentials 的聚合与筛选过程。
2. 说明 `D03` 在 `3 + 13 + 4` 轮攻击结构中的作用，并区分 characteristic probability 与 differential probability。
3. 用脚本复核候选数量关系以及数据复杂度指数；根据给定二项分布参数计算或数值复核 `beta`、`T1`、`T2` 和总时间复杂度。
4. 给出数据、时间和内存复杂度，并与完整 codebook 基线比较。
5. 明确结论只适用于该 20 轮 single-key 实例，不能外推为完整 GIFT-64 破译。

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

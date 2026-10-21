# Question 067. 14 轮 Simon32/64 differential linear distinguisher search with observable process

## 问题描述

背景。Simon32/64 使用 Simon-like ARX/AND 结构。目标是搜索 14 轮 differential-linear distinguisher，需要同时处理前向 differential、后向 linear approximation 和中间连接。

任务。请自行选择 MILP、MIQCP、SAT/SMT、枚举或脚本搜索方法，寻找并复核 14 轮 Simon32/64 的 differential-linear distinguisher。只使用下面定义的实例，不需要依赖未提供的论文算法编号或表格。

Simon32/64 的状态由两个 16-bit word `(L,R)` 构成。位旋转均限制在 16 bit 内，一轮为

```text
F(L) = (ROL_1(L) AND ROL_8(L)) XOR ROL_2(L)
(L',R') = (R XOR F(L) XOR k_i, L).
```

使用 64-bit master key 的 4 个 16-bit word 作为 `k_0,...,k_3`。后续轮密钥按

```text
tmp = ROR_3(k_(i-1))
tmp = tmp XOR k_(i-3)
tmp = tmp XOR ROR_1(tmp)
k_i = k_(i-4) XOR tmp XOR 0xfffc XOR z0[(i-4) mod 62]
```

生成，其中

```text
z0 = 11111010001001010110000111001101111101000100101011000011100110
```

且字符串从左到右依次提供 `z0[0],z0[1],...`。若实现采用相反 word/bit convention，必须给出等价映射和自检，不能混用两种 convention。

只比较以下三种 14 轮分解，三元组依次表示 differential part、中间 connective part 和 linear part 的轮数：

```text
(5,5,4), (5,6,3), (7,3,4)
```

对固定输入 XOR difference `Delta` 和输出 linear mask `Gamma`，实验相关性按

```text
signed_sum = sum_j (-1)^parity((C_j XOR C'_j) AND Gamma)
c = signed_sum / N
```

计算，其中 `(P_j,P'_j)` 满足 `P_j XOR P'_j=Delta`，两者在同一 master key 下加密，`N` 是样本对数。搜索必须使用固定随机种子，并报告 master key 的选取方式、样本数、`signed_sum`、`c` 和 `log2(abs(c))`。应使用分阶段筛选、模型生成候选或其他系统方法，不能只靠少量随机 pair 碰撞。

最终报告你找到的最佳输入差分、输出 mask、轮数 split、单条 trail 的估计相关性（若模型产生该量）和实测 distinguisher correlation，并给出代码运行证据。明确区分 `CorT`（单条 DL trail）和 `CorD`（固定端点下兼容贡献形成的 distinguisher），也不要把 distinguisher 误写成 key-recovery attack。

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

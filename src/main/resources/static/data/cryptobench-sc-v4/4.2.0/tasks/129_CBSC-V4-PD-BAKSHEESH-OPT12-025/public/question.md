# BAKSHEESH 12 轮最优差分特征搜索

附件 `attachments/baksheesh_diff_instance.json` 完整定义了 128 位 SPN 的 S-box、P-layer、位序和单条差分特征的概率语义。搜索全部非零输入差分，确定 12 轮单条差分特征的最小权重

```text
w = -log2(product of all active S-box DDT probabilities).
```

请给出一条达到最小权重的完整 12 轮 trail，包括 13 个轮边界差分、每轮活动 S-box 转移及每轮权重；同时给出能够排除所有更低权重 trail 的可复现最优性证据。轮密钥和轮常数与固定密钥 XOR 差分传播无关，不纳入状态差分。

返回可直接执行的建模、求解与 trail replay 代码，保存关键 SAT/UNSAT 或等价证明结果，并在最终报告中明确区分“找到可行 trail”和“证明全局最优”。环境提供 Python、Z3、PySAT、CaDiCaL 与 CryptoMiniSat，也可使用等价的完备方法。

请分别保留可行解的逐轮复核材料与排除更优解的求解记录，写清每次运行的界限和停止状态。

如需机器核对完整候选路径，请将 `optimality_evidence.json` 放在工作区根目录。用 `optimal_weight` 和 `round_states_hex` 给出最终报告值与有序轮边界；`rounds` 中每轮给出 `round`（1–12）、`weight` 与 `transitions`。每个活动转移列出 `sbox`（零起算）、`input`、`output`（各为一位十六进制值）、`table_value` 与 `weight`。验证器从提交的边界重新检查每轮记录，允许替代路径。

`lower_bound_case` 可保留独立求解记录中的模型类型、轮数、权重界、内部界、输出活跃盒约束、求解状态与公式规模。既有复放记录用于校对参考下界；求解器状态或统计摘要本身不足以核验本次提交的下界证明。当前评分器可自动核对候选与逐轮过程，下界尚无完整的机器证明核验路线。

发布版的固定实例验收会核对以下记录。提交同一实例的阈值记录、可行见证及逐轮复算结果。记录核对通过记为 `release_check_status=passed`。原题的独立证明另列，现版不计入完整证明通过率。

# BAKSHEESH 12 轮最优差分特征搜索

附件 `attachments/baksheesh_diff_instance.json` 完整定义了 128 位 SPN 的 S-box、P-layer、位序和单条差分特征的概率语义。搜索全部非零输入差分，确定 12 轮单条差分特征的最小权重

```text
w = -log2(product of all active S-box DDT probabilities).
```

请给出一条达到最小权重的完整 12 轮 trail，包括 13 个轮边界差分、每轮活动 S-box 转移及每轮权重；同时给出能够排除所有更低权重 trail 的可复现最优性证据。轮密钥和轮常数与固定密钥 XOR 差分传播无关，不纳入状态差分。

返回可直接执行的建模、求解与 trail replay 代码，保存关键 SAT/UNSAT 或等价证明结果，并在最终报告中明确区分“找到可行 trail”和“证明全局最优”。环境提供 Python、Z3、PySAT、CaDiCaL 与 CryptoMiniSat，也可使用等价的完备方法。

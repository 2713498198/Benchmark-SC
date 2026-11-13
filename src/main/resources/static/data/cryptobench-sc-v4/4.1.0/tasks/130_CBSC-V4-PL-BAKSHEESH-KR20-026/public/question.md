# BAKSHEESH 20 轮固定边界线性特征与 21 轮密钥恢复审计

附件 `attachments/baksheesh_linear_instance.json` 完整定义了 128 位 SPN 的 S-box、P-layer、位序、固定输入/输出掩码和线性相关语义。请在这两个固定边界之间搜索 20 轮单条线性特征，确定最小绝对相关权重

```text
c = -log2(product of the absolute local S-box correlations).
```

给出一条达到最小权重的完整 trail，包括 21 个轮边界掩码、每轮非零 LAT 转移、逐轮权重和总权重；同时给出能够排除所有更低权重 trail 的可复现完备证据。请明确区分单条 linear trail 与 linear hull，也不要把 `TIME_LIMIT` 或“未找到更好解”写成最优性证明。

在该 20 轮 distinguisher 后附加一轮部分解密。按附件给出的公式，从输出掩码自行确定 `N` 和 `m`，计算 21 轮攻击需要猜测的子密钥位数、数据复杂度、部分解密时间、剩余密钥穷举时间和两部分相加后的总时间指数。

返回可直接执行的建模、求解和 trail replay 代码，并保留关键 SAT/UNSAT 或等价证明结果。环境提供 Python、Z3、PySAT、CaDiCaL 与 CryptoMiniSat，也允许使用其他能够给出完备状态的求解方法。

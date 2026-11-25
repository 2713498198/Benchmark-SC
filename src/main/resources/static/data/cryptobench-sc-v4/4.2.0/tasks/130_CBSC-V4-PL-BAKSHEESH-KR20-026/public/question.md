# BAKSHEESH 20 轮固定边界线性特征与 21 轮密钥恢复审计

附件 `attachments/baksheesh_linear_instance.json` 完整定义了 128 位 SPN 的 S-box、P-layer、位序、固定输入/输出掩码和线性相关语义。请在这两个固定边界之间搜索 20 轮单条线性特征，确定最小绝对相关权重

```text
c = -log2(product of the absolute local S-box correlations).
```

给出一条达到最小权重的完整 trail，包括 21 个轮边界掩码、每轮非零 LAT 转移、逐轮权重和总权重；同时给出能够排除所有更低权重 trail 的可复现完备证据。请明确区分单条 linear trail 与 linear hull，也不要把 `TIME_LIMIT` 或“未找到更好解”写成最优性证明。

在该 20 轮 distinguisher 后附加一轮部分解密。按附件给出的公式，从输出掩码自行确定 `N` 和 `m`，计算 21 轮攻击需要猜测的子密钥位数、数据复杂度、部分解密时间、剩余密钥穷举时间和两部分相加后的总时间指数。

返回可直接执行的建模、求解和 trail replay 代码，并保留关键 SAT/UNSAT 或等价证明结果。环境提供 Python、Z3、PySAT、CaDiCaL 与 CryptoMiniSat，也允许使用其他能够给出完备状态的求解方法。

逐轮数据可写成按轮排列的边界列表，或写成含轮号、输入和置换后输出的逐轮记录。请将可行解的复核材料与较低阈值的求解记录分开标记。

判分器可读取工作目录 `verification.json`。提交 `schema`（固定字符串 `cbsc-fixed-result-v1`）、`task_id`（本目录的完整题号）、`weight`、`round_states_hex`（按轮排列的 21 个边界）及 `per_round_weights`（20 个整数）。以 `threshold` 对象给出 `kind`、`rounds`、`input_mask`、`output_mask`、`weight_bound`、`status`。以 `metrics` 对象给出 `N`、`m`、`guessed_key_bits`、`data_exponent`、`partial_decryption_time_exponent`、`remaining_search_exponent` 和 `total_time_exponent`。时间指数保留至少两位小数。判分器复算提交路径并核对各项数值及固定实例的归档阈值结果。该记录可以取得固定实例的满答案分。当前发布版尚不支持对提交者自己的完备性证明自动验真，完整交付在报告中单列。

发布版的固定实例验收会核对以下记录。提交固定端点、逐轮权重、阈值记录和复杂度各项。记录核对通过记为 `release_check_status=passed`。原题的独立证明另列，现版不计入完整证明通过率。

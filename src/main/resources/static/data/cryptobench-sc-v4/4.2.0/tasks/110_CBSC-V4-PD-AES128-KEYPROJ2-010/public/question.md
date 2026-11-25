# Question 110. AES-128 两轮固定差分特征的密钥投影条件

附件 `attachments/aes_sbox.json` 给出 AES S-box。所有字节乘法均在 `GF(2^8)` 中进行，约化多项式为 `x^8+x^4+x^3+x+1`（`0x11b`），加法为 XOR。

考虑以下两轮固定字节差分特征，16 个字节按 AES 列优先顺序编号为 `0,...,15`：

```text
round 0: Is = 01000000000000000000000000000000
         Os = 01000000000000000000000000000000
round 1: Is = 02000000010000000100000003000000
         Os = 4700000063000000c20000008a000000
```

定义 S-box 转移输入域

```text
X(di,do) = {x in GF(2^8) : S(x) xor S(x xor di) = do}.
```

令

```text
Y0   = {S(x) : x in X(01,01)},
X10  = X(02,47),
X14  = X(01,63),
X18  = X(01,c2),
X112 = X(03,8a).
```

首轮密钥字节记为 `k0,k4,k8,k12`。该固定特征可行当且仅当密钥投影

```text
L(k) = 17*k0 xor 2f*k4 xor f6*k8 xor 91*k12
```

属于集合

```text
R = {5f*y0 xor 17*x10 xor 2f*x14 xor f6*x18 xor 91*x112 :
     y0 in Y0, x10 in X10, x14 in X14, x18 in X18, x112 in X112}.
```

式中两位数均为十六进制域元素。请从附件 S-box 重新枚举五个转移域并精确计算 `R`。可交可复算的程序与结果，也可交由验证器直接核对的完整有限表。报告五个域、完整 `R`、每个 `R` 元素对应的局部五元组重数，以及满足条件的均匀随机 `(k0,k4,k8,k12)` 比例。

五个局部 S-box 转移的独立乘积概率为 `2^-35`。请进一步给出无效密钥下和有效密钥下该固定特征的概率，并验证对均匀随机密钥取平均后仍为 `2^-35`。结论只针对这里给出的固定两轮特征和密钥投影模型。

请分别标明允许的投影值集合、每个值的重数，以及无效密钥、有效密钥和均匀密钥三种条件下的概率。表格、带标签的 JSON 或正文均可报告数值。

自动核验时，可将完整数值结果另存为工作区根目录的 `projection_evidence.json`。其中 `transition_domains` 用题面给出的五个域名；`allowed_projection_values` 为完整列表；`multiplicity_by_projection` 为逐投影值重数表；`local_tuple_count` 为总重数。字节可用十六进制字符串或整数。`field_polynomial` 写成 `0x11b`，`projection` 以题面顺序写为 `0x17*k0 xor 0x2f*k4 xor 0xf6*k8 xor 0x91*k12`，`scope` 写为 `fixed_characteristic`。用 `valid_key_fraction`、`invalid_key_probability`、`valid_key_probability` 和 `uniform_key_average_probability` 分别给出比例与三种精确概率，支持整数、分数及二的幂。

完整有限表可作为独立的数学交付，无须附程序。用 `tuple_rows` 列出有限关系的全部行，每行包含五个局部域取值和 `projection_value`。验证器按题面公式逐行检查，检查行是否重复，并重新汇总逐值重数。若采用已独立复放的原有实现，完整数值结果与该实现也可交付为可复算材料。静态核验只说明交付材料可复算。

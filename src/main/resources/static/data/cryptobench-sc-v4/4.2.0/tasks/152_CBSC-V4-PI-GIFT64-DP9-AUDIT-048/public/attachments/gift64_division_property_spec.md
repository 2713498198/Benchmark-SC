# GIFT-64 bit-based division-property 形式化规范

## 状态与轮函数

状态是 64 位向量 `x[0]...x[63]`，编号均从 0 开始。分析对象是 GIFT-64 的九轮 round core；轮密钥异或和轮常数异或不改变 bit-based division property，因此不包含在本模型中。

每轮先并行应用 16 个 4 比特 S-box。对第 `j` 个 nibble，S-box 输入整数为

```text
u = sum_{b=0}^3 x[4*j + 3 - b] * 2^b.
```

S-box 真值表按输入整数 `0...15` 排列：

```text
S = [1, a, 4, c, 6, f, 3, 9, 2, d, b, 7, 5, 0, 8, e].
```

输出整数的第 `b` 位写回位置 `4*j+3-b`，得到 `y[0]...y[63]`。随后应用 P-layer：

```text
x_next[i] = y[P[i]],
P = [
  0,17,34,51,48,1,18,35,32,49,2,19,16,33,50,3,
  4,21,38,55,52,5,22,39,36,53,6,23,20,37,54,7,
  8,25,42,59,56,9,26,43,40,57,10,27,24,41,58,11,
  12,29,46,63,60,13,30,47,44,61,14,31,28,45,62,15
].
```

## S-box division transition

对输入 choice nibble `a in {0,1}^4` 和输出 choice nibble `b in {0,1}^4`，令 `S_t(x)` 是 S-box 输出的第 `t` 个 Boolean 坐标函数，并定义

```text
F_b(x) = product over t with b[t]=1 of S_t(x),
```

空乘积为 1。把 `F_b` 写成 ANF。局部 transition `a -> b` 合法，当且仅当该 ANF 至少有一个非零单项式，其变量支持包含 `a` 中全部取 1 的输入坐标。所有坐标顺序都遵循上面的 `u` 定义。

一轮 division trail 由 16 个合法 S-box transition 和同一个 P-layer 组成。九轮 trail 是九次该关系的复合。

## 固定查询

初始 choice vector `k0` 为：

```text
k0[0] = k0[1] = k0[2] = 0,
k0[i] = 1  for i = 3,...,63.
```

这对应输入位 0、1、2 固定，其余 61 位遍历全部取值。对每个输出位 `i`，目标 choice vector `e_i` 仅第 `i` 位为 1。

- 若存在从 `k0` 到 `e_i` 的九轮 division trail，则该模型不能保证输出位 `i` 平衡。
- 若不存在该 trail，则输出位 `i` 对指定输入集合的 XOR 和必为 0，即由 bit-based division property 保证平衡。

逐轮 witness 使用 16 个十六进制数字表示每个完整轮边界上的 64 位 choice vector；最低有效位对应状态位 0。一个可达 witness 应包含初始边界和九个轮后边界，共 10 个掩码。

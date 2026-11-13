# SIMON-32 ultrametric 可除性规范

## 1. 状态、位序与 reduced core

状态为两个 16 比特字 `(L,R)`。打包整数的位 `0,...,15` 依次是 `L` 的最低位到最高位，位 `16,...,31` 依次是 `R` 的最低位到最高位。所有循环移位都限制在 16 比特字内。

计算首先对 `R` 异或一个独立的 16 比特子密钥，然后执行七次下述非线性轮；每轮使用一个新的、彼此独立的 16 比特子密钥：

```text
(L,R) -> (R, L xor k_r xor ROL_2(R) xor (ROL_8(R) and ROL_1(R))).
```

本题将上述“初始右半密钥加法 + 七个非线性轮”称为八轮性质对应的 reduced core。不要额外增加或删除轮，也不要使用 SIMON 密钥编排。

## 2. 输入集合与待计算量

固定掩码

```text
u = 0x01ffffff.
```

输入集合为

```text
X_u = { x in {0,1}^32 : x & ~u = 0 },
```

因此打包位 `0,...,24` 自由变化，位 `25,...,31` 固定为零，数据量为 `2^25`。对完整独立密钥元组 `K`，令 `G_K` 表示第 1 节的 reduced core，并定义

```text
N_i(K) = |{x in X_u : bit_i(G_K(x)) = 1}|.
```

需要按 `i=0,...,31` 的顺序给出模型认证的逐位下界 `nu_i`，使所有 `K` 均满足 `2^nu_i | N_i(K)`。

## 3. ultrametric transition 定义

对布尔映射 `F:{0,1}^n -> {0,1}^m`，令 `T_F` 为标准基下的 pushforward 矩阵，并定义

```text
U_n = [[1,1],[0,1]]^(tensor n),
A_F = U_m T_F U_n^(-1).
```

一条局部 transition `a -> b` 仅在整数系数 `(A_F)[b,a]` 非零时允许，其代价为该系数的 2-adic valuation。复合网络中沿 trail 累加局部代价。不允许把普通 division-property 的零/非零传播规则当作完整的 ultrametric 模型。

为固定 dominant-trail 模型的组件边界，每轮必须采用以下三个组件层：

1. 每个 `R` 输入位进入一个三输出 COPY：分支分别供新左半、下面的 `H` 组件和 `ROL_2` 项使用；
2. 将完整的 16 比特映射

```text
H(R) = ROL_8(R) and ROL_1(R)
```

作为一个 monoid-homomorphism 组件，其输出 exponent `b` 唯一反向传播为

```text
a = ROR_8(b) or ROR_1(b).
```

3. 每个新右半位使用一个四输入 XOR 组件，输入依次来自旧左半、独立密钥、`ROL_2` 分支和 `H` 输出；由 `A_XOR4` 一次生成它的精确 transition 与 valuation。

初始右半密钥加法使用二输入 XOR 组件。若实现内部把 `H` 或四输入 XOR 继续拆成更小的门，必须先对所有内部 trails 的整数系数求和，恢复上述组件的 `A_H` 或 `A_XOR4` 后才能取 2-adic valuation；仅在分解门图上取最小局部代价会改变本题指定的 dominant-trail 模型，因此不被接受。

## 4. 输入 precursor 与密钥无关抵消

输入集合不能简单视为单个 basis exponent。由 `U delta_{X_u}` 的展开，对每个固定掩码位 `u_i`、网络输入 exponent 位 `a_i` 和附加计数位 `c_i`，使用以下 precursor 分解：

```text
u_i = 1  =>  a_i xor c_i = 1;
u_i = 0  =>  a_i = 0, c_i = 0.
```

因此网络输入 exponent 满足 `a <= u`，而 `c_i=u_i-a_i`；`c_i` 与所有局部 2-adic 代价位共同参与总代价计数。特别地，`u` 中的自由输入位不是强制令 `a_i=1`，固定零位也不会产生 precursor 代价。

对每个单位输出 exponent `e_i`，从代价 `d=0` 开始搜索：

1. 若代价 `d` 存在带非零密钥 exponent 的 trail，则该位返回 `d`；
2. 否则枚举代价 `d` 的全部密钥 exponent 为零的 trails；若其数量为奇数则返回 `d`，若数量为正偶数则返回 `d+1`；
3. 若该层没有 trail，则继续检查 `d+1`。

该规则显式计入最低代价处密钥无关 trails 的模二抵消。返回值是上述 dominant-trail 模型给出的、对任意独立密钥成立的可除性下界；不要将它表述为对真实计数的全局最大可能指数，除非另有独立证明。

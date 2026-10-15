# Question 126. Midori-64 两轮非线近似的密钥相关相关度

将 16 比特状态写为四个 nibble `(x0,x1,x2,x3)`，其中 `x0` 是最高 nibble。Midori-64 S-box 为

```text
x:    0 1 2 3 4 5 6 7 8 9 a b c d e f
S(x): c a d 3 e b f 7 8 9 1 5 0 2 4 6
```

定义

```text
M(x0,x1,x2,x3) = (
  x1 xor x2 xor x3,
  x0 xor x2 xor x3,
  x0 xor x1 xor x3,
  x0 xor x1 xor x2
),
R_K(x) = M(S(x0 xor K0), S(x1 xor K1), S(x2 xor K2), S(x3 xor K3)).
```

对 `a,b in {0,...,15}`，固定

```text
F_{a,b} = R_(b,0,0,0) o R_(a,0,0,0).
```

对一个 nibble `z`，令 `z_i=(z >> i)&1`，即 `z_0` 是最低位，并定义

```text
g1(z) = z3*z2*z1 xor z3*z1 xor z3 xor z2 xor z1 xor z0,
g2(z) = z3*z2 xor z2 xor z1 xor z0,
Phi(x0,x1,x2,x3) = g1(x0) xor g2(x1) xor g2(x2) xor g2(x3).
```

请实现可执行程序，对全部 (2^{16}) 个输入精确计算

```text
C[a,b] = 2^-16 * sum_x (-1)^(Phi(x) xor Phi(F_{a,b}(x))).
```

报告全部 `a,b=0,...,15` 的精确有符号相关度，表格按第二轮键 `b` 分行、第一轮键 `a` 分列；可用无歧义的对称或重复行压缩表示。同时给出非零行的 `b` 集合、零行的 `b` 集合，以及最大绝对相关度和最小非零绝对相关度。结论仅针对上述固定两轮函数、非线性质和密钥切片。

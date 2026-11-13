# CRAFT 七轮中间矩阵的形式化定义

## 1. 基本约定

所有变量均取值于 `0..15`，加法均为按位 XOR。CRAFT 的 4 比特 S-box 为

```text
S = [c,a,d,3,e,b,f,7,8,9,1,5,0,2,4,6]
```

其中数组下标和元素均按十六进制解释；`Sinv` 是其逆置换。定义

```text
DDT[u,v] = #{x : S(x) xor S(x xor u) = v}.
```

对非负整数 `r`，`DDT^r[u,v]` 表示整数矩阵 `DDT` 的普通矩阵乘方条目，而不是逐元素乘方。

## 2. 局部连接计数

以下定义均直接对 `x in 0..15` 计数。

```text
DBT[u,b,v] = #{x :
    Sinv(S(x) xor b) xor Sinv(S(x xor u) xor b) = u,
    S(x) xor S(x xor u) = v }.

BDT[u,b,c] = #{x :
    Sinv(S(x) xor b) xor Sinv(S(x xor u) xor b) = u,
    x xor Sinv(S(x) xor b) = c }.
```

为了允许回旋四条边上的差分不同，再定义两个四维表：

```text
DBTstar[u1,u2,b,v] = #{x :
    Sinv(S(x) xor b) xor Sinv(S(x xor u1) xor b) = u2,
    S(x) xor S(x xor u1) = v }.

BDTstar[u,h1,h2,c] = #{x :
    Sinv(S(x) xor h1) xor Sinv(S(x xor u) xor h2) = u,
    x xor Sinv(S(x) xor h1) = c }.
```

这些都是非负整数计数，不做局部归一化。

## 3. 分块收缩

用下列中间表压缩六个内部变量的求和。所有未显式限定的求和变量均遍历 `0..15`。

```text
M1[a1,a2,B,c5] = sum_b DBTstar[a1,a2,b,B] * BDT[B,c5,b]

M2[B,C12,d1] = sum_c12 DBT[B,c12,C12] * BDT[C12,d1,c12]

M3[E1,f12,g9] = sum_F12 DBT[E1,f12,F12] * BDT[F12,g9,f12]

M4[F5,g9,h1,h2] = sum_G9 DBT[F5,g9,G9] * BDTstar[G9,h1,h2,g9]

M12[a1,a2,c5,C12,d1]
  = sum_B M1[a1,a2,B,c5] * M2[B,C12,d1]

M34[E1,f12,F5,h1,h2]
  = sum_g9 M3[E1,f12,g9] * M4[F5,g9,h1,h2].
```

## 4. 目标矩阵

先计算整数分子

```text
N[a1,a2,h1,h2] =
  sum_{c5,C12,d1,E1,f12,F5}
    M12[a1,a2,c5,C12,d1]
  * M34[E1,f12,F5,h1,h2]
  * DDT^2[d1,f12]
  * DDT^3[c5,f12]
  * DDT^2[C12,E1]
  * DDT^3[C12,F5].
```

七轮中间矩阵条目定义为

```text
R7[a1,a2,h1,h2] = N[a1,a2,h1,h2] / 16^18
                    = N[a1,a2,h1,h2] / 2^72.
```

应先用任意精度整数得到 `N`，再约分和计算对数。允许采用变量消元、张量收缩、动态规划或与上述有限求和严格等价的其他精确方法。

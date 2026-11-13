# Question 109. WARP 十轮 Feistel boomerang 中部概率精确计算

WARP 使用如下 4 比特 S-box（下标和值均按十六进制解释）：

```text
S = [c, a, d, 3, e, b, f, 7, 8, 9, 1, 5, 0, 2, 4, 6]
```

对 `a,b,d in {0,...,15}`，定义三个整数连接表：

```text
DDT[a,b]  = #{x : S(x) xor S(x xor a) = b}
FBCT[a,d] = #{x : S(x) xor S(x xor a) xor S(x xor d) xor S(x xor a xor d) = 0}
FBDT[a,b,d] = #{x : S(x) xor S(x xor a) = b,
                     S(x) xor S(x xor a) xor S(x xor d) xor S(x xor a xor d) = 0}
```

本题固定十轮中部两端的相关非零差分为 `alpha = 0xa`、`beta = 0xa`。用上述整数表计算：

```text
F(alpha,beta) = sum_u FBCT[alpha,u] * DDT[beta,u]

G(alpha,c,d) = sum_e FBCT[e,d] * DDT[alpha,c] * DDT[c,e]

H(f,d) = sum_g DDT[f,g] * DDT[g,d]

I(c,f,beta) = sum_h sum_i DDT[c,h] * DDT[h,i] * FBDT[beta,f,i]

N = sum_c sum_d sum_f F(alpha,beta) * G(alpha,c,d) * H(f,d) * I(c,f,beta)
```

所有求和变量均遍历 `0,...,15`，目标概率为 `p = N / 16^10`。请实现可执行程序，重新生成三个连接表并完成精确整数收缩；报告 `N`、约分后的精确概率、十进制概率和 `-log2(p)`。同时说明归一化指数为何是 10，并将结论严格限定为这里定义的固定十轮中部，而不是任意完整 WARP 区分器。

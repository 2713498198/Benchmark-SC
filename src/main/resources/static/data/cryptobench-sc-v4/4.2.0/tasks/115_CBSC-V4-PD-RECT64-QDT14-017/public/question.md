# Question 115. RECTANGLE 14 轮固定差分特征的固定密钥概率审计

附件 `attachments/rectangle_qdt14_instance.json` 给出 RECTANGLE 的 4 比特 S-box、位排列和一条完整 14 轮差分 characteristic。每轮依次执行 64 比特轮密钥异或、16 个并行 S-box 和位排列。所有状态整数均按 LSB-first 编号：第 `j` 位是整数位 `j`，第 `n` 个 S-box 使用位 `4n,...,4n+3`，轮密钥位记为 `K_r[j]`。

对 S-box `S` 定义有符号 quasidifferential 系数

```text
D[(v,b),(u,a)] = (1/16) * sum_{x: S(x xor a)=S(x) xor b}
                            (-1)^(<u,x> xor <v,S(x)>)
```

其中尖括号是 GF(2) 内积。一个全局 mask trail 由 `U_0,...,U_14` 和 `V_0,...,V_13` 构成，满足 `U_0=U_14=0` 且 `U_(r+1)=P(V_r)`；其无密钥符号系数是 224 个局部 `D` 的乘积，轮密钥再贡献

```text
(-1)^(sum_r <U_r,K_r>).
```

全零 mask trail 给出的普通独立轮密钥 characteristic 概率为 `p0`。定义非零 mask trail 相对 `p0` 的损失为 `ell=-log2(|coefficient|/p0)`。

请重建局部有符号表，枚举这条固定 characteristic 中所有 `ell <= 1` 的全局 mask trails，并完成以下审计：确定 `p0`；列出每条主项的相对损失、无密钥符号和非零轮密钥掩码；把四项化简为最少的独立 GF(2) 轮密钥奇偶量；给出这些奇偶量全部赋值下的主项概率表。

可使用下述严格结论：若某个非零 mask trail 的绝对系数等于全零 mask trail 的 `p0`，则二者在某一轮密钥奇偶类中相消时，该固定 characteristic 在该类中的精确概率为零。请据此明确区分“由 `ell <= 1` 四项得到的主项近似”与“能够严格推出的零概率条件”。

返回可直接执行的验证代码、枚举证据和最终结论。这里只审计附件中的固定 characteristic，不搜索其他 characteristic，也不估计完整 differential 或密钥恢复攻击。

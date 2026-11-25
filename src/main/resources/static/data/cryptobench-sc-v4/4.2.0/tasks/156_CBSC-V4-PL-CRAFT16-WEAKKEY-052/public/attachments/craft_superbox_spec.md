# CRAFT 两轮列 superbox 形式化定义

## 状态与位序

状态和中间密钥均为四个 4 比特 cell：

```text
x = (x0, x1, x2, x3),    k = (k0, k1, k2, k3).
```

每个 cell 取值于 `0..15`。机器可读整数采用小端 cell 打包：

```text
pack(x) = x0 | (x1 << 4) | (x2 << 8) | (x3 << 12).
```

所有加法均为 XOR。

## 非线性层

`Sbar` 对四个 cell 并行应用 CRAFT S-box：

```text
input : 0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f
S(x)  : c  a  d  3  e  b  f  7  8  9  1  5  0  2  4  6
```

## 列混合

线性映射 `M` 定义为

```text
M(x0, x1, x2, x3)
  = (x0 xor x2 xor x3,
     x1 xor x3,
     x2,
     x3).
```

## 两轮列 superbox

本题映射为

```text
E_k(x) = Sbar(M(Sbar(x) xor k)).
```

这里没有额外的轮常数、白化密钥或末端线性层。

## 线性掩码与弱密钥

对两个 16 比特整数 `a,b`，定义

```text
<a,b> = parity(a & b).
```

若存在 16 比特输入掩码 `alpha` 和常数 `c in {0,1}`，使得对全部 `x in {0,...,2^16-1}` 都有

```text
<alpha, x> = <beta, E_k(x)> xor c,
```

则称 `k` 是输出掩码 `beta` 的弱密钥。此时相关系数为 `(-1)^c`。`beta=0` 的平凡情形不计为非平凡近似。

“两轮可分性条件所允许的输出掩码”是指满足下式的全部 `beta`：对每个 16 比特 `x`，

```text
<beta, Sbar(M(x))>
 = <beta, Sbar(M(x0,0,0,0))
             xor Sbar(M(0,x1,0,0))
             xor Sbar(M(0,0,x2,0))
             xor Sbar(M(0,0,0,x3))
             xor Sbar(M(0,0,0,0))>.
```

该条件只生成候选输出掩码；固定 `beta` 的弱密钥仍须按上面的全定义域等式完整判定。

# 模加置换与 boomerang 表定义

## 置换和位序

令 `n=16`、`mask=2^n-1`。所有变量均为 `n` 位无符号整数，`xor` 表示按位异或，`+` 和 `-` 均按模 `2^n` 计算。定义 `2n` 位置换

```text
S(L || R) = (((L + R) & mask) || R).
```

这里 `L`、`R` 是左右两个 `n` 位字，`||` 表示拼接；所有十六进制常量都按普通无符号整数解释，不进行字节翻转。

固定输入差分和逆向差分为

```text
(dl, dr) = (0x0102, 0x8102)
(nl, nr) = (0x0201, 0x840a)
```

## 共同中间量

对每个 `(L,R) in [0,2^n)^2`，定义

```text
A  = (L + R) & mask
Ad = ((L xor dl) + (R xor dr)) & mask
X  = ((A  xor nl) - (R xor nr)) & mask
Xd = ((Ad xor nl) - (R xor dr xor nr)) & mask
```

共同的 BCT 条件为

```text
X xor Xd = dl.
```

第二个输出字的对应差分恒等于 `dr`，因此无需重复写入条件。

## 四类精确计数

`BCT(dl,dr,nl,nr)` 是满足共同 BCT 条件的 `(L,R)` 数量。

对附加参数 `dll in [0,2^n)`，`UBCT(dl,dr,nl,nr,dll)` 是同时满足

```text
X xor Xd = dl
A xor Ad = dll
```

的 `(L,R)` 数量。

对附加参数 `nll in [0,2^n)`，令

```text
An = ((L xor nll) + (R xor nr)) & mask.
```

`LBCT(dl,dr,nl,nr,nll)` 是同时满足

```text
X xor Xd = dl
A xor An = nl
```

的 `(L,R)` 数量。

`EBCT(dl,dr,nl,nr,dll,nll)` 是三个条件

```text
X xor Xd = dl
A xor Ad = dll
A xor An = nl
```

同时成立时的 `(L,R)` 数量。

所有计数的公共样本空间大小均为 `2^(2n)`。因此，某个条目的精确概率等于其计数除以 `2^(2n)`。

按定义应满足以下分区恒等式：

```text
sum_dll UBCT(...,dll) = BCT(...)
sum_nll LBCT(...,nll) = BCT(...)
sum_nll EBCT(...,dll,nll) = UBCT(...,dll)
sum_dll EBCT(...,dll,nll) = LBCT(...,nll)
```

# LBlock 十七轮 bit-based division-property 形式化规范

## 状态与编号

分析对象是去掉轮密钥异或的 LBlock 64 位 round core。状态为 `s[0]...s[63]`，编号从 0 开始；机器可读掩码的最低有效位对应 `s[0]`。轮密钥异或不改变本题的 bit-based division-property 传播，因此不进入模型。

## 八个 S-box

每个 S-box 是 4 比特置换，表项按输入整数 `0...15` 排列：

```text
S0 = [e,9,f,0,d,4,a,b,1,2,8,3,7,6,c,5]
S1 = [4,b,e,9,f,d,0,a,7,c,5,6,2,8,1,3]
S2 = [1,e,7,c,f,d,0,6,b,5,9,3,2,4,8,a]
S3 = [7,6,8,b,0,f,3,e,9,a,c,d,5,2,4,1]
S4 = [e,5,f,0,7,2,c,d,1,8,4,9,b,a,6,3]
S5 = [2,d,b,c,f,e,0,9,7,a,6,3,1,8,4,5]
S6 = [b,9,4,e,0,f,a,d,6,c,5,7,3,8,1,2]
S7 = [d,a,f,0,e,4,9,b,2,1,8,3,7,5,c,6]
```

若某个 nibble 的四个状态位依次为 `[v0,v1,v2,v3]`，则 S-box 输入/输出整数均按

```text
u = v3*2^0 + v2*2^1 + v1*2^2 + v0*2^3
```

解释；也就是传入 S-box 的位名顺序为 `[v3,v2,v1,v0]`，列表第 0 个坐标对应整数最低位。

## 一轮 round core

每轮依次执行下列操作。所有下标都按上述状态编号。

1. 对状态位 `s[0]...s[31]` 按 8 比特字置换：旧字 `i` 写入新字 `rho[i]`，其中 `rho=[1,2,3,0]`。`s[32]...s[63]` 不变。
2. 把当前 `s[32+i]` 复制到临时位 `t[i]`，`i=0,...,31`。
3. 对 `t[4j]...t[4j+3]` 并行应用 `Sj`，`j=0,...,7`，位序遵循上一节。
4. 令 `q=[4,12,0,8,20,28,16,24]`。对每个 `i=0,...,31`，执行

   ```text
   s[i] <- s[i] XOR t[q[floor(i/4)] + (i mod 4)].
   ```

5. 交换两个 32 比特半部：新 `s[i]=旧 s[32+i]`、新 `s[32+i]=旧 s[i]`。

临时位不属于轮边界，轮末全部释放。连续执行上述关系 17 次。

## 局部 choice-vector 传播

choice bit 只取 0 或 1。一个可达 witness 必须由下列精确局部关系复合而成。

### 复制 `target <- source`

若目标在操作前为空，则 `(source,target)` 的合法传播为：

```text
00 -> 00
10 -> 10
10 -> 01
```

### 异或 `target <- source XOR target`

`(source,target)` 的合法传播为：

```text
00 -> 00
01 -> 01
10 -> 10
10 -> 01
11 -> 11
```

### S-box

对输入 choice nibble `a` 和输出 choice nibble `b`，令 `Sj,k(x)` 为 `Sj` 输出的第 `k` 个 Boolean 坐标函数，并定义

```text
F_b(x) = product over k with b[k]=1 of Sj,k(x),
```

空乘积为 1。把 `F_b` 写成 ANF；局部 transition `a -> b` 合法，当且仅当该 ANF 至少有一个非零单项式，其变量支持包含 `a` 的全部 1 坐标。不得把普通 DDT 非零关系误作该 division relation。

字置换和半部交换只重标号 choice bit。

## 固定查询与结论语义

初始 choice vector `k0` 为：

```text
k0[34] = 0,
k0[i] = 1  for every i != 34.
```

它对应输入位 34 固定，其余 63 位遍历全部取值。对每个输出位 `i`，目标 `e_i` 仅第 `i` 位为 1。

- 若存在从 `k0` 到 `e_i` 的 17 轮 division trail，该模型不能保证输出位 `i` 平衡。
- 若不存在该 trail，则该输出位在指定 `2^63` 个输入上的 XOR 和必为 0，即由 bit-based division property 保证平衡。

逐轮 witness 用 16 个十六进制数字表示一个 64 位 choice vector，最低有效位对应状态位 0。每条 witness 共 18 个轮边界掩码。

# WARP 部分 boomerang 特征语义

## 1. 轮传播

每个状态由 32 个半字节组成。第 `r` 轮第 `j` 个 S-box 的输入差分为 `X[r][2*j]`，输出差分为 `SX[r][j]`。附件中的 `pi_even` 和 `pi_odd` 均含 16 个位置。对上部和下部状态分别使用同一组等式：

```text
X[r+1][pi_even[j]] = X[r][2*j]
X[r+1][pi_odd[j]]  = X[r][2*j+1] xor SX[r][j]
```

具体差分数组中的 `-1` 表示自由值。只检查等式两边所需的全部值均不为 `-1` 的实例；跳过含自由值的等式不能记为违反。

## 2. 连接表

令 `S` 为附件给出的 4 比特 S-box，所有变量均遍历 `0,...,15`：

```text
DDT[a,b] = #{x : S(x) xor S(x xor a) = b}

Q(a,d) = {x : S(x) xor S(x xor a) xor S(x xor d)
                xor S(x xor a xor d) = 0}

FBCT[a,d] = #Q(a,d)

FBDT[a,b,d] = #{x in Q(a,d) : S(x) xor S(x xor a) = b}

FBET[a,b,d,c] = #{x in Q(a,d) :
                        S(x) xor S(x xor a) = b,
                        S(x xor a) xor S(x xor a xor d) = c}
```

若原始计数为 `N`，普通局部因子的概率为 `N/16`；`DDT2` 因子的概率为 `(N/16)^2`。局部权重均定义为概率的 `-log2`。

## 3. 因子参数

在轮号 `r`、S-box 位置 `j`，记：

```text
Delta_i = upper_x[r][2*j]
delta   = upper_sx[r][j]
Nabla_o = lower_x[r][2*j]
alpha   = lower_sx[r][j]
```

各类型使用的参数为：

```text
DDT_U   : DDT[Delta_i, delta]
DDT_L   : DDT[Nabla_o, alpha]
DDT2_U  : DDT[Delta_i, delta]^2
DDT2_L  : DDT[Nabla_o, alpha]^2
FBCT    : FBCT[Delta_i, Nabla_o]
FBDT_U  : FBDT[Delta_i, delta, Nabla_o]
FBDT_L  : FBDT[Nabla_o, alpha, Delta_i]
FBET    : FBET[Delta_i, delta, Nabla_o, alpha]
```

上式中的平方表示概率因子平方，不是整数计数平方后仍除以 16。

## 4. 因子分类

从 `pattern` 中读取以下六元组：

```text
(upper_active[r][2*j], upper_state_free[r][2*j], upper_sbox_free[r][j],
 lower_active[r][2*j], lower_state_free[r][2*j], lower_sbox_free[r][j])
```

其中 activity 标志 `1` 表示活跃，free 标志 `1` 表示自由。按下表分类；未命中任一行的位置没有局部概率因子。

| 六元组 | 类型 |
|---|---|
| `(1,0,0,0,0,0)` | `DDT_U` |
| `(0,0,0,1,0,0)` | `DDT_L` |
| `(1,0,0,1,1,1)` | `DDT2_U` |
| `(1,1,1,1,0,0)` | `DDT2_L` |
| `(1,0,1,1,0,1)` | `FBCT` |
| `(1,0,0,1,0,1)` | `FBDT_U` |
| `(1,0,1,1,0,0)` | `FBDT_L` |
| `(1,0,0,1,0,0)` | `FBET` |

总概率是所有已分类局部因子的精确乘积。四份候选彼此独立审计，不把它们的概率相加。

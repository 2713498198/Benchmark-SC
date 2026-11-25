# Two-subset division-property 审计规范

## 1. 变量与注入层

所有变量均为二进制整数，状态坐标为 `0..256`，所有状态下标都模 257。

64 个 diversifier 输入均活跃。将其分成 32 组，每组两个输入，并固定

```text
d[g,j] = 1,  0 <= g < 32,  j in {0,1}.
```

每个输入复制到四个分支：

```text
sum_{k=0}^3 c[g,j,k] = d[g,j].
```

每组的四个非线性输出满足

```text
c[g,0,k] + c[g,1,k] >= n[g,k],
c[g,0,k] + c[g,1,k] <= 2*n[g,k].
```

注入后的 257 位状态为

```text
b[32*k + g] = n[g,k]      for 0 <= g < 32, 0 <= k < 4,
b[i] = 0                   for 128 <= i < 257.
```

## 2. 一轮传播

第一轮的输入为 `b`，后续轮的输入为前一轮输出。记某轮输入为 `q`，该轮变量满足：

```text
p[j] = q[(121*j) mod 257],

u[j] + v[j] + w[j] = p[j],
t[j] = u[j] + v[(j+3) mod 257] + w[(j+10) mod 257],

x[j] + y[j] + z[j] = t[j],
y[(j+1) mod 257] + z[(j+2) mod 257] >= a[j],
y[(j+1) mod 257] + z[(j+2) mod 257] <= 2*a[j],
s[j] = x[j] + a[j].
```

轮常数不改变该 bit-based division-property 模型，故不出现在约束中。对 `r` 轮模型，最终向量是第 `r-1` 轮的 `s`。

## 3. 待判定集合

令 `e_i` 为仅坐标 `i` 等于 1 的 257 维单位向量，定义

```text
U_r = { i in {0,...,256} : r 轮模型在最终状态固定为 e_i 时可行 }.
B_r = {0,...,256} \ U_r.
```

在本题给定的有损 two-subset 模型内，`i in B_r` 才能证明对应输出坐标平衡；`i in U_r` 只表示模型允许该传播，不能证明真实 ANF 中相应项一定存在。

## 4. 待审计回调与摘要

原程序最小化最终向量的汉明重量，并逐个排除已经找到的单位向量。其停止回调为：

```python
def check_lower_bound(model, where):
    if where == GRB.Callback.MIP:
        objbnd = model.cbGet(GRB.Callback.MIP_OBJBND)
        if objbnd > 1:
            print("objective bound = " + str(objbnd))
            model.terminate()
```

五轮运行摘要为：

```text
Found unit vector 4.
Found unit vector 77.
Found unit vector 121.
objective bound = 1.0000000000000002
Model was terminated. An integral distinguisher exists because not all unit vectors appear.
Reported unit vectors: [4, 77, 121]
```

不得假设该摘要正确；需要用修正后的求解或独立模型核验。

## 5. 规范化摘要

对每个 `U_r`，按数值升序每行写一个十进制坐标，在最后保留 LF 换行，并计算这些 ASCII 字节的 SHA-256。机器产物可使用 JSON、CSV 或文本，也可用无歧义的闭区间表示连续全集。

## 6. 宿主 Gurobi 接口

工作区若存在 `cbsc_gurobi_solve.py`，可把静态 LP/MPS 模型提交给宿主许可证环境：

```bash
python3 cbsc_gurobi_solve.py model.lp \
  --output-json solver_result.json
```

可用 `--param KEY=VALUE` 传入标准 Gurobi 参数。该接口只接受模型文本，不暴露许可证文件；也可以完全不用 Gurobi。

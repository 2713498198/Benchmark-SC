# 关系空间定义

本题审计的是 9 轮 PRESENT 独立轮密钥模型产生的常数广义积分关系。轮函数边界为 9 次 `AddRoundKey -> S-box layer -> pLayer`，随后再加一次独立的末轮密钥。候选生成阶段已经结束，本题不要求重新执行该搜索。

`present9_relation_candidates.json` 中一条 relation 是若干二元项 `(u,v)` 的集合。`u` 和 `v` 均为 64 比特掩码；该项表示

```text
XOR_{x subseteq u} <v, E_K(x)> .
```

候选生成器已判定每条 relation 对所有独立轮密钥均为常数。审计时，把每个不同的 `(u,v)` 当作一个形式坐标，把 relation 当作该坐标宇宙上的 GF(2) 关联向量。因此：

- 两条 relation 相加就是对其 term 集合做对称差；
- 一组 relation 的维数是这些关联向量在 GF(2) 上的秩，不是 relation 的条数；
- `baseline_existing_method_label=true` 的行张成旧方法基线空间 `B`；该标签要求关系同时来自 `1+7+1` 分割且自身被判为线性等价；
- `linearly_equivalent_label=true` 的所有行张成线性等价关系空间 `L`；
- 全部行张成合并空间 `T`。

商空间维数按 `dim(T/B)=rank(T)-rank(B)`、`dim(L/B)=rank(L)-rank(B)` 和 `dim(T/L)=rank(T)-rank(L)` 计算。数据保证 `B` 是 `L` 的子空间、`L` 是 `T` 的子空间。

候选文件来自八个 `F1+F2+F3` 搜索分割。`source_splits` 仅记录来源，不是额外的向量坐标；同一 relation 出现在多个分割中时必须先按完整 term 集合去重。

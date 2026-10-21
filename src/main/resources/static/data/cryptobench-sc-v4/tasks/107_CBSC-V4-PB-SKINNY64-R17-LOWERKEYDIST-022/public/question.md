# Question 107. SKINNY-64-128 八轮相关 tweakey 联合差分概率分类

附件 `attachments/skinny64_r17_lower_instance.json` 完整定义了本题采用的 SKINNY-64-128 轮函数、状态与 bit 编号、tweakey 调度、四个相关 tweakey、八轮子区间以及联合差分事件。

对全部 128 位基准 tweakey `(TK1,TK2)`，精确求附件中定义的 `q(TK1,TK2)`。请报告所有不同的概率值、每个概率值对应的基准 tweakey 数量或比例，并给出这些类别关于基准 tweakey bit 的等价判定条件。结果必须覆盖零概率类别，并明确区分固定 tweakey 概率与对均匀 tweakey 取平均后的概率。

请返回可执行的精确分析代码、最终分类和可复核证据。随机抽样可以用于交叉检查，但不能代替精确结论。

# GIFT-64 九轮固定输入集合的 bit-based division-property 审计

附件 `attachments/gift64_division_property_spec.md` 完整定义了轮函数、位序、bit-based division trail 和固定输入边界。

对全部 64 个单位输出 choice vector 进行精确可达性审计，确定九轮后恰好哪些输出位由给定输入集合保证平衡。必须为每个可达单位输出保留一条从初始 choice vector 到该单位向量的逐轮 witness，并给出所有输出位的完整分类。

返回可直接执行的验证代码、机器可读的完整分类与 witness 产物，以及简洁的密码分析结论。不得用抽样、普通差分传播或仅报告部分平衡位代替 bit-based division-property 的精确可达性判定。

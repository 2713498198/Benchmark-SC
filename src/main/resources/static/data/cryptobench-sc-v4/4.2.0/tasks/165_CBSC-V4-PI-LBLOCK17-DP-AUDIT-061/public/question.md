# LBlock 十七轮固定输入集合的 bit-based division-property 审计

附件 `attachments/lblock17_division_property_spec.md` 完整定义了分析对象、位序、轮函数、局部 choice-vector 传播规则和固定输入边界。

请对全部 64 个单位输出 choice vector 做精确可达性判定，确定十七轮后恰好哪些输出位由给定输入集合保证平衡。对每个可达单位输出，保留一条包含初始边界和每轮结束边界的完整 witness，并检查相邻边界之间确实满足一轮传播关系。

返回可直接执行的验证代码、机器可读的完整分类与 witness 产物，以及简洁的密码分析结论。不得以抽样、普通差分传播、经验加密测试或只报告部分平衡位代替精确的 bit-based division-property 可达性审计。

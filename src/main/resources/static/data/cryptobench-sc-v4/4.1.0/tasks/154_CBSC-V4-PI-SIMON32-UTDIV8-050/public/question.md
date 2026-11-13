# SIMON-32 八轮固定输入集合的逐位 2-adic 可除性分析

附件 `attachments/simon32_ultrametric_spec.md` 完整定义了本题采用的七轮非线性 SIMON-32 reduced core、独立轮密钥模型、输入集合、位编号以及 ultrametric dominant-trail 评估规则。

对每个输出位 `i=0,...,31`，求一个由附件中的精确模型认证的指数 `nu_i`，使任意独立轮密钥取值下，输入集合中映射到输出位 `i` 为一的元素个数均可被 `2^nu_i` 整除。按输出位编号顺序返回完整的 32 维指数向量，并说明最大指数及其位置。

必须返回可直接执行的建模或验证代码、机器可读结果及求解证据。答案需要处理输入集合的 precursor 分解，并对每个输出位判断最低代价层是已出现密钥相关 trail，还是需要继续检查密钥无关 trails 的偶数抵消；只做普通 bit-based division property、只报告平衡位，或只给未经执行验证的向量，不构成完整答案。

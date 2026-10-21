# Question 117. SKINNY-64 三轮固定差分特征的密钥概率分布审计

附件 `attachments/skinny64_single_key_spec.json` 给出本题采用的 SKINNY-64-64 单密钥轮函数、状态顺序和密钥调度；`attachments/fixed_trail_r3.json` 固定了三轮中每层 S-box 的输入差分与输出差分。

请实现一个精确验证程序，求该固定差分特征在全部 64 位主密钥上的 fixed-key probability 分布。报告每个非零概率值、取得该概率的主密钥比例、零概率主密钥比例，并给出这些类别关于主密钥 nibble 或 bit 的等价判定条件；同时计算对均匀明文和均匀主密钥取平均后的 expected differential probability。

分析必须保留跨轮共享主密钥造成的具体值依赖，不能把轮密钥视为相互独立，也不能只把各 S-box 的 DDT 概率相乘。返回可执行代码、最终分布和可复核的验证证据。

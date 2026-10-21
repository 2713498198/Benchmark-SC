# Question 127. PRESENT-64 六轮固定掩码的零相关验证

附件 `attachments/present64_linear_spec.json` 给出本题采用的 PRESENT-64 S-box、线性逼近表定义、位编号、nibble 编号和 pLayer；`attachments/fixed_masks.json` 给出固定的输入与输出线性掩码。

请实现一个精确的 SAT、SMT、CP 或等价可穷尽验证程序。对每个 S-box，输入掩码与输出掩码构成的转移仅在线性逼近表对应项非零时允许；相邻轮通过给定 pLayer 连接。判断从固定输入掩码到固定输出掩码是否存在任意一条完整六轮线性 trail。

请报告 PRESENT S-box 线性逼近表中非零掩码对的总数、求解器最终状态及其密码分析含义，并提供可执行代码和机器可复核的求解证据。

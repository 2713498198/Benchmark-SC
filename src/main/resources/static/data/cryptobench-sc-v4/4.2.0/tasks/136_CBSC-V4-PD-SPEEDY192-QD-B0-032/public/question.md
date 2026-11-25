# SPEEDY-192 一轮固定 characteristic 的精确准差分概率审计

附件 attachments/speedy_b0_instance.json 完整定义了 SPEEDY-192 的状态编码、6 比特 S-box、ShiftColumns 映射和一组固定的 SB -> SC -> SB -> SC 差分 characteristic；attachments/quasidifferential_semantics.md 给出本题采用的准差分系数和求和语义。

现有未经验证的结论把全零中间掩码的相关 2187/72057594037927936 直接当作该 characteristic 的完整概率。请编写可执行程序，对这一结论作精确审计。

程序需要从 S-box 真值表独立计算局部准差分系数，推导所有可能产生非零相关的中间掩码支撑，并完整枚举其中每条非零 trail。对每条非零 trail，报告 192 比特中间掩码、符号和精确有理数相关；再作带符号求和，报告化简后的精确概率及其以 2 为底的权重，并判断未经验证的结论是否成立。必须给出候选支撑约简与枚举完整性的机器可核查证据，随机采样或只计算全零掩码不能代替精确结果。

返回可直接执行的代码、机器可读审计报告和最终结论。

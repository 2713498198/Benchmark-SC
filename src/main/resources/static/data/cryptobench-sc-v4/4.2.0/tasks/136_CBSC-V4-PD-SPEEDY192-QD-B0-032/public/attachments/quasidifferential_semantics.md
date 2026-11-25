# 准差分系数与状态约定

对 6 比特 S-box S，输入掩码 u、输入差分 a、输出掩码 v 和输出差分 b 的局部准差分系数定义为：

    Q_S(v,b;u,a) = 2^-6 * sum_x (-1)^(<u,x> xor <v,S(x)>)

求和只遍历满足 S(x) xor S(x xor a) = b 的 6 比特输入 x。内积是逐比特 GF(2) 内积。该系数应由满足差分条件的 S-box 输入的带符号计数精确得到，而不是使用浮点近似。

固定 characteristic 依次执行：

    X1 = SB(X0)
    X2 = SC(X1)
    X3 = SB(X2)
    X4 = SC(X3)

附件中的 difference_at_X0_hex、difference_at_X1_hex、difference_at_X4_hex 分别固定 X0、X1、X4 处的 XOR 差分。两端掩码为零；u 表示紧接第一层 SB 之后、尚未执行第一层 SC 的 192 比特中间掩码。

对固定 u，trail 相关是两层共 64 个局部 Q_S 系数按附件 SC 语义传播后的乘积。固定 characteristic 的精确概率是所有中间掩码 u 的 trail 相关之和。若某个不活跃 S-box 的一侧掩码为零，则可先利用局部系数为零的条件约简候选掩码支撑，但必须证明没有遗漏可能非零的掩码。

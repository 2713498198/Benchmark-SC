# KNOT-256 十轮固定差分特征的轮常数相消审计

附件 `attachments/knot256_qdt_instance.json` 给出 KNOT-256 的 S-box、位排列、前 10 个标准轮常数以及一条完整的 10 轮差分 characteristic。请从附件定义直接重建有符号 quasidifferential transition table，并枚举该 characteristic 中所有绝对系数等于普通差分特征概率的全局 mask trails。

计算普通独立随机轮常数模型下的 characteristic 概率；给出上述全体 trails 的数量、标准轮常数下的正负号分布和归一化有符号和；再给出一份可机器核验的完备性与相消证书，并据附件中的严格判据判断标准 KNOT-256 轮常数下该 characteristic 的精确概率。

返回可直接执行的验证代码、枚举证据和最终结论。这里只分析附件固定的 characteristic，不搜索其他 characteristic，也不评估完整 differential 或碰撞、伪造攻击。

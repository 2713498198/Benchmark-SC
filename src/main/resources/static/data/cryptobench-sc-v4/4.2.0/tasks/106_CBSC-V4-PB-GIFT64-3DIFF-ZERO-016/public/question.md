# Question 106. GIFT-64 固定两轮三差分特征的精确概率审计

附件 `attachments/gift64_3diff_instance.json` 完整定义了一个 GIFT-64 两轮中部函数、四条相关轮密钥分支和一条固定的三差分 characteristic。这里三差分表示同一有序 quartet 中相对第 0 条执行的三个 XOR 差分。

计算该 characteristic 的独立 S-box 层概率估计，并在四条执行共享附件所定义的基轮密钥时，确定联合事件对每个固定基轮密钥的精确有向明文数量和概率。需要明确区分“把两层看成独立时的乘积”与“同一组中间具体值必须同时满足全部约束时的精确概率”，并说明结论是否依赖基轮密钥。

返回可直接执行的验证代码、关键计数或可满足性证据及最终结论。这里审计的是附件给出的固定 characteristic，不是搜索其他 characteristic，也不是估计完整 boomerang distinguisher 或完整 GIFT-64 的概率。

# CryptoBench-SC V4 来源与署名

本页记录 `cryptobench-sc-v4@4.2.0` 的公开再分发来源。完整逐题映射保存在：

- `src/main/resources/static/data/cryptobench-sc-v4/4.2.0/attribution.json`
- 上游选手包 `CryptoBench-SC-170-20261125-auto1-public.zip`
- 上游维护者包 `CryptoBench-SC-170-20261125-auto1-judge.zip`

## 上游来源

- 项目主页：<https://gitee.com/xiandianzidaxue/cryptobench-sc>
- 发布套件：`CBSC-V4-COMPREHENSIVE-BLOCK-CIPHER-170-20260805`
- 上游评分修订：`20261125-auto1`
- 发布修订日期：`2026-11-25`
- 任务数量：170
- 公开附件：75

## 署名原则

- 保留上游 task ID、source task ID、来源套件和论文标识。
- 论文名称、作者、会议或期刊信息以逐题 `attribution.json` 和上游材料为准。
- 公开包只包含题干、附件和必要元数据，不包含隐藏答案、oracle、验证器或参考复现私密材料。
- 本页面不授予新的第三方许可。正式对外发布前，发布方应确认其拥有公开再分发和展示这些材料的权利。

## 发布检查

```bash
npm run validate:v4-release
```

若来源映射、公开题目 ID 或附件哈希不一致，发布门禁必须失败。

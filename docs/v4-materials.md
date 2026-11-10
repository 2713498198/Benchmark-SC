# V4 正式材料接入

V4 的隐藏答案、oracle、参考复放和验证器不能放在 `src/main/resources/static/`，也不能放进公开任务 ZIP。材料交付后先放入仅服务端可读的私有目录，并准备一份清单。4.1.0 导入使用同样的清单格式，版本号必须改为 `4.1.0`：

项目根目录的 `private/` 已加入 `.gitignore`。请将清单和材料放在该目录或其他受控目录中，并在提交前确认 `git status` 不包含任何隐藏材料文件。

```json
{
  "datasetId": "cryptobench-sc-v4",
  "version": "4.1.0",
  "evaluatorVersion": "cryptobench-sc-v4-evaluator-1.0.0",
  "publicCatalogSha256": "公开 catalog 文件的 SHA-256",
  "tasks": [
    {
      "id": "001_CBSC-V2-NL-A01",
      "verifierRef": "com.example.CryptobenchV4Verifier",
      "materials": [
        {"kind": "oracle", "path": "001/oracle.json", "sha256": "文件 SHA-256"}
      ]
    }
  ]
}
```

使用下面的命令校验清单、公开目录覆盖、材料路径和文件哈希：

```powershell
npm run validate:v4-materials -- .\private\v4-manifest.json `
  --public-catalog .\src\main\resources\static\data\cryptobench-sc-v4\catalog.json `
  --materials-root .\private\v4-materials `
  --validator-registry .\private\v4-validator-registry.json `
  --validator-root .\private\v4-validators
```

校验器只输出数据集身份、题目数、材料数和摘要哈希，不打印隐藏文件内容。校验通过后，还需要把 verifier 实现注册到 `BenchmarkEvaluator`，完成服务端集成测试和正式评分复核，才能把 `executionReady` 改为 `true`。

## 修正版公开附件覆盖层

如果原始题库只把公开附件列出但内容为空，可以使用导入器的
`--public-overlay` 补齐附件：覆盖层目录只能包含
`tasks/<task-id>/public/attachments/` 下与源包同名的非空文件，并且只能替换源包中的空文件。
题干、`hidden/`、`oracle/`、答案文件、额外路径和非空文件冲突都会被拒绝；未补齐的空附件仍会阻止导入。

```powershell
python scripts/import_cryptobench.py .\staged-release `
  --version 4.1.0 `
  --public-overlay .\public-attachment-correction `
  --output-dir .\src\main\resources\static\data\cryptobench-sc-v4\4.1.0
```

## 共用验证器的覆盖登记

验证器可以共用实现文件，但每道题必须在私有注册表中单独登记
`validator_ref`，并提供至少一个 `positive_tests`、`negative_tests` 和
`boundary_tests` 路径。注册表允许多个任务引用同一个实现，发布门禁仍要求任务 ID
完整覆盖且所有测试材料存在：

```powershell
python scripts/validate-v4-release.py .\staged-release `
  --validator-registry .\private\v4-validator-registry.json `
  --require-validators
```

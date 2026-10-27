# V4 正式材料接入

V4 的隐藏答案、oracle、参考复放和验证器不能放在 `src/main/resources/static/`，也不能放进公开任务 ZIP。材料交付后先放入仅服务端可读的私有目录，并准备一份清单：

项目根目录的 `private/` 已加入 `.gitignore`。请将清单和材料放在该目录或其他受控目录中，并在提交前确认 `git status` 不包含任何隐藏材料文件。

```json
{
  "datasetId": "cryptobench-sc-v4",
  "version": "4.0.0",
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
  --materials-root .\private\v4-materials
```

校验器只输出数据集身份、题目数、材料数和摘要哈希，不打印隐藏文件内容。校验通过后，还需要把 verifier 实现注册到 `BenchmarkEvaluator`，完成服务端集成测试和正式评分复核，才能把 `executionReady` 改为 `true`。

# CryptoBench

面向密码分析智能体的可交互前端原型。当前以分组密码为核心，预留流密码、后量子密码及更多密码分析方向。

## 运行

需要 Node.js 20 或以上版本，无需安装生产依赖。

```powershell
cd D:\Benchmark-SC
node server.mjs
```

浏览器访问 http://127.0.0.1:4173 。保持终端运行，按 Ctrl+C 停止。也可以双击 `start.cmd`。

## 已实现的页面和交互

- 首页：占满首屏的平台测评入口与 3D 密码几何体、工具 / 榜单 / PK 三个探索入口。
- 测评集：搜索、筛选、卡片 / 列表、任务条件、评分规则、版本与任务样例下载。
- 工具与文档：Agent 接入协议建议、本地部署配置下载、报告与复现说明。
- 测评：本地接口 / 云端 Agent 模式切换、四步向导、Agent / 模型配置（支持自定义模型标识）、预算与任务范围、完成 / 取消 / 失败 / 超时、报告导出。
- PK：模型或 Agent 选择、固定赛场条件、客观通过率、平局与双方成绩。
- 榜单：按条件筛选、复现配置、2–4 项对比、CSV 导出。
- 账号与工作台：服务端登录 / 注册 / 找回密码请求、体验账号、连接管理、实验记录、私有报告同步与单独公开。
- 管理：服务端管理员权限校验、JSON 测评集校验、预览、发布固定版本。
- 响应式布局、移动导航、键盘操作与减少动态效果支持。

## 当前边界

已导入 CryptoBench-SC V4 的真实公开题库，其余测评集与排行榜为明确标注的示例；执行采用浏览器模拟器，不会连接实际 Agent，不包含密码分析执行器、评分后端或真实云服务。输入密码和访问凭证不持久化；已有 `.env` 文件不由前端读取，静态服务器禁止访问点文件。

账号、会话、连接元信息、测评报告和自定义任务包由 `server.mjs` 的 JSON API 写入 `data/runtime/db.json`。密码使用 Node `scrypt` 哈希，登录状态使用 HttpOnly 会话 Cookie；连接、私有报告和实验记录按用户隔离，公开报告与已发布测评集才会进入公共读取范围。浏览器 localStorage 只保留兼容缓存，不能作为身份或权限来源。

当前仍是可本地运行的演示后端：Agent 执行、真实评分、邮件找回密码、云端部署和正式入榜流程需要接入生产服务。接入文档提供建议协议，而非已上线的第三方执行 API。

## 项目结构

```text
index.html             页面入口
css/                   基础样式、页面样式、测评资源样式
js/app.js              路由、页面框架、浏览器状态
js/core.js             图标、转义、导出等共用功能
js/data.js             测评集注册与演示数据
data/cryptobench-sc-v4/  真实题库索引、公开题干与下载包
scripts/import_cryptobench.py  可重复运行的公开题库导入脚本
js/pages/home.js       首页
js/pages/resources.js  测评集、工具、下载与文档
js/pages/arena.js      测评、PK、报告
js/pages/community.js  榜单、账号、工作台、管理
server.mjs             本地静态预览服务
tests/smoke.mjs        浏览器流程验收
tests/scenarios.mjs    异常状态、版本隔离、导出与账号验收
tests/auth-isolation.mjs 服务端账号、权限与多用户隔离验收
tests/redesign.mjs     五种屏宽、桌面 / 移动截图、键盘与视觉验收
tests/check.mjs        递归 JavaScript 语法检查
```

## 已导入的真实题库

CryptoBench-SC V4（网站版本 4.0.0）：127 道分组密码分析题目、26 个公开附件。支持搜索、按算法 / 难度 / 分析方向筛选、题干详情、附件下载与公开任务 ZIP 下载。两道计算压力题可单独筛选。

访问 `/#/datasets/cryptobench-sc-v4`。来源：https://gitee.com/xiandianzidaxue/cryptobench-sc ，源版本为 `CBSC-V4-CURATED-BLOCK-CIPHER-127-20260722`。

重新导入：

```powershell
python -B -X utf8 scripts/import_cryptobench.py "D:\Benchmark-题目\cryptobench-sc"
```

仅导入公开题干、附件与必要元信息；隐藏答案、参考复放、评分器及历史运行成绩不进入公开目录或 ZIP。真实题库的执行器尚未接入，网站禁止为该题库生成模拟成绩。真实运行需要后续接入独立执行与评分服务。静态部署时请包含 `data/` 目录。

## 验证

```powershell
node tests/check.mjs
python -B -X utf8 tests/verify-import.py
node tests/imported-dataset.mjs
# 先在另一个终端运行 node server.mjs，再执行服务端账号隔离验收
node tests/auth-isolation.mjs
node tests/smoke.mjs
node tests/redesign.mjs
```

浏览器验收需要 Playwright 以及 Chrome / Edge。可安装开发依赖 `npm install --no-save playwright`，流程与改版验收也支持通过 `PLAYWRIGHT_MODULE` 指定 Playwright 的 `index.mjs` 绝对路径，通过 `CHROME_PATH` 指定浏览器。

流程、账号和改版验收会创建测试账号、连接与测评集，请在独立项目副本启动服务，不复制 `data/runtime/`，再通过 `TEST_URL` 指定地址（默认 4173 端口）。题库与流程验收需要未发布自定义测评集的初始数据库，应先运行题库验收，再运行流程验收，最后运行账号与改版验收。流程截图默认保存在 `tests/artifacts/`；改版截图默认保存在 `tmp/verification/redesign/`，可用 `TEST_ARTIFACT_DIR` 自定义。改版验收覆盖 1440、1024、768、390、360px，保存桌面和移动截图及 JSON 结果。

## 设计取舍

采用与 OpenCompass 司南一致的深蓝主色（#1B3882）、白底和浅灰分区，保持大标题与简洁留白。首页为全屏首屏与探索平台两个区块：桌面端左侧介绍与入口，右侧大型 3D 密码几何体，移动端上下排列；首屏高度随可视区域适配。入口为“开始测评”和“浏览测评榜单”，真实题库与公开任务包通过测评集页面访问。密码几何体由本地 SVG 与 CSS 3D 构建，采用多轴旋转、轻微悬浮、鼠标响应与拖动惯性；点击图形或按空格 / 回车暂停与继续，方向键手动旋转、Home 复位，不再显示独立操作区。离开视口或页面后停止动画，系统开启减少动态效果时只响应手动旋转。完整题干、评分规则、下载和操作提示保留在相关功能页，长代码与复现配置按需展开。

针对密码分析任务，固定数据版本与预算，以验证器判定的任务通过率为主要比较指标；不使用主观投票，也不把不同密码类型混成一个总分。模型 PK 固定 Agent 模板与工具，Agent PK 记录各自的模型与工具条件。本地与云端共用规则，报告默认私有，主动同步后再单独决定是否公开。

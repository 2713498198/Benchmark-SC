# CryptoBench 测评榜单专业化升级验收

日期：2026-10-18

final result: passed

## 结果

完成已确认的“排名与关键结论优先、蓝白科研风格、默认示例榜单”。未发现尚待修复的 P0 / P1 / P2 问题。公开仅表示可见性，页面始终把数据标为“示例”或“未核验”；真实执行与正式认证仍属于后续工作。

- 1440 × 1000：首屏完整显示概览与五条排名。
- 1024 × 1000、768 × 1000：首屏显示五条排名，宽表格在自身容器内滚动。
- 390 × 844：首屏显示三条排名；360 × 800：首屏显示两条排名。
- 五种宽度均检查默认、展开依据及长名称，无页面横向溢出。

## 视觉目标、状态与证据

本轮视觉依据为已批准的升级计划、既有蓝白产品页面与改版前截图。参考图表的蓝色圆点、浅色面积与帕累托虚线保留在“性能分析”中；改为榜单优先是用户要求的结构变化。

所有下列路径相对于 `D:/Benchmark-SC/`。截图为本地 Chrome / Playwright 实际渲染，deviceScaleFactor = 1。基准状态：未登录、Mini-SPN / 1.0.0 / 标准预算 / 全部来源、五条内置样例；公开、并列、长名称、缺失字段场景使用浏览器内拦截数据，不写入服务端。

| 证据 | 文件与规格 |
| --- | --- |
| 改版前桌面 | `tmp/verification/leaderboard-authority-audit/01-desktop.png`，1440 × 1910，CSS 视口 1440 × 1000 |
| 改版前手机 | `tmp/verification/leaderboard-authority-audit/05-mobile-first-screen.png`，390 × 844 |
| 改版前依据 | `tmp/verification/leaderboard-authority-audit/02-evidence.png`，1158 × 213 |
| 最终桌面 | `tmp/verification/leaderboard-authority/viewport-ranking-1440.png`，1440 × 1000 |
| 最终手机 | `tmp/verification/leaderboard-authority/viewport-ranking-390.png`、`viewport-ranking-360.png`，390 × 844 / 360 × 800 |
| 最终重点依据 | `tmp/verification/leaderboard-authority/evidence-focus-1440.png`，浏览器元素截图 |
| 最终图表 | `tmp/verification/leaderboard-authority/chart-focus-1440.png`、`chart/final-cost-360.png` |

以下组合图已实际打开比较，不是仅核对文件存在：

1. `tmp/verification/leaderboard-authority/compare-before-final-desktop.png`：两侧截取首屏 1000px 高，各等比例缩至 720px 宽；比较整体信息层级。
2. `tmp/verification/leaderboard-authority/compare-before-final-mobile.png`：两侧原尺寸 390 × 844；比较手机信息密度、折叠筛选和首屏排名。
3. `tmp/verification/leaderboard-authority/compare-evidence.png`：两侧依据区域各等比例缩至 1000px 宽；右侧内容增加，底部白色补齐不属于页面。比较实际字段与原有固定样例字段。
4. `tmp/verification/leaderboard-authority/compare-density-1440.png`、`compare-density-360.png`：本轮首版与最终版相同视口、状态，复核间距与可见行数。
5. `tmp/verification/leaderboard-authority/compare-detail-360.png`：同一手机页面展开同一条依据。左侧是在浏览器内临时恢复旧 colspan=9 的诊断复现，右侧为最终 colspan=4；两侧按原尺寸截取顶部 1600px。用于复核列宽问题，不作为两次提交的历史截图。

另外已打开复核：`evidence-1440.png`、`long-record-360.png`、`compare-360.png`、`analysis-1440.png`、`chart/touch-tooltip-390.png`。两种图表横轴在全部五种宽度均保存截图；空态、公开依据、对比矩阵、搜索、缩放和定位状态均位于同一验收目录。

## 发现、修正与复核历史

- **P1，已修复：排序改变名次语义。** 原费用排序会重排名次，表头却仍像按通过率排序。现在在完整条件集内按一位小数通过率计算竞争排名（1、1、3），排序和名称搜索只改变展示。模型测试与浏览器测试覆盖，并列概览标明数量，排序列的 aria-sort 与箭头同步。
- **P1，已修复：依据包含固定配置。** 原依据显示统一 demo-v1、seed=42。最终显示记录实际提供的版本、种子、时间、配置、结果；缺失项为“未提供”。比较证据见 `compare-evidence.png`；实际种子 0、缺失字段、原始 JSON 和公开未核验类型均通过检查。
- **P2，已修复：首版首屏密度不足。** `first-ranking-1440.png` 只有四条完整排名，`first-ranking-360.png` 只有一条；辅助文案被截断。减少页头、条件栏和概览间距，改短辅助文案，手机正文辅助文字使用 12px。最终首屏分别为五条、两条，见两张 `compare-density-*`。
- **P2，已修复：手机展开依据压缩名称列。** 详情行原跨九列，但手机只显示四列，导致名称逐字折行。根据媒体查询同步 colspan，并在离开或重绘时清理监听。名称与模型显示最多两行，依据保留完整身份。见 `compare-detail-360.png`、`long-record-360.png`；浏览器新增展开列宽与长名称行高断言。
- **P2，已修复：图表浮层遮挡横轴按钮。** 将浮层限制在绘图区域内，保持键盘可进入“在排行榜中定位”。0–100% 轴下接近的数据点使用相邻区域裁剪透明点击区域，不移动真实坐标。四个费用点在 360px 触屏中逐一点击，身份均正确。
- **P2，已修复：输入法提交未触发搜索。** 改用 compositionend 事件监听，组合输入期间不重绘，提交后才搜索，并恢复焦点和光标。浏览器覆盖组合输入与提交结果。

## 五项视觉质量复核

- **字体与排版**：沿用系统字体及微软雅黑 / 苹方回退；深蓝标题 38px，手机 28px；主要数字突出且采用等宽数字特性；表格、依据与辅助信息保持清晰层级。长身份缩略只发生在表格，展开可查看全文。
- **间距与布局**：页头、条件、三项概览、视图切换、搜索、排名顺序清楚；去掉原先图表占满首屏的结构。桌面表格紧凑，手机四列，依据单列，对比矩阵局部滚动。
- **色彩与状态**：白色、浅蓝背景、深蓝标题及活动状态与现有产品一致。六个桌面/手机状态共检查 506 个可见文本节点，无不达标对比度项；见 `accessibility-results.json`。
- **图像与图形**：沿用项目 Logo 和图标；图表由现有 Canvas 绘制真实测量坐标，没有替换为装饰图片。圆点、前沿、网格和引导线在实际图表截图中复核清晰。没有新增图片素材。
- **内容与可信表达**：区分样例与公开未核验；每行是配置记录，不把 Agent 名称当成单一模型能力认证。显示比较条件、覆盖率、缺失费用及纵轴缩放提示；完整配置与结果按需展开。

## 交互与回归

| 检查 | 结果 |
| --- | --- |
| `node tests/check.mjs` | 23 个 JavaScript 模块语法通过 |
| `node tests/leaderboard-model.mjs` | 并列、稳定名次、搜索、成绩类型、覆盖率、缺失值、CSV 标记、不可变数据与纵轴通过 |
| `node tests/leaderboard-chart-model.mjs` | 前沿、重合坐标、零费用、无效数值及坐标范围通过 |
| `node tests/leaderboard-authority-browser.mjs` | 67 项通过 |
| `node tests/leaderboard-chart-browser.mjs` | 71 项通过；保留原业务断言，仅适配双视图、手机筛选及浮层语义 |
| `node tests/smoke.mjs` | 79 项通过，含注册登录、测评/PK、公开、下载、连接、管理员发布与版本隔离 |
| `node tests/redesign.mjs` | 246 项通过，95 组页面/视口布局 |
| `node tests/auth-isolation.mjs` | 账号隔离通过 |
| `python tests/verify-import.py` | 127 题、26 附件、字段白名单、哈希、公开 ZIP 校验通过 |
| `git diff --check` | 无空白错误 |

专项覆盖了：搜索与条件总数、并列最高、筛选焦点恢复、排序/视图保留选择、条件变化清空选择、2–4 项比较、公开模拟报告进入示例榜、JSON 原始结构等价、CSV 真实 demo 标记、空态恢复、零值、缺失依据、图表定位、观察器销毁、Tab / Escape / 方向键 / Home、手机触屏和减少动态效果。图表在减少动态设置下无动画且正常呈现。全部专项浏览器运行均无页面脚本错误。

结果 JSON：`tmp/verification/leaderboard-authority/authority-browser-results.json`、`chart/chart-browser-results.json`、`smoke/results.json`、`redesign/results.json`。

写入型测试仅针对 `tmp/leaderboard-acceptance-20261018-134056/` 的独立站点及新建数据库，端口 4187；测试站点只从项目复制前端、server.mjs 和公开题库，没有复制工作区账号数据库。该测试进程在验收后关闭。4173 预览保留。

## 实现与边界

- 主要代码：`js/leaderboard-model.js`、`js/pages/leaderboard.js`、`js/leaderboard-chart.js`、`css/leaderboard.css`；`community.js` 委托榜单渲染与清理，其他账号和管理逻辑保留。
- 排名、概览、表格、图表和导出源自同一模型；概览统计条件内全集，搜索只影响展示。费用与耗时排序不重算通过率名次。
- API、路由、数据库格式及既有 CSV 列保持兼容；未增加真实执行或认证服务。
- 在本机 Chrome 和移动端模拟视口验收，未声称完成真实 iOS / Android 设备或 Safari / Firefox 测试。
- 原上一轮图表 QA 已备份在 `tmp/verification/leaderboard-authority/previous-qa.md`。

实现清单已完成；无待确认项，无阻塞问题。

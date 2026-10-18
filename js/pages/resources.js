import { icon as sharedIcon, escapeHTML, downloadFile, datasetTitle } from '../core.js';
import { datasets } from '../data.js';
import { renderImportedDataset, bindImportedDataset } from './imported-dataset.js';

const iconAliases = { 'arrow-right': 'arrow', 'book-open': 'book', 'file-text': 'file', 'shield-check': 'shield', 'file-code': 'code', 'refresh-cw': 'refresh', 'check-circle': 'check', binary: 'code', 'git-branch': 'layers', 'flask-conical': 'activity', target: 'activity', plug: 'code', list: 'menu', monitor: 'terminal', play: 'arrow' };
const icon = (name, size = 20) => {
  if (name === 'arrow-left') return sharedIcon('arrow', size).replace('<svg ', '<svg style="transform:rotate(180deg)" ');
  if (name === 'arrow-up-right') return sharedIcon('arrow', size).replace('<svg ', '<svg style="transform:rotate(-45deg)" ');
  return sharedIcon(iconAliases[name] || name, size);
};
const palette = { blue: '#1b3882', violet: '#1b3882', teal: '#1b3882', orange: '#1b3882' };
let catalogDatasets = datasets;
let detailVersion = null;
const ui = { query: '', algorithm: 'all', difficulty: 'all', view: 'grid', detailTab: 'overview', downloadTab: 'desktop', docsTab: 'local' };
const e = value => escapeHTML(String(value ?? ''));
const link = (path, text, kind = 'secondary') => `<a class="btn ${kind}" href="#${path}">${text}</a>`;
const chip = (text, kind = '') => `<span class="badge ${kind}">${e(text)}</span>`;
const routeName = route => String(route || '/datasets').split('?')[0].replace(/^\//, '');
const heading = (eyebrow, title, description, action = '') => `<header class="page-header resource-heading"><div><h1>${title}</h1><p>${description}</p></div>${action}</header>`;
const options = (values, selected) => values.map(v => `<option value="${e(v)}" ${v === selected ? 'selected' : ''}>${e(v)}</option>`).join('');
const datasetIcon = d => `<span class="dataset-symbol" style="--dataset-color:${/^#[0-9a-f]{3,8}$/i.test(d.color || '') ? d.color : palette[d.color] || '#245BDB'}">${icon(d.icon || 'layers', 25)}</span>`;
const demoNote = `<span class="resource-demo">${icon('info', 14)} 真实 / 示例按标识区分</span>`;
const count = value => Array.isArray(value) ? value.length : value;
const taskCount = d => Number(count(d.tasks)) || 0;
const filteredDatasets = () => catalogDatasets.filter(d => {
  const haystack = [d.name, d.subtitle, d.algorithm, ...(d.tags || [])].join(' ').toLowerCase();
  return (!ui.query || haystack.includes(ui.query.toLowerCase())) && (ui.algorithm === 'all' || d.algorithm === ui.algorithm) && (ui.difficulty === 'all' || d.difficulty === ui.difficulty);
}).sort((a, b) => Number(b.demo === false) - Number(a.demo === false));
function datasetCard(d) {
  const summaries={
    'cryptobench-sc-v4':'覆盖差分、线性与积分分析，提供完整题干和公开附件。',
    'mini-spn':'从差分分布表到多轮路径，验证建模与搜索能力。',
    'key-recovery':'在固定查询预算下，验证约化轮子密钥恢复策略。',
    'linear-analysis':'构造线性逼近、估计偏差，验证统计分析结果。',
    'cipher-modeling':'分析轮函数与扩散结构，构建可复现的计算模型。'
  };
  return `<a href="#/datasets/${encodeURIComponent(d.id)}?version=${encodeURIComponent(d.version)}" class="card resource-dataset-card ${ui.view === 'list' ? 'dataset-row' : ''}"><div class="resource-dataset-card-top">${datasetIcon(d)}<span class="resource-dataset-card-arrow">${icon('arrow-up-right', 18)}</span></div><div class="resource-dataset-card-main"><div class="dataset-meta">${chip(d.demo === false ? '真实题库' : '演示样例', d.demo === false ? 'success' : 'neutral')}</div><h3>${e(datasetTitle(d))}</h3><p>${e(summaries[d.id]||d.description)}</p></div><div class="resource-dataset-card-bottom"><span>${taskCount(d)} ${d.demo === false ? '题' : '个任务'}</span><span>v${e(d.version)}</span></div></a>`;
}
function renderCatalog() {
  const results = filteredDatasets();
  return `<div class="container resources-page">${heading('', '测评集', '公开题库与演示任务，按固定版本组织。', link('/docs', '使用指南'))}<section class="catalog-controls" aria-label="筛选测评集"><div class="resource-search">${icon('search', 19)}<input type="search" id="dataset-search" placeholder="搜索测评集、算法或分析方法…" value="${e(ui.query)}" aria-label="搜索测评集"></div><div class="catalog-filter"><label for="dataset-algorithm">密码算法</label><select id="dataset-algorithm"><option value="all">全部算法</option>${options([...new Set(catalogDatasets.map(d => d.algorithm))], ui.algorithm)}</select></div><div class="catalog-filter"><label for="dataset-difficulty">任务难度</label><select id="dataset-difficulty"><option value="all">全部难度</option>${options([...new Set(catalogDatasets.map(d => d.difficulty))], ui.difficulty)}</select></div></section><div class="catalog-result-bar"><span>共 <strong>${results.length}</strong> 个测评集 ${demoNote}</span><div class="view-switch" aria-label="视图切换"><button type="button" data-view="grid" class="${ui.view === 'grid' ? 'active' : ''}" aria-label="卡片视图" aria-pressed="${ui.view === 'grid'}">${icon('grid', 17)}</button><button type="button" data-view="list" class="${ui.view === 'list' ? 'active' : ''}" aria-label="列表视图" aria-pressed="${ui.view === 'list'}">${icon('list', 17)}</button></div></div><section class="dataset-results ${ui.view === 'list' ? 'list' : ''}" aria-live="polite">${results.length ? results.map(datasetCard).join('') : `<div class="empty-state card">${icon('search', 34)}<h3>没有匹配的测评集</h3><p>试试其他关键词或筛选条件。</p><button class="btn" data-clear-filters>清除筛选</button></div>`}</section><section class="resource-contribute"><div><h2>发布你的测评集。</h2><p>导入任务包，校验并发布固定版本。</p></div>${link('/admin', `管理测评集 ${icon('arrow', 16)}`)}</section></div>`;
}
function overview(d) {
  return `<div class="detail-content-grid"><section class="card detail-section"><h2>关于测评集</h2><p>${e(d.description)}</p><div class="detail-callout">${icon('info', 18)}<span>演示测评集。教学密码与约化轮实验的成绩不代表对完整工业密码的破解能力。</span></div><h3>任务与验证</h3><p>任务目标、轮数和资源预算由版本固定，产物通过验证器检查；运行配置与证据随报告保存。</p>${link('/docs?tab=report', '报告与复现说明', 'ghost')}</section><aside class="card detail-section detail-at-glance"><h3>测评集概览</h3><dl><div><dt>密码方向</dt><dd>${e(d.type)}</dd></div><div><dt>目标算法</dt><dd>${e(d.algorithm)}</dd></div><div><dt>任务数量</dt><dd>${taskCount(d)} 个</dd></div><div><dt>难度</dt><dd>${e(d.difficulty)}</dd></div><div><dt>版本</dt><dd>${e(d.version)}</dd></div></dl></aside></div>`;
}
function conditions(d) {
  return `<section class="card detail-section"><span class="section-kicker">TASK SPECIFICATION</span><h2>任务与实验条件</h2><p>以下为平台支持的任务结构示例。真实测试前，组织者需为每道题发布确定的条件和验证器。</p><div class="table-scroll"><table class="resource-table"><thead><tr><th>条件</th><th>示例说明</th><th>记录方式</th></tr></thead><tbody><tr><td>密码与轮数</td><td>${e(d.algorithm)}，具体轮数由子任务给出</td><td>每个任务固定记录</td></tr><tr><td>攻击目标</td><td>差分特征、线性近似或密钥恢复等</td><td>以题目目标为准</td></tr><tr><td>已知信息</td><td>公开结构、允许使用的数据与查询接口</td><td>在任务输入中声明</td></tr><tr><td>资源预算</td><td>运行时间、调用次数、Token 与查询上限</td><td>随运行配置保存</td></tr><tr><td>提交产物</td><td>结构化分析结果、程序或实验记录</td><td>通过任务验证器检查</td></tr></tbody></table></div><h3>公开任务样例</h3><div class="resource-code-head"><span>task.example.json · 建议格式</span><button class="btn small secondary" data-download-dataset="${e(d.id)}@${e(d.version)}">${icon('download', 15)} 下载样例</button></div><pre class="resource-code"><code>${e(JSON.stringify(sampleTask(d), null, 2))}</code></pre><div class="detail-callout">${icon('shield', 19)}<span>公开样例仅描述任务输入与产物要求，不包含隐藏答案或正式评分用例。</span></div></section>`;
}
function scoring() {
  return `<section class="card detail-section"><span class="section-kicker">VERIFICATION</span><h2>评分规则</h2><p>以可验证的攻击产物判断任务是否成功；推理文本长度、模型自评与用户投票不计入客观成绩。</p><div class="scoring-flow"><div><b>01</b><h3>检查产物</h3><p>解析结果，核对必需字段和输出格式。</p></div><span>${icon('arrow-right', 18)}</span><div><b>02</b><h3>执行验证</h3><p>使用与测评集版本匹配的验证器。</p></div><span>${icon('arrow-right', 18)}</span><div><b>03</b><h3>保存证据</h3><p>记录通过状态、失败原因和资源消耗。</p></div></div><div class="table-scroll"><table class="resource-table"><thead><tr><th>结果</th><th>解释</th><th>计分处理</th></tr></thead><tbody><tr><td>${chip('通过', 'success')}</td><td>在允许预算内满足任务成功判据</td><td>计入通过数量</td></tr><tr><td>${chip('未通过', 'danger')}</td><td>产物缺失、格式错误或未满足验证条件</td><td>不计入通过数量</td></tr><tr><td>${chip('超时', 'warning')}</td><td>到达当前任务规定的资源上限</td><td>不计入通过数量</td></tr><tr><td>${chip('连接失败', 'neutral')}</td><td>Agent 未成功接入或执行服务不可达</td><td>单独报告，不伪装成能力分数</td></tr></tbody></table></div><p class="scoring-formula">任务通过率 <span>=</span> 已通过任务数 <span>÷</span> 选定范围内的总任务数 <span>× 100%</span></p><p class="muted">演示原型中的流程使用模拟数据。正式接入后，具体成功判据与异常处理需随评测器版本公开。</p></section>`;
}
function version(d) {
  return `<section class="card detail-section"><span class="section-kicker">VERSIONS & REPRODUCIBILITY</span><h2>版本与复现</h2><p>已发布版本应保持固定。内容或评分规则发生变化时发布新版本，历史成绩继续关联原来的数据与评测器。</p><div class="resource-version"><span class="version-marker"></span><div><div class="version-header"><strong>${e(d.version)}</strong>${chip('当前演示版本', 'primary')}</div><p>首次展示任务元信息、公开样例和验证流程。</p><span class="muted">演示目录 · 尚未发布正式任务包</span></div></div><h3>复现记录清单</h3><div class="repro-grid">${['数据集 ID 与版本', '评测器版本', 'Agent 与基础模型', '任务范围与随机种子', '工具配置与资源预算', '运行环境与验证产物'].map(t => `<div>${icon('check', 17)} ${t}</div>`).join('')}</div><div class="detail-callout">${icon('info', 19)}<span>跨环境对比时保留硬件与网络信息，时间和费用作为独立指标展示。本地与云端执行同一套评分规则。</span></div>${link('/docs', `查看本地与云端复现指南 ${icon('arrow-right', 17)}`)}</section>`;
}
function sampleTask(d) {
  return { schema: 'cryptobench-proposed-v1', demo: true, dataset: d.id, version: d.version, task: { id: `${d.id}-sample-001`, algorithm: d.algorithm, goal: '寻找满足题目约束的密码分析特征', input: { rounds: '由题目发布者指定', public_parameters: {} }, output: { artifact: '结构化特征或攻击产物', evidence: '可复现的验证记录' } }, budget: { timeout_seconds: 300, max_agent_calls: 20 } };
}
function renderDataset(id, route) {
  const d = catalogDatasets.find(item => String(item.id) === id && (!detailVersion || item.version === detailVersion));
  if (!d) return `<div class="container resources-page"><div class="empty-state card">${icon('layers', 36)}<h1>测评集不存在</h1><p>链接可能已更改，请返回测评集目录查看。</p>${link('/datasets', '返回测评集', 'primary')}</div></div>`;
  if (d.demo === false) return renderImportedDataset(d, route);
  const tabs = [['overview', '概览'], ['conditions', '任务与条件'], ['scoring', '评分规则'], ['version', '版本与复现']];
  return `<div class="container resources-page"><a class="resource-back" href="#/datasets">${icon('arrow-left', 16)} 返回测评集</a><header class="dataset-detail-heading"><div class="detail-title-icon">${datasetIcon(d)}</div><div><div class="dataset-meta">${chip(d.type)}${chip(d.difficulty, 'neutral')}${chip('演示', 'warning')}</div><h1>${e(d.name)}</h1><div class="detail-title-meta"><span>${e(d.algorithm)}</span><span>${taskCount(d)} 个任务</span><span>${e(d.version)}</span></div></div><div class="dataset-detail-actions">${link(`/evaluate?dataset=${encodeURIComponent(d.id)}&version=${encodeURIComponent(d.version)}&environment=cloud`, `${icon('play', 17)} 云端测评`, 'primary')}${link('/download', `${icon('download', 17)} 本地版与部署`)}${link(`/leaderboard?dataset=${encodeURIComponent(d.id)}&version=${encodeURIComponent(d.version)}`, '查看对应榜单', 'ghost')}</div></header><nav class="tabs resource-tabs" aria-label="测评集详情">${tabs.map(([id, label]) => `<button data-detail-tab="${id}" class="${ui.detailTab === id ? 'active' : ''}" aria-current="${ui.detailTab === id ? 'page' : 'false'}">${label}</button>`).join('')}</nav>${ui.detailTab === 'conditions' ? conditions(d) : ui.detailTab === 'scoring' ? scoring() : ui.detailTab === 'version' ? version(d) : overview(d)}</div>`;
}
function renderTools() {
  const tools = [
    ['plug', 'Agent 接入', '服务协议、模型与工具配置。', '接入说明', '/docs?tab=agent'],
    ['terminal', '测评执行', '配置任务、版本与资源预算。', '开始测评', '/evaluate'],
    ['binary', '密码分析工具', '将求解器与脚本接入 Agent。', '工具约定', '/docs?tab=agent'],
    ['shield-check', '结果验证', '验证产物，记录任务与异常状态。', '验证说明', '/docs?tab=report'],
    ['file-text', '报告与复现', '导出成绩、配置与验证证据。', '报告格式', '/docs?tab=report'],
    ['git-branch', '测评集管理', '导入、校验并发布任务包。', '管理测评集', '/admin']
  ];
  return `<div class="container resources-page">${heading('', '测评工具', '从 Agent 接入到结果复现。', link('/docs', '接入文档'))}<div class="demo-banner">${icon('info',16)}当前采用模拟执行，不发起真实 Agent 调用。</div><section class="resource-paths"><article class="card resource-path"><h2>云端测评</h2><p>配置 Agent 服务，在线查看运行与报告。</p>${link('/evaluate?environment=cloud', `开始云端测评 ${icon('arrow', 16)}`, 'primary')}</article><article class="card resource-path"><h2>本地接口测试</h2><p>配置本机接口，导出或主动同步报告。</p>${link('/evaluate?environment=local', `本地接口测试 ${icon('arrow', 16)}`)}<a class="path-footnote" href="#/download">本地版与部署方式 ↗</a></article></section><div class="resource-section-heading"><h2>实验工具</h2></div><section class="tool-grid">${tools.map(([glyph, title, body, label, url]) => `<article class="card resource-tool-card"><div class="tool-icon">${icon(glyph, 24)}</div><h3>${title}</h3><p>${body}</p><a href="#${url}">${label} ${icon('arrow', 15)}</a></article>`).join('')}</section></div>`;
}
const dockerCompose = `services:\n  cryptobench-ui:\n    image: nginx:alpine\n    ports:\n      - "8080:80"\n    volumes:\n      - ./site:/usr/share/nginx/html:ro\n    restart: unless-stopped\n`;
const localConfig = { schema: 'cryptobench-proposed-v1', demo: true, environment: 'local', agent: { endpoint: 'http://127.0.0.1:8000', credential: '由本地凭证管理器提供，不写入报告' }, evaluation: { dataset: '选择本地已获取的数据集', version: '固定版本', timeout_seconds: 300 }, report: { export_format: 'json', auto_publish: false, sync_credentials: false } };
const deployNotes = `CryptoBench 本地前端部署说明\n\n本项目是可交互前端原型，不含真实 Agent 执行器、评分后端或桌面安装包。\n\n方式一：Node.js 本地预览\n1. 安装 Node.js 20 或更新版本。\n2. 在项目目录执行 node server.mjs。\n3. 打开终端中显示的本地地址。\n\n方式二：Docker 静态界面\n1. 在工作目录新建 site 文件夹，将项目的 index.html、css、js、assets、data 放入 site。\n2. 将下载的 compose.yaml 放在 site 的同级目录。\n3. 执行 docker compose up -d。\n4. 访问 http://localhost:8080。\n5. 使用 docker compose down 停止。\n\n任务执行与评分需另行连接真实后端。建议协议见网站接入文档。\n本地配置样例仅说明字段，不会自动启动模型或上传报告。\n`;
function renderDownload() {
  return `<div class="container resources-page">${heading('LOCAL WORKSPACE', '本地版与部署', '部署当前平台，获取本地配置。', link('/docs', `${icon('book-open', 17)} 本地测试指南`))}<div class="download-tabs" role="tablist" aria-label="本地交付方式"><button data-download-tab="desktop" class="${ui.downloadTab === 'desktop' ? 'active' : ''}" role="tab" aria-selected="${ui.downloadTab === 'desktop'}">${icon('monitor', 19)} 桌面应用</button><button data-download-tab="deploy" class="${ui.downloadTab === 'deploy' ? 'active' : ''}" role="tab" aria-selected="${ui.downloadTab === 'deploy'}">${icon('server', 19)} 本地部署网页版</button></div>${ui.downloadTab === 'desktop' ? `<section class="card download-feature"><h2>桌面应用，开发中。</h2><p>计划支持本地题库、Agent 接入与报告管理。</p><div class="download-platform">Windows ${chip('安装包待构建', 'warning')}</div><button class="btn primary" disabled>${icon('download', 17)} 桌面安装包暂未发布</button><a class="download-alternative" href="#/download" data-switch-deploy>使用本地部署网页版 ${icon('arrow', 16)}</a></section>` : `<section class="card download-deploy"><div><span class="section-kicker">SELF-HOSTED WEB INTERFACE</span><h2>在本地浏览器中打开平台</h2><p>使用 Node.js 预览当前项目，或用 Docker 托管静态界面。以下操作部署前端，真实任务执行需要另行接入测评服务。</p>${chip('前端可运行', 'success')} ${chip('执行后端需接入', 'warning')}</div><div class="deployment-steps"><article><b>01</b><div><h3>准备项目文件</h3><p>将项目的 index.html、css、js、assets、data 放入同一个 site 目录；把 compose.yaml 放在 site 同级。</p></div><button class="btn small secondary" data-download="compose">${icon('download', 16)} compose.yaml</button></article><article><b>02</b><div><h3>启动静态界面</h3><p>需要已安装并启动 Docker Desktop。</p><div class="copy-line"><code>docker compose up -d</code><button data-copy="docker compose up -d" aria-label="复制 Docker 启动命令">${icon('copy', 16)}</button></div></div></article><article><b>03</b><div><h3>打开本地地址</h3><p><code>http://localhost:8080</code> · 使用 <code>docker compose down</code> 停止容器。</p></div><button class="btn small secondary" data-download="notes">${icon('download', 16)} 部署说明</button></article></div><div class="node-option"><strong>使用 Node.js？</strong><span>在项目目录执行 <code>node server.mjs</code>，按终端提示打开本地地址。</span><button class="btn small secondary" data-copy="node server.mjs">${icon('copy', 15)} 复制命令</button></div></section>`}<section class="download-bottom"><article class="card detail-section"><div class="tool-icon">${icon('file-code', 22)}</div><h3>获取本地配置样例</h3><p>Agent 地址、任务版本与报告选项。</p><button class="btn secondary" data-download="config">${icon('download', 16)} 下载 local-config.json</button></article><article class="card detail-section"><div class="tool-icon">${icon('refresh-cw', 22)}</div><h3>报告与同步</h3><p>报告可导出或主动同步；公开需单独确认，凭证不随报告上传。</p>${link('/docs?tab=report', `查看报告与同步说明 ${icon('arrow-right', 16)}`, 'ghost')}</article></section></div>`;
}
function docsLocal() {
  return `<span class="section-kicker">LOCAL EVALUATION</span><h2>本地测试快速开始</h2><p>当前项目提供可运行的前端界面。本地执行器和评分服务尚未实现，以下接口为后续接入的建议协议。</p><div class="docs-steps"><article><span>1</span><div><h3>启动前端界面</h3><p>安装 Node.js 后，在项目目录执行 <code>node server.mjs</code>，使用终端显示的地址访问。Docker 配置可在本地下载页获取。</p>${link('/download', '查看本地部署方式', 'small secondary')}</div></article><article><span>2</span><div><h3>准备固定版本的测评集</h3><p>CryptoBench-SC V4 已导入 127 道真实题目，可在测评集详情中下载公开任务包。其他标注为演示样例的测评集仅提供任务结构。真实评分需接入对应执行器与评分材料。</p></div></article><article><span>3</span><div><h3>调用本地执行接口</h3><p>建议执行器接收数据集版本、Agent 连接信息与预算，返回可追踪的 run_id。</p></div></article></div><div class="resource-code-head"><span>POST /v1/runs · 建议接口，尚无后端实现</span><button class="btn small secondary" data-copy-example="local">${icon('copy', 15)} 复制</button></div><pre class="resource-code"><code>${e(JSON.stringify({ dataset_id: catalogDatasets[0]?.id || 'mini-spn', dataset_version: catalogDatasets[0]?.version || 'v1.0', agent_endpoint: 'http://127.0.0.1:8000', budget: { timeout_seconds: 300, max_agent_calls: 20 }, seed: 42 }, null, 2))}</code></pre><div class="detail-callout">${icon('info', 18)}<span>使用本地模型和已下载任务包可规划离线运行；连接远程模型时仍需要网络。前端演示不会实际调用你的本地 Agent。</span></div>`;
}
function docsAgent() {
  return `<span class="section-kicker">AGENT INTEGRATION</span><h2>让你的 Agent 接入测评</h2><p>平台面向已有 Agent 服务，模型与工具由服务声明。以下为建议的最小 HTTP 协议，正式集成前需与真实测评后端确认。</p><div class="table-scroll"><table class="resource-table"><thead><tr><th>建议端点</th><th>用途</th><th>关键输出</th></tr></thead><tbody><tr><td><code>GET /health</code></td><td>连通性检查</td><td>服务状态与版本</td></tr><tr><td><code>GET /capabilities</code></td><td>说明模型与工具能力</td><td>模型列表、是否允许切换、工具列表</td></tr><tr><td><code>POST /analyze</code></td><td>接收单个分析任务</td><td>产物、证据与资源消耗</td></tr></tbody></table></div><h3>任务请求示例</h3><div class="resource-code-head"><span>POST /analyze · 建议协议</span><button class="btn small secondary" data-copy-example="agent">${icon('copy', 15)} 复制</button></div><pre class="resource-code"><code>${e(JSON.stringify({ task_id: 'demo-001', model: 'your-model-id', task: { algorithm: 'Mini-SPN', rounds: 3, objective: 'search_differential_characteristic', parameters: {} }, budget: { timeout_seconds: 300, max_tool_calls: 20 }, seed: 42 }, null, 2))}</code></pre><h3>模型选择与 PK</h3><p>服务允许切换模型时，前端展示可选模型；模型固定在 Agent 服务中时只读展示。模型 PK 使用相同 Agent 模板与工具配置；Agent PK 则记录各自的工具与运行条件。</p><div class="detail-callout">${icon('lock', 18)}<span>访问凭证应由执行服务安全保存。共享报告和同步配置时删除凭证字段，浏览器演示仅保留非敏感元信息。</span></div>${link('/evaluate', `体验云端接入流程 ${icon('arrow-right', 16)}`, 'primary')}`;
}
function docsReport() {
  return `<span class="section-kicker">REPORTS & REPRODUCIBILITY</span><h2>把成绩变成可复现的实验记录</h2><p>报告需要同时说明“得到了什么结果”和“在什么条件下得到”。本地与云端采用相同的数据版本、判据与报告结构。</p><div class="report-fields">${[['实验身份', 'run_id、数据集 ID、数据与评测器版本'], ['执行条件', 'Agent / 模型版本、工具、预算、随机种子与硬件'], ['任务结果', '通过、未通过、超时、连接失败或取消'], ['验证证据', '结构化产物、验证器输出与资源消耗']].map(([a, b]) => `<div><strong>${a}</strong><span>${b}</span></div>`).join('')}</div><h3>建议报告格式</h3><pre class="resource-code"><code>${e(JSON.stringify({ schema: 'cryptobench-proposed-v1', run_id: 'demo-run-001', demo: true, dataset: { id: catalogDatasets[0]?.id || 'mini-spn', version: catalogDatasets[0]?.version || 'v1.0' }, evaluator_version: 'demo-v1', environment: 'local', results: [{ task_id: 'demo-001', status: 'passed', artifact: {}, verification: { passed: true, evidence: '演示证据占位' } }], visibility: 'private' }, null, 2))}</code></pre><h3>同步与公开是两步操作</h3><p>用户选择本地报告后主动同步，再单独选择是否公开及提交榜单。相同 run_id 的重复同步应合并，网络中断保留本地文件并允许重试。本地运行时由服务端 API 保存状态，浏览器缓存只用于兼容旧版流程。</p><div class="detail-callout">${icon('shield-check', 18)}<span>本地来源不降低成绩资格。正式入榜需要完整任务范围、匹配的验证器版本与足够的证据；耗时和费用独立展示。</span></div>`;
}
function renderDocs(route) {
  const queryTab = new URLSearchParams(String(route).split('?')[1] || '').get('tab');
  if (['local', 'agent', 'report', 'methodology', 'about'].includes(queryTab)) ui.docsTab = queryTab;
  return `<div class="container resources-page">${heading('DOCUMENTATION', '接入文档', '部署、服务协议与报告格式。')}<div class="resource-docs-layout"><aside class="card resource-docs-nav"><span class="section-kicker">开始使用</span><a href="#/docs?tab=local" class="${ui.docsTab === 'local' ? 'active' : ''}">${icon('monitor', 18)} 本地测试</a><a href="#/docs?tab=agent" class="${ui.docsTab === 'agent' ? 'active' : ''}">${icon('plug', 18)} Agent 接入</a><a href="#/docs?tab=report" class="${ui.docsTab === 'report' ? 'active' : ''}">${icon('file-text', 18)} 报告与复现</a><a href="#/docs?tab=methodology" class="${ui.docsTab === 'methodology' ? 'active' : ''}">${icon('shield', 18)} 测评方法</a><a href="#/docs?tab=about" class="${ui.docsTab === 'about' ? 'active' : ''}">${icon('info', 18)} 关于平台</a><hr><a href="#/datasets">${icon('layers', 18)} 浏览测评集</a><a href="#/download">${icon('download', 18)} 本地版与部署</a></aside><article class="card resource-docs-content">${ui.docsTab === 'agent' ? docsAgent() : ui.docsTab === 'report' ? docsReport() : ui.docsTab === 'methodology' ? scoring() : ui.docsTab === 'about' ? '<h2>关于 CryptoBench</h2><p>面向密码分析智能体的研究测评平台。当前聚焦分组密码，采用固定任务、版本、预算与验证器，逐步扩展其他密码方向。</p><h3>设计参考</h3><p>借鉴 OpenCompass 的测评集与工具组织，借鉴 Aider 的通过率、费用和复现配置，针对密码分析保留客观验证与本地 / 云端两种路径。</p><p><a class="text-link" href="https://opencompass.org.cn/home" target="_blank" rel="noopener">OpenCompass ↗</a> · <a class="text-link" href="https://aider.chat/docs/leaderboards/" target="_blank" rel="noopener">Aider Leaderboards ↗</a></p><div class="detail-callout">当前为可运行前端原型，演示结果不代表真实模型能力。正式测评集由维护者提供，真实执行与云服务需要接入后端。</div>' : docsLocal()}</article></div></div>`;
}
function collapseExamples(html) {
  return html.replace(/(<div class="resource-code-head">[\s\S]*?<\/div>)(<pre class="resource-code">[\s\S]*?<\/pre>)/g, '<details class="code-disclosure"><summary>查看代码示例</summary>$1$2</details>');
}
export function render(route, state) {
  catalogDatasets = [...datasets, ...(state?.customDatasets || []).filter(d => d.published)];
  detailVersion = new URLSearchParams(String(route).split('?')[1] || '').get('version');
  const current = routeName(route);
  if (current.startsWith('datasets/')) return collapseExamples(renderDataset(current.slice(9), route));
  if (current === 'tools') return renderTools();
  if (current === 'download') return renderDownload();
  if (current === 'docs') return collapseExamples(renderDocs(route));
  return renderCatalog();
}
export function bind(ctx) {
  const imported = catalogDatasets.find(d => d.demo === false);
  if (imported) bindImportedDataset(ctx, imported);
  const root = document.getElementById('main') || document;
  const rerender = () => ctx.render();
  root.querySelector('#dataset-search')?.addEventListener('input', event => {
    ui.query = event.target.value;
    const cursor = event.target.selectionStart;
    rerender();
    const input = document.getElementById('dataset-search');
    input?.focus();
    try { input?.setSelectionRange(cursor, cursor); } catch {}
  });
  root.querySelector('#dataset-algorithm')?.addEventListener('change', event => { ui.algorithm = event.target.value; rerender(); });
  root.querySelector('#dataset-difficulty')?.addEventListener('change', event => { ui.difficulty = event.target.value; rerender(); });
  root.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => { ui.view = button.dataset.view; rerender(); }));
  root.querySelector('[data-clear-filters]')?.addEventListener('click', () => { ui.query = ''; ui.algorithm = 'all'; ui.difficulty = 'all'; rerender(); });
  root.querySelectorAll('[data-detail-tab]').forEach(button => button.addEventListener('click', () => { ui.detailTab = button.dataset.detailTab; rerender(); }));
  root.querySelectorAll('[data-download-tab]').forEach(button => button.addEventListener('click', () => { ui.downloadTab = button.dataset.downloadTab; rerender(); }));
  root.querySelector('[data-switch-deploy]')?.addEventListener('click', event => { event.preventDefault(); ui.downloadTab = 'deploy'; rerender(); });
  root.querySelectorAll('[data-download]').forEach(button => button.addEventListener('click', () => {
    if (button.dataset.download === 'compose') downloadFile('compose.yaml', dockerCompose, 'text/yaml');
    if (button.dataset.download === 'notes') downloadFile('CryptoBench-本地部署说明.txt', deployNotes, 'text/plain;charset=utf-8');
    if (button.dataset.download === 'config') downloadFile('local-config.json', JSON.stringify(localConfig, null, 2));
    ctx.toast('文件已生成，请查看浏览器下载记录');
  }));
  root.querySelectorAll('[data-download-dataset]').forEach(button => button.addEventListener('click', () => {
    const dataset = catalogDatasets.find(d => d.id + '@' + d.version === button.dataset.downloadDataset);
    if (dataset) { downloadFile(`${dataset.id}.example.json`, JSON.stringify(sampleTask(dataset), null, 2)); ctx.toast('演示任务样例已生成'); }
  }));
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); ctx.toast('已复制到剪贴板'); }
    catch { ctx.toast('浏览器未授予剪贴板权限，请手动选择并复制代码'); }
  }
  root.querySelectorAll('[data-copy]').forEach(button => button.addEventListener('click', () => copy(button.dataset.copy)));
  root.querySelectorAll('[data-copy-example]').forEach(button => button.addEventListener('click', () => copy(button.closest('.resource-code-head').nextElementSibling.textContent)));
}


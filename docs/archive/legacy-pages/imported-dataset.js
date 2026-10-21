import { escapeHTML, icon, datasetTitle } from '../core.js';

const e = value => escapeHTML(String(value ?? ''));
const labels = { easy_medium: '入门至中等', medium: '中等', medium_hard: '中等至困难', hard: '困难', very_hard: '高难度', very_hard_compute: '计算压力' };
const ui = { tab: 'overview', query: '', cipher: 'all', difficulty: 'all', family: 'all', stress: 'all', page: 1 };
let catalog, loading, error = '', lastRoute = '', scrolledTask = '';
const pageSize = 20;
const badge = text => `<span class="badge neutral">${e(text)}</span>`;
const href = (d, task) => `#/datasets/${d.id}?version=${encodeURIComponent(d.version)}${task ? '&task=' + encodeURIComponent(task) : ''}`;
const fileLink = file => `<a class="btn small secondary" href="${e(file.url)}" download>${icon('download', 15)} ${e(file.name)}</a>`;
const option = (value, label, selected) => `<option value="${e(value)}" ${value === selected ? 'selected' : ''}>${e(label)}</option>`;
function family(task) {
  const type = task.task_family;
  if (/lower_bound|active_sbox_bound/.test(type)) return '下界与活跃 S 盒';
  if (/boomerang/.test(type)) return 'Boomerang 分析';
  if (/impossible/.test(type)) return '不可能差分';
  if (/related_key|key_schedule|key_recovery|key_space/.test(type)) return '密钥与相关密钥';
  if (/differential_linear/.test(type)) return '差分线性分析';
  if (/linear/.test(type)) return '线性分析';
  if (/integral|division_property|invariant/.test(type)) return '积分与代数分析';
  if (/differential|trail/.test(type)) return '差分与路径分析';
  return '建模与复杂度复核';
}
// Render escaped Markdown blocks; raw HTML and executable links are never interpreted.
function questionHTML(text) {
  let code = false;
  return text.split(/\r?\n/).map(line => {
    if (/^```/.test(line)) { code = !code; return code ? '<pre class="resource-code"><code>' : '</code></pre>'; }
    if (code) return e(line) + '\n';
    if (/^#{1,6} /.test(line)) return `<h3>${e(line.replace(/^#+ /, ''))}</h3>`;
    return line.trim() ? `<div class="question-line">${e(line)}</div>` : '<div class="question-gap"></div>';
  }).join('') + (code ? '</code></pre>' : '');
}
function downloads(d) {
  return `<div class="row suite-downloads"><a class="btn secondary" href="${e(d.downloadUrl)}" download>${icon('download', 17)} 下载公开任务包 ZIP</a><a class="btn ghost" href="${e(d.catalogUrl)}" download>下载题目索引 JSON</a></div>`;
}
function overview(d) {
  return `<div class="detail-content-grid"><section class="card detail-section"><span class="section-kicker">PUBLIC BENCHMARK RELEASE</span><h2>127 道可复核的密码分析任务</h2><p>${e(d.description)}</p><div class="suite-stats"><div><strong>105</strong><span>精选旧题</span></div><div><strong>22</strong><span>论文衍生新题</span></div><div><strong>2</strong><span>计算压力题 · 单列统计</span></div></div><button class="btn secondary" data-suite-tab="tasks">浏览全部题目 ${icon('arrow', 16)}</button>${downloads(d)}<div class="detail-callout">${icon('info', 18)}<span>题库可浏览与下载；真实执行和自动评分待接入。</span></div></section><aside class="card detail-section detail-at-glance"><h3>测评集概览</h3><dl><div><dt>数据状态</dt><dd>${badge('已导入真实题库')}</dd></div><div><dt>密码方向</dt><dd>分组密码分析</dd></div><div><dt>题目数量</dt><dd>${d.tasks} 道</dd></div><div><dt>网站版本</dt><dd>${e(d.version)}</dd></div><div><dt>源发布日期</dt><dd>${e(d.releaseDate)}</dd></div><div><dt>执行状态</dt><dd>待接入执行器</dd></div></dl></aside></div>`;
}
function taskList(d, route) {
  const tasks = catalog.tasks;
  const filtered = tasks.filter(t => (!ui.query || [t.task_id, t.title, t.cipher, t.task_family, family(t)].join(' ').toLowerCase().includes(ui.query.toLowerCase())) && (ui.cipher === 'all' || t.cipher === ui.cipher) && (ui.difficulty === 'all' || t.difficulty === ui.difficulty) && (ui.family === 'all' || family(t) === ui.family) && (ui.stress === 'all' || t.is_aggregate_stress === (ui.stress === 'only')));
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
  ui.page = Math.min(ui.page, pages);
  const selected = new URLSearchParams(route.split('?')[1]).get('task');
  const task = tasks.find(t => t.task_id === selected);
  const select = (key, label, values) => `<div class="catalog-filter"><label for="suite-${key}">${label}</label><select id="suite-${key}" data-suite-filter="${key}">${option('all', '全部', ui[key])}${values.map(v => option(v, key === 'difficulty' ? labels[v] || v : v, ui[key])).join('')}</select></div>`;
  return `<section class="card detail-section"><h2>题目目录</h2><div class="suite-filters"><div class="resource-search">${icon('search', 18)}<input id="suite-query" aria-label="搜索题目" type="search" placeholder="搜索题号、标题或算法…" value="${e(ui.query)}"></div>${select('cipher', '密码算法', [...new Set(tasks.map(t => t.cipher))].sort())}${select('difficulty', '题目难度', [...new Set(tasks.map(t => t.difficulty))])}${select('family', '分析方向', [...new Set(tasks.map(family))])}<div class="catalog-filter"><label for="suite-stress">计算压力题</label><select id="suite-stress" data-suite-filter="stress">${option('all', '全部题目', ui.stress)}${option('exclude', '排除压力题', ui.stress)}${option('only', '仅压力题', ui.stress)}</select></div></div><div class="row between suite-list-meta"><span role="status">共 <strong>${filtered.length}</strong> / ${tasks.length} 道题</span><button class="btn small secondary" data-suite-reset>清除筛选</button></div><div class="table-scroll"><table class="resource-table suite-task-table"><thead><tr><th>编号</th><th>题目</th><th>算法</th><th>难度</th><th>方向</th></tr></thead><tbody>${filtered.slice((ui.page - 1) * pageSize, ui.page * pageSize).map(t => `<tr><td>${String(t.question_number).padStart(3, '0')}</td><td><a class="text-link" href="${href(d, t.task_id)}">${e(t.title)}</a>${t.is_aggregate_stress ? '<div>' + badge('aggregate_stress · 计算压力') + '</div>' : ''}<small class="suite-task-id">${e(t.task_id)}</small></td><td>${e(t.cipher)}</td><td>${e(labels[t.difficulty] || t.difficulty)}</td><td>${e(family(t))}</td></tr>`).join('')}</tbody></table></div>${filtered.length ? '' : '<p class="empty">没有匹配的题目，请调整筛选条件。</p>'}<div class="row between suite-pagination"><button class="btn small secondary" data-suite-page="${ui.page - 1}" ${ui.page === 1 ? 'disabled' : ''}>上一页</button><span>第 ${ui.page} / ${pages} 页</span><button class="btn small secondary" data-suite-page="${ui.page + 1}" ${ui.page === pages ? 'disabled' : ''}>下一页</button></div></section>${selected ? task ? `<section class="card detail-section suite-question" id="suite-question"><div class="row between"><span class="section-kicker">QUESTION ${task.question_number}</span>${badge(labels[task.difficulty] || task.difficulty)}</div><h2>${e(task.title)}</h2><p class="suite-task-id">${e(task.task_id)}</p><div class="tags">${badge(task.cipher)}${badge(family(task))}${task.is_aggregate_stress ? badge('计算压力题 · 单列统计') : ''}</div><article class="task-question">${questionHTML(task.question)}</article><h3>题干与公开附件</h3><div class="suite-files">${task.files.map(fileLink).join('')}</div></section>` : '<section class="card detail-section"><h2>题目不存在</h2><p>请从上方目录选择题目。</p></section>' : '<div class="detail-callout">' + icon('info', 18) + '<span>点击题目标题查看完整题干并下载附件。</span></div>'}`;
}
function scoring() {
  return `<section class="card detail-section"><span class="section-kicker">SCORING POLICY</span><h2>评分规则</h2><p>沿用源题库的两种评分模式。每次实验需要记录评分模式、任务范围、预算方案与执行器版本。</p><div class="table-scroll"><table class="resource-table"><thead><tr><th>模式</th><th>最终结果</th><th>过程证据</th></tr></thead><tbody><tr><td>final_only</td><td>100%</td><td>不计入</td></tr><tr><td>process_plus_final</td><td>65%</td><td>35%</td></tr></tbody></table></div><p class="scoring-formula">综合得分 = 最终结果得分 × 0.65 + 过程证据得分 × 0.35</p><p>过程证据来自日志、命令、文件与验证输出。通过阈值为 0.99（得分范围为 0–1）；通过率和平均得分分别展示，最终结果通过率与综合得分通过率应明确区分。</p><h3>统计与异常处理</h3><p>两道 aggregate_stress 计算压力题单列统计。超时、环境错误、连接失败或执行围栏终止需单独报告。历史分数仅供参考，不能作为当前版本的正式榜单成绩。</p><div class="detail-callout">${icon('info', 18)}<span>本网站尚未接入这套题库的执行器与评分器，因此不会为它生成模拟成绩。</span></div></section>`;
}
function version(d) {
  return `<section class="card detail-section"><span class="section-kicker">SOURCE & VERSION</span><h2>版本与来源</h2><dl class="info-list"><div><dt>网站数据版本</dt><dd>${e(d.version)}</dd></div><div><dt>原始题库标识</dt><dd class="suite-wrap">${e(d.suiteId)}</dd></div><div><dt>源发布日期</dt><dd>${e(d.releaseDate)}</dd></div><div><dt>源提交</dt><dd class="suite-wrap">${e(d.sourceCommit)}</dd></div></dl><p><a class="text-link" href="${e(d.sourceUrl)}" target="_blank" rel="noopener noreferrer">查看源仓库 ↗</a></p><p>本次导入保留原始任务 ID、题号及公开内容。网站版本用于固定本次导入，源发布身份由原始题库标识和提交记录共同确定。</p><p>历史运行中部分题目后来修订过，历史分数未导入网站榜单。公开任务包包含题目索引、题干和附件；隐藏答案、参考复放与评分材料由测评后端管理。</p>${downloads(d)}</section>`;
}
export function renderImportedDataset(d, route) {
  if (route !== lastRoute || !document.querySelector('[data-imported-suite]')) {
    if (new URLSearchParams(route.split('?')[1]).has('task')) ui.tab = 'tasks';
    scrolledTask = '';
    lastRoute = route;
  }
  const tabs = [['overview', '概览'], ['tasks', '题目与附件'], ['scoring', '评分规则'], ['version', '版本与来源']];
  let content = ui.tab === 'overview' ? overview(d) : ui.tab === 'scoring' ? scoring() : ui.tab === 'version' ? version(d) : catalog ? taskList(d, route) : `<section class="card detail-section"><h2>题目目录</h2><p role="status">${error ? e(error) : '正在加载公开题目…'}</p>${error ? '<button class="btn secondary" data-suite-retry>重试</button>' : ''}</section>`;
  return `<div class="container resources-page" data-imported-suite="${d.id}"><a class="resource-back" href="#/datasets">${icon('arrow', 16)} 返回测评集</a><header class="dataset-detail-heading"><div><div class="dataset-meta">${badge('真实题库')}${badge('分组密码')}${badge('执行待接入')}</div><h1>${e(datasetTitle(d))}</h1><div class="detail-title-meta"><span>${d.tasks} 道任务</span><span>v${e(d.version)}</span></div></div><div class="dataset-detail-actions"><a class="btn primary" href="${e(d.downloadUrl)}" download>${icon('download', 17)} 下载公开任务包</a></div></header><nav class="tabs resource-tabs" aria-label="测评集详情">${tabs.map(([id, label]) => `<button data-suite-tab="${id}" class="${ui.tab === id ? 'active' : ''}" aria-current="${ui.tab === id ? 'page' : 'false'}">${label}</button>`).join('')}</nav>${content}</div>`;
}
export function bindImportedDataset(ctx, d) {
  const root = document.querySelector('[data-imported-suite]');
  if (!root) return;
  const refresh = () => ctx.render();
  const load = () => {
    if (catalog || loading || error) return;
    loading = fetch(d.catalogUrl).then(response => {
      if (!response.ok) throw Error('题目加载失败，请重试。');
      return response.json();
    }).then(data => {
      if (data.dataset?.id !== d.id || data.tasks?.length !== d.tasks) throw Error('题目索引与版本不匹配。');
      catalog = data;
    }).catch(err => { error = err.message; }).finally(() => {
      loading = null;
      if (document.querySelector('[data-imported-suite]')) refresh();
    });
  };
  load();
  root.querySelectorAll('[data-suite-tab]').forEach(button => button.onclick = () => { ui.tab = button.dataset.suiteTab; refresh(); });
  root.querySelectorAll('[data-suite-filter]').forEach(select => select.onchange = () => { ui[select.dataset.suiteFilter] = select.value; ui.page = 1; refresh(); });
  root.querySelector('#suite-query')?.addEventListener('input', event => {
    ui.query = event.target.value; ui.page = 1;
    const cursor = event.target.selectionStart;
    refresh();
    const input = document.querySelector('#suite-query');
    input?.focus();
    try { input?.setSelectionRange(cursor, cursor); } catch {}
  });
  root.querySelector('[data-suite-reset]')?.addEventListener('click', () => { Object.assign(ui, { query: '', cipher: 'all', difficulty: 'all', family: 'all', stress: 'all', page: 1 }); refresh(); });
  root.querySelectorAll('[data-suite-page]').forEach(button => button.onclick = () => { ui.page = Number(button.dataset.suitePage); refresh(); });
  root.querySelector('[data-suite-retry]')?.addEventListener('click', () => { error = ''; refresh(); });
  const selected = new URLSearchParams(location.hash.split('?')[1]).get('task');
  if (!selected) scrolledTask = '';
  if (selected && selected !== scrolledTask && ui.tab === 'tasks' && root.querySelector('#suite-question')) {
    scrolledTask = selected;
    requestAnimationFrame(() => {
      if (root.isConnected) root.querySelector('#suite-question')?.scrollIntoView({ block: 'start' });
    });
  }
}

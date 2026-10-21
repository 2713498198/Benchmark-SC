import {icon,escapeHTML as e,pageHeading,datasetTitle,downloadFile,csv} from '../core.js';
import {datasets,rankings} from '../data.js';
import {renderRankChart,bindRankChart} from '../leaderboard-chart.js';
import {leaderboardModel,leaderboardCsvRows,validNumber,displayScore} from '../leaderboard-model.js';

const defaults={dataset:'mini-spn',version:'1.0.0',budget:'standard',source:'all',type:'demo',sort:'score'};
const filters={...defaults};
let state,lastRoute='',view='ranking',query='',metric='cost',zoom=false,filterOpen=false,cleanup=()=>{};
const selected=new Set(),expanded=new Set();
const opt=(v,t,s)=>`<option value="${e(v)}" ${v===s?'selected':''}>${e(t)}</option>`;
const amount=value=>validNumber(value)?value.toLocaleString('en-US',{maximumFractionDigits:3}):'—';
const score=r=>displayScore(r)===null?'—':displayScore(r).toFixed(1)+'%';
const source=r=>r.source==='local'?'本地':r.source==='cloud'?'云端':'未提供';
const status=r=>r.demo===true?'示例':'未核验';
const provided=value=>value===null||value===undefined||value===''?'未提供':typeof value==='object'?JSON.stringify(value):String(value);
const budget=key=>key==='extended'?'扩展 · 600s / 40 次调用':'标准 · 300s / 20 次调用';
const allData=()=>[...datasets,...(state.customDatasets||[]).filter(d=>d.published)];
const model=()=>leaderboardModel(rankings,state.publicReports||[],state.reports||[],filters,query);
const domId=id=>'rank-record-'+encodeURIComponent(id);
const detailId=id=>'rank-evidence-'+encodeURIComponent(id);
const control=(label,key,options)=>`<label class="rank-filter"><span>${label}</span><select data-rank="${key}" aria-label="${key==='dataset'?'榜单测评集':key==='version'?'榜单版本':key==='budget'?'榜单预算':label}">${options}</select></label>`;
const info=(name,value)=>`<div><dt>${name}</dt><dd>${e(provided(value))}</dd></div>`;

function evidence(entry,ds){
 const r=entry.record,agentVersion=r.agentVersion,evaluator=r.evaluatorVersion??r.evaluator_version,seed=r.seed??r.config?.seed;
 return `<tr class="leaderboard-details" id="${e(detailId(r.id))}"><td colspan="9"><section class="rank-evidence" aria-label="${e(r.agent)} 的成绩依据">
  <div class="rank-evidence-heading"><div><h3>成绩依据</h3><p>${entry.builtin?'内置样例记录，用于展示比较方式；未执行真实测评。':r.demo===true?'用户公开的模拟测评记录；示例成绩不代表真实模型能力。':'用户公开提交，当前平台未核验执行过程与验证证据。'}</p></div><button class="btn small" data-download-rank="${e(r.id)}">${icon('download',14)} ${entry.builtin?'下载样例 JSON':'下载公开记录 JSON'}</button></div>
  <div class="rank-evidence-grid"><dl class="info-list">${info('Agent',r.agent)}${info('基础模型',r.model)}${info('记录来源',entry.builtin?'平台内置样例':'用户公开提交')}${info('数据性质',status(r))}${info('记录 ID',r.id)}${info('提交时间',r.createdAt)}${info('执行来源',source(r))}</dl>
  <dl class="info-list">${info('测评集 / 版本',datasetTitle(ds)+' · '+r.version)}${info('预算 / 范围',budget(r.budget)+' · 完整任务')}${info('Agent 版本',agentVersion)}${info('验证器版本',evaluator)}${info('随机种子',seed)}</dl></div>
  <div class="rank-mobile-metrics">${info('通过 / 总数',amount(r.passed)+' / '+amount(r.total))}${info('平均耗时',amount(r.time)+(validNumber(r.time)?' s':''))}${info('费用 USD',amount(r.cost))}</div>
  <details class="rank-evidence-data"><summary>查看已有配置与结果明细</summary><p class="inline-note">仅展示记录中实际提供的内容；未提供的字段不代表已完成验证。</p><pre class="code-block">${e(JSON.stringify({agent:r.agent,model:r.model,agent_version:agentVersion??null,evaluator_version:evaluator??null,seed:seed??null,tools:r.tools??null,config:r.config??null,results:r.results??null,categories:r.categories??null,evidence:r.evidence??null},null,2))}</pre></details>
 </section></td></tr>`;
}

function table(data,ds){
 const sorted=(key,title)=>`<th scope="col" ${filters.sort===key?'aria-sort="'+(key==='score'?'descending':'ascending')+'"':''}>${title}${filters.sort===key?(key==='score'?' ↓':' ↑'):''}</th>`;
 return `<div class="card table-scroll rank-table-wrap" tabindex="0" role="region" aria-label="测评榜单"><table class="leaderboard-table"><colgroup>${Array.from({length:9},()=>'<col>').join('')}</colgroup><thead><tr><th scope="col">对比</th><th scope="col">名次</th><th scope="col">Agent / 模型</th>${sorted('score','通过率')}<th scope="col">通过 / 总数</th>${sorted('time','平均耗时')}${sorted('cost','费用 USD')}<th scope="col">来源</th><th scope="col">成绩依据</th></tr></thead><tbody>${data.visible.map(entry=>{
  const {record:r,place}=entry,open=expanded.has(r.id);
  return `<tr id="${e(domId(r.id))}" data-rank-record="${e(r.id)}" tabindex="-1"><td><label class="rank-compare-check"><input class="checkbox" type="checkbox" aria-label="对比 ${e(r.agent)}" data-compare="${e(r.id)}" ${selected.has(r.id)?'checked':''}></label></td><td class="rank-number ${place&&place<=3?'top':''}" aria-label="${place?'通过率第 '+place+' 名':'无有效通过率'}">${place?String(place).padStart(2,'0'):'—'}</td><td><div class="rank-identity"><button class="rank-name" data-expand="${e(r.id)}" aria-expanded="${open}" aria-controls="${e(detailId(r.id))}">${e(r.agent||'未命名 Agent')}</button><small>${e(r.model||'模型未提供')}</small></div></td><td class="score">${score(r)}</td><td>${amount(r.passed)} <span class="muted">/ ${amount(r.total)}</span></td><td>${amount(r.time)}${validNumber(r.time)?' s':''}</td><td>${validNumber(r.cost)?r.cost.toFixed(2):'—'}</td><td><span class="rank-origin">${source(r)}</span></td><td><button class="btn small ghost rank-evidence-toggle" data-expand="${e(r.id)}" aria-expanded="${open}" aria-controls="${e(detailId(r.id))}">${open?'收起':'查看依据'}</button></td></tr>${open?evidence(entry,ds):''}`;
 }).join('')}</tbody></table>${data.visible.length?'':`<div class="empty"><h3>${query?'没有匹配的 Agent 或模型':filters.type==='public'?'暂无公开记录':'当前条件下暂无成绩'}</h3><p>${query?'换个关键词，或清除搜索查看全部记录。':filters.type==='public'?'公开记录将标注为未核验；可切换到示例榜单查看比较方式。':'调整测评集与预算，或完成并公开一次相同条件的模拟测评。'}</p><button class="btn" ${query?'data-clear-rank-search':filters.type==='public'?'data-show-demo':'data-reset-rank'}>${query?'清除搜索':filters.type==='public'?'查看示例榜单':'重置筛选'}</button></div>`}</div>`;
}

export function render(route,s){
 state=s;
 if(lastRoute!==route){
  const params=new URLSearchParams(route.split('?')[1]),id=params.get('dataset');
  if(id&&allData().some(d=>d.id===id)){filters.dataset=id;filters.version=params.get('version')||allData().filter(d=>d.id===id).at(-1).version;}
  lastRoute=route;selected.clear();expanded.clear();query='';view='ranking';
 }
 const ds=allData().find(d=>d.id===filters.dataset&&d.version===filters.version)||datasets[0],data=model();
 const leader=data.leaders[0]?.record,leadLabel=data.leaders.length>1?`${data.leaders.length} 项并列最高`:leader?`${leader.agent} · ${leader.model}`:'等待测评记录';
 return `<div class="container page leaderboard-page">${pageHeading('LEADERBOARD','测评榜单','在相同任务、版本与预算下，比较每一次测评。',`<a class="btn rank-method-link" href="#/docs?tab=methodology">${icon('book',16)} 测评方法</a><a class="btn primary" href="#/evaluate?new=1">开始测评 ${icon('arrow',16)}</a>`)}
  <div class="rank-context"><div class="rank-context-main"><strong>${e(datasetTitle(ds))}</strong><span>v${e(filters.version)} · ${filters.budget==='extended'?'扩展预算':'标准预算'} · ${filters.source==='all'?'全部来源':filters.source==='local'?'本地':'云端'}</span></div><label class="rank-type"><span class="sr-only">成绩类型</span><select data-rank="type" aria-label="成绩类型">${opt('demo','示例榜单',filters.type)}${opt('public','公开记录 · 未核验',filters.type)}</select></label></div>
  <details class="rank-filter-disclosure" ${filterOpen?'open':''}><summary><span>测评条件</span><span class="rank-filter-hint">调整筛选 ${icon('chevron',14)}</span></summary><div class="rank-filters">
   ${control('测评集','dataset',[...new Map(allData().map(d=>[d.id,d])).values()].map(d=>opt(d.id,datasetTitle(d),filters.dataset)).join(''))}
   ${control('版本','version',allData().filter(d=>d.id===filters.dataset).map(d=>opt(d.version,d.version,filters.version)).join(''))}
   ${control('执行来源','source',opt('all','全部来源',filters.source)+opt('cloud','云端',filters.source)+opt('local','本地',filters.source))}
   ${control('预算方案','budget',opt('standard',budget('standard'),filters.budget)+opt('extended',budget('extended'),filters.budget))}</div></details>
  <div class="rank-summary" aria-label="当前测评条件概览"><div class="rank-summary-leader"><span>最高通过率</span><strong>${leader?score(leader):'—'}</strong><small title="${e(leadLabel)}">${e(leadLabel)}</small></div><div><span>参评记录</span><strong>${data.cohort.length}<small> 项</small></strong><small>完整测评</small></div><div><span>费用覆盖</span><strong>${data.cohort.length?Math.round(data.costCount/data.cohort.length*100)+'%':'—'}</strong><small>${data.costCount} / ${data.cohort.length} 项提供</small></div></div>
  <div class="demo-banner rank-trust-note">${icon('info',14)}<span>${filters.type==='demo'?'示例数据不代表真实模型测评成绩。':'公开仅表示记录可见，执行与证据尚未核验。'} 每行是一条配置记录。</span></div>
  <div class="rank-viewbar"><div class="rank-tabs" role="tablist" aria-label="榜单视图">${[['ranking','排行榜'],['analysis','性能分析']].map(([id,label])=>`<button type="button" id="rank-tab-${id}" role="tab" aria-controls="rank-panel-${id}" aria-selected="${view===id}" tabindex="${view===id?0:-1}" data-rank-view="${id}">${label}</button>`).join('')}</div><a href="#/docs?tab=report" class="text-link rank-rules-link">评分与复现 ${icon('arrow',14)}</a></div>
  <div class="rank-toolbar"><div class="rank-search">${icon('search',16)}<input id="rank-search" type="search" aria-label="搜索 Agent 或模型" placeholder="搜索 Agent 或模型" value="${e(query)}"></div><span class="rank-result-count" role="status" aria-live="polite">${data.visible.length} / ${data.cohort.length} 项</span><select class="input" data-rank="sort" aria-label="榜单排序">${opt('score','通过率优先',filters.sort)}${opt('time','耗时从低到高',filters.sort)}${opt('cost','费用从低到高',filters.sort)}</select><button class="btn small" data-export-rank>${icon('download',15)} CSV</button></div>
  <section id="rank-panel-${view}" role="tabpanel" aria-labelledby="rank-tab-${view}" tabindex="0">${view==='ranking'?table(data,ds):renderRankChart(data.visible.map(entry=>entry.record),metric,{zoom})+(data.visible.length?'':`<div class="rank-empty-actions"><button class="btn" ${query?'data-clear-rank-search':filters.type==='public'?'data-show-demo':'data-reset-rank'}>${query?'清除搜索':filters.type==='public'?'查看示例榜单':'重置筛选'}</button></div>`)}</section>
  <p class="rank-ranking-note">${view==='ranking'?'名次按当前条件下的通过率计算，同分并列；搜索及费用、耗时排序不改变名次。不同 Agent 与模型配置可能影响结果；点击名称查看成绩依据。':'前沿基于当前显示的记录，表示通过率与资源消耗的取舍。'}${query?' 概览统计全部条件内记录。':''}</p>
  ${selected.size?`<div class="compare-bar"><span>已选择 ${selected.size} 项 · 可比较 2–4 项</span><div class="row"><button class="btn small" data-clear-compare>清空</button><button class="btn small" data-open-compare ${selected.size<2?'disabled':''}>查看对比 ${icon('arrow',14)}</button></div></div>`:''}
 </div>`;
}

export function dispose(){cleanup();cleanup=()=>{};}
export function bind(ctx){
 const root=document.querySelector('.leaderboard-page');if(!root)return;
 const $=selector=>root.querySelector(selector),all=(selector,fn)=>root.querySelectorAll(selector).forEach(fn);
 const refresh=focus=>{ctx.render();document.querySelector(focus)?.focus({preventScroll:true});};
 const setView=next=>{view=next;refresh('#rank-tab-'+view);};
 const focusRecord=id=>{
  view='ranking';expanded.add(id);ctx.render();
  const row=document.getElementById(domId(id));row?.focus({preventScroll:true});row?.scrollIntoView({block:'center',behavior:'instant'});
 };
 const cleanChart=bindRankChart($('[data-rank-chart]'),model().visible.map(entry=>entry.record),metric,next=>{metric=next;refresh('[data-chart-metric="'+metric+'"]');},{zoom,onZoom:next=>{zoom=next;refresh('[data-chart-zoom]');},onLocate:focusRecord});
 const compact=matchMedia('(max-width:640px)');
 const resizeDetails=()=>all('.leaderboard-details>td',cell=>{cell.colSpan=compact.matches?4:9;});
 resizeDetails();compact.addEventListener('change',resizeDetails);
 cleanup=()=>{cleanChart();compact.removeEventListener('change',resizeDetails);};
 const disclosure=$('.rank-filter-disclosure');disclosure.ontoggle=()=>{filterOpen=disclosure.open;};
 all('[data-rank]',el=>el.onchange=()=>{
  const key=el.dataset.rank;filters[key]=el.value;
  if(key!=='sort'){selected.clear();expanded.clear();}
  if(key==='dataset')filters.version=allData().filter(d=>d.id===el.value).at(-1).version;
  refresh('[data-rank="'+key+'"]');
 });
 const search=$('#rank-search');search.oninput=event=>{
  if(event.isComposing)return;
  query=search.value;const position=search.selectionStart;refresh('#rank-search');
  document.querySelector('#rank-search')?.setSelectionRange?.(position,position);
 };
 search.addEventListener('compositionend',()=>search.oninput({isComposing:false}));
 all('[data-rank-view]',button=>{
  button.onclick=()=>setView(button.dataset.rankView);
  button.onkeydown=event=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(event.key)){event.preventDefault();setView(event.key==='Home'?'ranking':event.key==='End'?'analysis':view==='ranking'?'analysis':'ranking');}};
 });
 all('[data-expand]',button=>button.onclick=()=>{const id=button.dataset.expand;expanded.has(id)?expanded.delete(id):expanded.add(id);refresh('#'+CSS.escape(domId(id))+' .rank-name');});
 all('[data-download-rank]',button=>button.onclick=()=>{
  const entry=model().cohort.find(entry=>entry.record.id===button.dataset.downloadRank);if(!entry)return;
  downloadFile(entry.record.id+'.json',JSON.stringify(entry.record,null,2));
 });
 all('[data-compare]',button=>button.onchange=()=>{
  if(button.checked&&selected.size>=4){button.checked=false;ctx.toast('最多选择 4 项');return;}
  button.checked?selected.add(button.dataset.compare):selected.delete(button.dataset.compare);
  refresh('#'+CSS.escape(domId(button.dataset.compare))+' [data-compare]');
 });
 $('[data-clear-compare]')?.addEventListener('click',()=>{selected.clear();refresh('#rank-tab-'+view);});
 $('[data-clear-rank-search]')?.addEventListener('click',()=>{query='';refresh('#rank-search');});
 $('[data-show-demo]')?.addEventListener('click',()=>{filters.type='demo';selected.clear();refresh('[data-rank="type"]');});
 $('[data-reset-rank]')?.addEventListener('click',()=>{Object.assign(filters,defaults);selected.clear();expanded.clear();query='';refresh('#rank-tab-'+view);});
 $('[data-export-rank]')?.addEventListener('click',()=>downloadFile('cryptobench-leaderboard.csv',csv(leaderboardCsvRows(model().visible)),'text/csv'));
 $('[data-open-compare]')?.addEventListener('click',()=>{
  const entries=model().cohort.filter(entry=>selected.has(entry.record.id));
  const fields=[['通过率',r=>score(r)],['通过 / 总数',r=>amount(r.passed)+' / '+amount(r.total)],['基础模型',r=>provided(r.model)],['平均耗时',r=>amount(r.time)+(validNumber(r.time)?' s':'')],['费用 USD',r=>amount(r.cost)],['执行来源',source],['成绩性质',status],['Agent 版本',r=>provided(r.agentVersion)],['验证器版本',r=>provided(r.evaluatorVersion??r.evaluator_version)],['随机种子',r=>provided(r.seed??r.config?.seed)]];
  const dialog=ctx.modal('同条件实验对比',`<p class="inline-note">同一测评集、版本、预算与成绩类型；不同配置可能影响结果。</p><div class="table-scroll rank-comparison" tabindex="0" role="region" aria-label="测评记录对比矩阵"><table><thead><tr><th scope="col">比较指标</th>${entries.map(({record:r})=>`<th scope="col">${e(r.agent)}</th>`).join('')}</tr></thead><tbody>${fields.map(([label,get])=>`<tr><th scope="row">${label}</th>${entries.map(({record:r})=>`<td>${e(get(r))}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`);
  dialog.classList.add('rank-compare-dialog');dialog.addEventListener('close',()=>dialog.classList.remove('rank-compare-dialog'),{once:true});
 });
}

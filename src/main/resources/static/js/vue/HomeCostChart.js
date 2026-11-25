import {ref,reactive,computed,watch,onMounted,onBeforeUnmount} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp,SvgIcon} from './shared.js';
import RankChart from './RankChart.js';
import {datasetTitle} from '../core.js';
import {leaderboardModel} from '../leaderboard-model.js';
import {chartModel} from '../leaderboard-chart.js';
import {executionSourceOptions} from '../execution-sources.js';
import {paperBenchmark,paperLeaderboardRows} from '../paper-leaderboard.js';

export default {components:{SvgIcon,RankChart},setup(){
 const app=useApp(),filters=reactive({dataset:'cryptobench-sc-v4',version:'4.2.0',budget:'official',source:'all',sort:'score'});
 const mode=ref('official'),zoom=ref(false),snapshot=ref(null),updated=ref(null),failed=ref(false),loading=ref(false);
 const all=computed(()=>app.getDatasets()),datasets=computed(()=>[...new Map(all.value.map(d=>[d.id,d])).values()]);
 const versions=computed(()=>all.value.filter(d=>d.id===filters.dataset));
 watch(()=>filters.dataset,()=>{filters.version=versions.value.at(-1)?.version||'';});
 const publicData=computed(()=>leaderboardModel([],snapshot.value??app.state.publicReports,[],{...filters,type:'public'}));
 const sourceOptions=computed(()=>{
  const types=mode.value==='auto'?['public','demo']:[mode.value];
  return executionSourceOptions(types.flatMap(type=>leaderboardModel(paperLeaderboardRows,snapshot.value??app.state.publicReports,[],{...filters,source:'all',type}).cohort.map(e=>e.record)));
 });
 watch(sourceOptions,options=>{if(!options.some(option=>option.value===filters.source))filters.source='all';});
 watch(mode,value=>{
  if(value==='official')filters.budget='official';
  else if(filters.budget==='official')filters.budget='standard';
 });
 const type=computed(()=>mode.value==='auto'?(publicData.value.cohort.length?'public':'demo'):mode.value);
 const paperMode=computed(()=>type.value==='official');
 const publicTrust=computed(()=>publicData.value.cohort.length?'正式榜单 · 平台核验':'正式榜单 · 暂无可入榜记录');
 const entries=computed(()=>leaderboardModel(paperLeaderboardRows,snapshot.value??app.state.publicReports,[],{...filters,type:type.value}));
 const rows=computed(()=>entries.value.visible.map(e=>e.record));
 const paperTop=computed(()=>entries.value.visible.filter(entry=>entry.record.officialBaseline===true||entry.record.paperBaseline===true).slice(0,6).map(entry=>({...entry.record,place:entry.place})));
 const paperLeader=computed(()=>paperTop.value[0]||null);
 const frontier=computed(()=>chartModel(rows.value,'cost').frontier.flatMap(p=>p.rows));
 const amount=value=>value!==0&&(value<.001||value>=1e6)?value.toExponential(1):value.toLocaleString('en-US',{maximumFractionDigits:3});
 const updateLabel=computed(()=>updated.value?new Date(updated.value).toLocaleTimeString('zh-CN',{hour12:false}):'尚未更新');
 const leaderboardUrl=computed(()=>'#/leaderboard?'+new URLSearchParams({dataset:filters.dataset,version:filters.version,budget:filters.budget,source:filters.source,type:type.value}));
 let timer,controller,disposed=false;
 function stop(){clearTimeout(timer);controller?.abort();controller=null;loading.value=false;}
 async function refresh(){
  if(disposed||document.hidden)return;
  stop();const request=new AbortController();controller=request;loading.value=true;
  const timeout=setTimeout(()=>request.abort(),15000);
  try{
   const reports=await app.api('/api/reports/public',{cache:'no-store',signal:request.signal});
   if(!Array.isArray(reports))throw Error('公开报告格式无效');
   if(disposed||controller!==request)return;
   // Preserve focused points and open details when the results have not changed.
   if(JSON.stringify(reports)!==JSON.stringify(snapshot.value))snapshot.value=reports;
   updated.value=Date.now();failed.value=false;
  }catch{
   if(!disposed&&controller===request)failed.value=true;
  }finally{
   clearTimeout(timeout);
   if(!disposed&&controller===request){controller=null;loading.value=false;if(!document.hidden)timer=setTimeout(refresh,30000);}
  }
 }
 function visibility(){if(document.hidden)stop();else refresh();}
 onMounted(()=>{document.addEventListener('visibilitychange',visibility);refresh();});
 onBeforeUnmount(()=>{disposed=true;stop();document.removeEventListener('visibilitychange',visibility);});
 return {filters,mode,zoom,datasets,versions,type,paperMode,paperTop,paperLeader,paperBenchmark,rows,frontier,amount,datasetTitle,updated,updateLabel,failed,loading,leaderboardUrl,sourceOptions,publicTrust};
},template:`<section class="container home-cost-section" aria-labelledby="home-cost-title" data-home-cost-chart>
 <header class="home-cost-heading"><div><h2 id="home-cost-title">测评榜单</h2><p>{{paperMode?'CryptoBench-SC V4 的 24 组模型与智能体组合正式结果。':'快速找到更适合你的模型与智能体组合。'}}</p></div><a class="text-link" :href="leaderboardUrl">{{paperMode?'查看全部 24 组':'完整榜单'}} <SvgIcon name="arrow" :size="16"/></a></header>
 <div class="home-cost-filters" role="group" aria-label="首页榜单测评条件">
  <label><span>测评集</span><select data-home-filter="dataset" v-model="filters.dataset"><option v-for="d in datasets" :key="d.id" :value="d.id">{{datasetTitle(d)}}</option></select></label>
  <label><span>版本</span><select data-home-filter="version" v-model="filters.version"><option v-for="d in versions" :key="d.version" :value="d.version">{{d.version}}</option></select></label>
  <label><span>执行来源</span><select data-home-filter="source" v-model="filters.source"><option v-for="option in sourceOptions" :key="option.value" :value="option.value">{{option.label}}</option></select></label>
  <label><span>预算方案</span><select data-home-filter="budget" v-model="filters.budget"><option v-if="paperMode" value="official">正式版本 · 3600s / 2M tokens</option><option v-else value="standard">标准 · 300s / 20 次调用</option><option v-if="!paperMode" value="extended">扩展 · 600s / 40 次调用</option></select></label>
  <label><span>成绩类型</span><select data-home-filter="type" v-model="mode"><option value="official">正式版本</option><option value="auto">自动 · 公开优先</option><option value="public">公开记录</option><option value="demo">示例数据</option></select></label>
 </div>
 <div class="home-cost-status"><div class="home-cost-trust"><span class="badge">{{paperMode?'正式版本':type==='demo'?'示例数据':publicTrust}}</span><p v-if="paperMode">170 道任务 · 24 组组合 · 每题 3600s / 2M tokens</p><p v-else-if="type==='demo'">{{mode==='auto'?'当前条件暂无可入榜记录':'仅供展示，不代表真实成绩'}}</p><details class="home-data-note"><summary>数据说明</summary><p>{{paperMode?'正式版本采用 CryptoBench-SC V4 的 170 道任务与统一实验条件。VCR 同时要求答案正确和证据通过验证。':type==='demo'?'示例数据不代表真实模型测评成绩。':'正式榜单只展示平台网关核验模型、供应商用量和费用的完整测评。'}}<template v-if="!paperMode">每条记录保留独立测评结果，费用与通过率缺失的记录不绘入图表。</template></p></details></div><p v-if="!paperMode" class="home-cost-update" role="status" aria-live="polite"><span v-if="failed">更新失败，已保留现有数据 · </span><span v-else-if="loading">更新中 · </span>{{updated?'最近更新 '+updateLabel:updateLabel}}<span> · 每 30 秒刷新</span></p></div>
 <div v-if="paperMode" class="home-paper-board" data-home-paper-board>
  <div class="home-paper-overview">
   <div class="home-paper-leader"><span>最高 VCR</span><strong>{{paperLeader?paperLeader.vcr.toFixed(1)+'%':'—'}}</strong><p>{{paperLeader?paperLeader.model+' / '+paperLeader.framework:'当前测评集暂无正式版本'}}</p><small v-if="paperLeader">{{paperLeader.passed}} / {{paperLeader.total}} 道任务完成验证</small></div>
   <div><span>指标均值</span><strong>{{paperBenchmark.metricMeans.vcr.toFixed(1)}}% VCR</strong><small>FullAcc {{paperBenchmark.metricMeans.fullAcc.toFixed(1)}} · VPR {{paperBenchmark.metricMeans.vpr.toFixed(1)}} · Process {{paperBenchmark.metricMeans.process.toFixed(1)}}</small></div>
   <div><span>实验规模</span><strong>{{paperBenchmark.taskCount}} 道任务</strong><small>{{paperBenchmark.combinationCount}} 组组合 · {{paperBenchmark.modelCount}} 模型 × {{paperBenchmark.frameworkCount}} 框架</small></div>
  </div>
  <div v-if="paperTop.length" class="home-paper-ranking">
   <div class="home-paper-rank-head" aria-hidden="true"><span>名次</span><span>模型 / 框架</span><span class="home-paper-metrics"><b>VCR</b><b>FullAcc</b><b>VPR</b><b>Process</b></span></div>
   <ol class="home-paper-rows"><li v-for="row in paperTop" :key="row.id" :data-home-paper-row="row.id"><span class="home-paper-place">{{String(row.place).padStart(2,'0')}}</span><div class="home-paper-identity"><strong>{{row.model}}</strong><small>{{row.framework}} · {{row.openWeights?'开放权重':'闭源权重'}} / {{row.openFramework?'开放框架':'闭源框架'}}</small></div><div class="home-paper-metrics"><div class="is-vcr"><span>VCR</span><strong>{{row.vcr.toFixed(1)}}%</strong></div><div><span>FullAcc</span><strong>{{row.fullAcc.toFixed(1)}}%</strong></div><div><span>VPR</span><strong>{{row.vpr.toFixed(1)}}%</strong></div><div><span>Process</span><strong>{{row.process.toFixed(1)}}</strong></div></div></li></ol>
  </div>
  <div v-else class="home-paper-empty"><h3>当前测评集暂无正式版本</h3><p>切换到 CryptoBench-SC V4.2.0，或进入完整榜单查看其他成绩类型。</p></div>
  <footer class="home-paper-footer"><div class="home-paper-categories"><span>任务构成</span><b>关系分析 {{paperBenchmark.categoryTotals[0]}}</b><b>搜索优化 {{paperBenchmark.categoryTotals[1]}}</b><b>精确计算 {{paperBenchmark.categoryTotals[2]}}</b><b>边界推导 {{paperBenchmark.categoryTotals[3]}}</b></div><a class="text-link" :href="leaderboardUrl">查看分类 VCR 与完整 24 组 <SvgIcon name="arrow" :size="15"/></a></footer>
 </div>
 <template v-else><RankChart :rows="rows" metric="cost" :zoom="zoom" fixed-metric hide-title @zoom="zoom=$event"/>
 <section v-if="frontier.length" class="home-frontier" aria-labelledby="home-frontier-title"><div class="home-frontier-heading"><h3 id="home-frontier-title">优选组合</h3><div class="home-frontier-values"><span>费用 USD</span><span>通过率</span></div></div><ul><li v-for="r in frontier" :key="r.id"><div><strong>{{r.model}}</strong><span>{{r.agent}}</span></div><div class="home-frontier-values"><span><small class="sr-only">费用 USD</small><b>&#36;{{amount(r.cost)}}</b></span><span><small class="sr-only">通过率</small><b>{{r.score.toFixed(1)}}%</b></span></div></li></ul></section></template>
 </section>`};

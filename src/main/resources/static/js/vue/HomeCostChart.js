import {ref,reactive,computed,watch,onMounted,onBeforeUnmount} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp,SvgIcon} from './shared.js';
import RankChart from './RankChart.js';
import {rankings} from '../data.js';
import {datasetTitle} from '../core.js';
import {leaderboardModel} from '../leaderboard-model.js';
import {chartModel} from '../leaderboard-chart.js';
import {executionSourceOptions} from '../execution-sources.js';

export default {components:{SvgIcon,RankChart},setup(){
 const app=useApp(),filters=reactive({dataset:'mini-spn',version:'1.0.0',budget:'standard',source:'all',sort:'score'});
 const mode=ref('auto'),zoom=ref(false),snapshot=ref(null),updated=ref(null),failed=ref(false),loading=ref(false);
 const all=computed(()=>app.getDatasets()),datasets=computed(()=>[...new Map(all.value.map(d=>[d.id,d])).values()]);
 const versions=computed(()=>all.value.filter(d=>d.id===filters.dataset));
 watch(()=>filters.dataset,()=>{filters.version=versions.value.at(-1)?.version||'';});
 const publicData=computed(()=>leaderboardModel([],snapshot.value??app.state.publicReports,[],{...filters,type:'public'}));
 const sourceOptions=computed(()=>{
  const types=mode.value==='auto'?['public','demo']:[mode.value];
  return executionSourceOptions(types.flatMap(type=>leaderboardModel(rankings,snapshot.value??app.state.publicReports,[],{...filters,source:'all',type}).cohort.map(e=>e.record)));
 });
 watch(sourceOptions,options=>{if(!options.some(option=>option.value===filters.source))filters.source='all';});
 const type=computed(()=>mode.value==='auto'?(publicData.value.cohort.length?'public':'demo'):mode.value);
 const publicTrust=computed(()=>publicData.value.cohort.some(({record})=>record.verified===true&&record.verificationStatus==='verified')?'公开记录 · 已验证':'公开记录 · 未核验');
 const rows=computed(()=>type.value==='public'?publicData.value.visible.map(e=>e.record):leaderboardModel(rankings,snapshot.value??app.state.publicReports,[],{...filters,type:'demo'}).visible.map(e=>e.record));
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
 return {filters,mode,zoom,datasets,versions,type,rows,frontier,amount,datasetTitle,updated,updateLabel,failed,loading,leaderboardUrl,sourceOptions,publicTrust};
},template:`<section class="container home-cost-section" aria-labelledby="home-cost-title" data-home-cost-chart>
 <header class="home-cost-heading"><div><h2 id="home-cost-title">测评榜单</h2><p>快速找到更适合你的模型与智能体组合。</p></div><a class="text-link" :href="leaderboardUrl">完整榜单 <SvgIcon name="arrow" :size="16"/></a></header>
 <div class="home-cost-filters" role="group" aria-label="成本效果图测评条件">
  <label><span>测评集</span><select data-home-filter="dataset" v-model="filters.dataset"><option v-for="d in datasets" :key="d.id" :value="d.id">{{datasetTitle(d)}}</option></select></label>
  <label><span>版本</span><select data-home-filter="version" v-model="filters.version"><option v-for="d in versions" :key="d.version" :value="d.version">{{d.version}}</option></select></label>
  <label><span>执行来源</span><select data-home-filter="source" v-model="filters.source"><option v-for="option in sourceOptions" :key="option.value" :value="option.value">{{option.label}}</option></select></label>
  <label><span>预算方案</span><select data-home-filter="budget" v-model="filters.budget"><option value="standard">标准 · 300s / 20 次调用</option><option value="extended">扩展 · 600s / 40 次调用</option></select></label>
  <label><span>成绩类型</span><select data-home-filter="type" v-model="mode"><option value="auto">自动 · 公开优先</option><option value="public">公开记录</option><option value="demo">示例数据</option></select></label>
 </div>
 <div class="home-cost-status"><div class="home-cost-trust"><span class="badge">{{type==='demo'?'示例数据':publicTrust}}</span><p v-if="type==='demo'">{{mode==='auto'?'当前条件暂无公开记录':'仅供展示，不代表真实成绩'}}</p><details class="home-data-note"><summary>说明</summary><p>{{type==='demo'?'示例数据不代表真实模型测评成绩。':'公开记录仅表示记录可见，验证状态以每条成绩依据为准。'}}每条记录保留独立测评结果，费用与通过率缺失的记录不绘入图表。</p></details></div><p class="home-cost-update" role="status" aria-live="polite"><span v-if="failed">更新失败，已保留现有数据 · </span><span v-else-if="loading">更新中 · </span>{{updated?'最近更新 '+updateLabel:updateLabel}}<span> · 每 30 秒刷新</span></p></div>
 <RankChart :rows="rows" metric="cost" :zoom="zoom" fixed-metric hide-title @zoom="zoom=$event"/>
 <section v-if="frontier.length" class="home-frontier" aria-labelledby="home-frontier-title"><div class="home-frontier-heading"><h3 id="home-frontier-title">优选组合</h3><div class="home-frontier-values"><span>费用 USD</span><span>通过率</span></div></div><ul><li v-for="r in frontier" :key="r.id"><div><strong>{{r.model}}</strong><span>{{r.agent}}</span></div><div class="home-frontier-values"><span><small class="sr-only">费用 USD</small><b>&#36;{{amount(r.cost)}}</b></span><span><small class="sr-only">通过率</small><b>{{r.score.toFixed(1)}}%</b></span></div></li></ul></section>
 </section>`};

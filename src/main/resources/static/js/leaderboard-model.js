import {averageTime,timeMetrics} from './report-metrics.js';
import {executionSourceKey} from './execution-sources.js';

export const validNumber=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0;
export const displayScore=record=>validNumber(record.score)&&record.score<=100?Number(record.score.toFixed(1)):null;
export const rankingEligible=record=>record?.trust?.rankingEligible===true;
export const trustLabel=record=>record.officialBaseline===true||record.paperBaseline===true?'正式版本':record.demo===true?'示例':rankingEligible(record)?'平台核验':'公开报告 · 未入榜';
const stable=(a,b)=>String(a.record.id).localeCompare(String(b.record.id),'en');
const scoreValue=entry=>displayScore(entry.record)??-Infinity;
const categoryRate=(record,index)=>validNumber(record.categories?.[index]?.score)?record.categories[index].score:'';

// Metadata used by the view lives outside the original records, so downloads
// keep their existing shape and comparisons never rewrite submitted results.
export function leaderboardModel(builtins,publicReports,ownReports,filters,query=''){
 const all=[...builtins.map(record=>({record:{...record,scope:'full',demo:true},builtin:true})),
  ...publicReports.map(record=>({record,builtin:false})),
  ...ownReports.filter(r=>r.published).map(record=>({record,builtin:false}))];
 const seen=new Set();
 const cohort=all.filter(entry=>{
  const r=entry.record;if(seen.has(r.id))return false;seen.add(r.id);
  return r.kind!=='pk'&&r.scope==='full'&&r.datasetId===filters.dataset&&r.version===filters.version&&r.budget===filters.budget&&
   (filters.source==='all'||executionSourceKey(r.source)===filters.source)&&
   (filters.type==='official'||filters.type==='paper'?(r.officialBaseline===true||r.paperBaseline===true):
    filters.type==='public'?r.demo!==true&&rankingEligible(r):r.demo===true);
 }).sort((a,b)=>scoreValue(b)-scoreValue(a)||stable(a,b));
 let previous=null,place=0;
 for(const [index,entry] of cohort.entries()){
  const score=displayScore(entry.record);
  if(score!==null&&score!==previous)place=index+1;
  entry.place=score===null?null:place;previous=score;
 }
 const search=query.trim().toLocaleLowerCase();
 const visible=cohort.filter(({record:r})=>!search||[r.agent,r.model].some(value=>String(value??'').toLocaleLowerCase().includes(search)));
 const sortable=new Set(['cost','time','fullAcc','vpr','process']);
 if(filters.sort!=='score'&&sortable.has(filters.sort))visible.sort((a,b)=>{
  const key=filters.sort==='time'?'time':filters.sort;
  const aValue=key==='time'?averageTime(a.record):a.record[key];
  const bValue=key==='time'?averageTime(b.record):b.record[key];
  const av=validNumber(aValue)?aValue:Infinity,bv=validNumber(bValue)?bValue:Infinity;
  const compared=['time','cost'].includes(key)?av-bv:bv-av;
  return compared||stable(a,b);
 });
 return {cohort,visible,leaders:cohort.filter(entry=>entry.place===1),costCount:cohort.filter(({record:r})=>validNumber(r.cost)).length};
}

export function leaderboardCsvRows(entries){
 return [['demo','official_baseline','agent','framework','model','dataset','version','budget','passed','total','vcr','full_acc','vpr','process','time_s','cost_usd','source','total_time_s','average_time_s','relations_vcr','search_vcr','exact_vcr','bounds_vcr','model_verified','usage_verified','cost_verified','ranking_eligible'],
  ...entries.map(({record:r})=>[typeof r.demo==='boolean'?r.demo:'',r.officialBaseline===true||r.paperBaseline===true,r.agent,r.framework||'',r.model,r.datasetId,r.version,r.budget,r.passed,r.total,r.score,r.fullAcc??'',r.vpr??'',r.process??'',r.time,r.cost,r.source,timeMetrics(r).totalTimeSeconds,averageTime(r),categoryRate(r,0),categoryRate(r,1),categoryRate(r,2),categoryRate(r,3),r.trust?.modelIdentityVerified===true,r.trust?.usageVerified===true,r.trust?.costVerified===true,rankingEligible(r)])];
}

export const validNumber=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0;
export const displayScore=record=>validNumber(record.score)&&record.score<=100?Number(record.score.toFixed(1)):null;
const stable=(a,b)=>String(a.record.id).localeCompare(String(b.record.id),'en');
const scoreValue=entry=>displayScore(entry.record)??-Infinity;

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
   (filters.source==='all'||r.source===filters.source)&&(filters.type==='public'?r.demo!==true:r.demo===true);
 }).sort((a,b)=>scoreValue(b)-scoreValue(a)||stable(a,b));
 let previous=null,place=0;
 for(const [index,entry] of cohort.entries()){
  const score=displayScore(entry.record);
  if(score!==null&&score!==previous)place=index+1;
  entry.place=score===null?null:place;previous=score;
 }
 const search=query.trim().toLocaleLowerCase();
 const visible=cohort.filter(({record:r})=>!search||[r.agent,r.model].some(value=>String(value??'').toLocaleLowerCase().includes(search)));
 if(filters.sort!=='score')visible.sort((a,b)=>{
  const key=filters.sort==='cost'?'cost':'time';
  const av=validNumber(a.record[key])?a.record[key]:Infinity,bv=validNumber(b.record[key])?b.record[key]:Infinity;
  return av-bv||stable(a,b);
 });
 return {cohort,visible,leaders:cohort.filter(entry=>entry.place===1),costCount:cohort.filter(({record:r})=>validNumber(r.cost)).length};
}

export function leaderboardCsvRows(entries){
 return [['demo','agent','model','dataset','version','budget','passed','total','pass_rate','time_s','cost_usd','source'],
  ...entries.map(({record:r})=>[typeof r.demo==='boolean'?r.demo:'',r.agent,r.model,r.datasetId,r.version,r.budget,r.passed,r.total,r.score,r.time,r.cost,r.source])];
}

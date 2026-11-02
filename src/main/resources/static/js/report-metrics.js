// View-only accessors: never rewrite historical reports or their legacy `time`.
const numeric=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0;
const has=(record,key)=>Object.prototype.hasOwnProperty.call(record||{},key);

export function participantResults(report,record=report){
 const rows=Array.isArray(report?.results)?report.results:[];
 if(report?.kind!=='pk')return rows;
 const index=report.competitors?.indexOf(record)??-1;
 const key=record?.participantKey||(index===0?'a':index===1?'b':null);
 if(!key)return [];
 return rows.filter(row=>(row.participantKey||String(row.taskId||'').split('::')[0])===key);
}

function completeResults(record,report){
 const rows=participantResults(report,record);
 if(!Number.isSafeInteger(record?.total)||record.total<=0||rows.length!==record.total)return null;
 const ids=rows.map(row=>row.taskId);
 return ids.every(id=>typeof id==='string'&&id.length>0)&&new Set(ids).size===rows.length?rows:null;
}

export function timeMetrics(record,report=record){
 const rows=completeResults(record,report);
 const complete=rows?.every(row=>numeric(row.elapsedMs)&&Number.isSafeInteger(row.elapsedMs));
 const total=complete?rows.reduce((sum,row)=>sum+row.elapsedMs/1000,0):null;
 const derived=numeric(total)?total:null;
 return {
  totalTimeSeconds:has(record,'totalTimeSeconds')?(numeric(record.totalTimeSeconds)?record.totalTimeSeconds:null):derived,
  averageTimeSeconds:has(record,'averageTimeSeconds')?(numeric(record.averageTimeSeconds)?record.averageTimeSeconds:null):(derived===null?null:derived/record.total)
 };
}

export const averageTime=record=>timeMetrics(record).averageTimeSeconds;
export const secondsText=value=>numeric(value)?value.toLocaleString('en-US',{maximumFractionDigits:3})+' s':'未知';
const rate=(passed,total)=>numeric(passed)&&numeric(total)&&total>0&&passed<=total?Math.round(passed*1000/total)/10:null;
export const categoryText=row=>row?`${row.passed??'未知'} / ${row.total??'未知'} · ${numeric(row.score)?row.score.toFixed(1)+'%':'未知'}`:'未知';

export function reportCategories(record,report=record){
 if(Array.isArray(record?.categories)&&record.categories.length)return record.categories.map(row=>({...row,score:rate(row.passed,row.total)}));
 const rows=completeResults(record,report);
 if(rows?.every(row=>typeof row.category==='string'&&typeof row.passed==='boolean')){
  const groups=new Map();
  for(const row of rows){
   const name=row.category.trim()||'未分类';
   if(!groups.has(name))groups.set(name,{name,passed:0,total:0});
   const group=groups.get(name);group.total++;if(row.passed)group.passed++;
  }
  return [...groups.values()].map(row=>({...row,score:rate(row.passed,row.total)}));
 }
 return [{name:'全部任务',passed:record?.passed??null,total:record?.total??null,score:rate(record?.passed,record?.total)}];
}

export function alignedCategories(report){
 if(report?.kind!=='pk')return reportCategories(report).map(a=>({name:a.name,a,b:null}));
 const [a,b]=report.competitors||[];
 // Old PK reports stored the A-side categories only at the top level.
 const left=reportCategories(!a?.categories?.length&&report.categories?.length?{...a,categories:report.categories}:a,report);
 const right=reportCategories(b,report);
 return [...new Set([...left,...right].map(row=>row.name))].map(name=>({name,a:left.find(row=>row.name===name),b:right.find(row=>row.name===name)}));
}

export function reportCsvRows(report){
 return [['task_id','status','passed','cost_usd','elapsed_ms','category','participant_key'],
  ...(report?.results||[]).map(row=>[row.taskId,row.status,row.passed,row.costUsd,row.elapsedMs,row.category,
   row.participantKey||(String(row.taskId||'').includes('::')?row.taskId.split('::')[0]:'')])];
}

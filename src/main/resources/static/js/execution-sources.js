// The current application runs local demos. Other execution locations are
// declarations in submitted reports, not execution services provided by us.
export const executionSourceKey=value=>typeof value==='string'&&value.trim()?value:'unreported';
export function executionSourceLabel(value){
 const key=executionSourceKey(value);
 if(key==='all')return '全部来源';
 if(key==='local')return '本地执行';
 if(key==='cloud')return '云端（报告声明）';
 if(key==='unreported')return '未提供来源';
 return key+'（报告声明）';
}
export function executionSourceOptions(records){
 const keys=new Set(['local']);
 for(const record of records)keys.add(executionSourceKey(record.source));
 return ['all',...keys].map(value=>({value,label:executionSourceLabel(value)}));
}

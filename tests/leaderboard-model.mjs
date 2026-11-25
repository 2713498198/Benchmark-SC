import assert from 'node:assert/strict';
import {leaderboardModel,leaderboardCsvRows,displayScore,rankingEligible,trustLabel} from '../src/main/resources/static/js/leaderboard-model.js';
import {chartModel,chartAxes} from '../src/main/resources/static/js/leaderboard-chart.js';
import {executionSourceOptions,executionSourceLabel} from '../src/main/resources/static/js/execution-sources.js';
import {paperLeaderboardRows,paperBenchmark} from '../src/main/resources/static/js/paper-leaderboard.js';
const rankings=[{source:'local'},{source:'local'},{source:'local'},{source:'local'},{source:'local'}];

const filters={dataset:'set',version:'1',budget:'standard',source:'all',type:'demo',sort:'score'};
const row={datasetId:'set',version:'1',budget:'standard',scope:'full',source:'local',total:100,agent:'Agent',model:'Model'};
const sample=(id,score,extra={})=>({...row,id,score,demo:true,...extra});
const verifiedTrust={rankingEligible:true,modelIdentityVerified:true,usageVerified:true,costVerified:true};
const builtins=[sample('a',90.04,{cost:8,time:10,averageTimeSeconds:10}),sample('b',90.01,{cost:1,time:30,averageTimeSeconds:30}),sample('c',80,{cost:null,time:null})];
const publicRows=[sample('demo',70,{published:true,cost:0,time:40,averageTimeSeconds:40}),sample('public',99,{demo:false,cost:2,trust:verifiedTrust}),sample('unknown',85,{demo:undefined}),sample('partial',100,{scope:'sample'}),sample('pk',100,{kind:'pk'}),sample('other-version',100,{version:'2'}),sample('other-budget',100,{budget:'extended'})];
const own=[sample('private',100,{published:false}),{...publicRows[0],published:true}];
const snapshot=JSON.stringify([builtins,publicRows,own]);
const data=leaderboardModel(builtins,publicRows,own,filters);
assert.deepEqual(data.cohort.map(e=>[e.record.id,e.place]),[['a',1],['b',1],['c',3],['demo',4]]);
assert.equal(data.leaders.length,2);
assert.equal(data.costCount,3);
assert.equal(displayScore({score:90.04}),90);
assert.equal(displayScore({score:null}),null);
assert.equal(displayScore({score:101}),null);
for(const sort of ['cost','time']){
 const sorted=leaderboardModel(builtins,publicRows,own,{...filters,sort});
 assert.equal(sorted.visible.at(-1).record.id,'c','Missing values go last');
 assert.deepEqual(Object.fromEntries(sorted.visible.map(e=>[e.record.id,e.place])),{a:1,b:1,c:3,demo:4});
 const searched=leaderboardModel(builtins,publicRows,own,{...filters,sort},'agent');
 assert.equal(searched.cohort.length,4);
}
const searched=leaderboardModel(builtins.map(r=>({...r,agent:r.id})),publicRows,own,filters,'c');
assert.equal(searched.visible[0].place,3,'Search does not rerank');
assert.equal(searched.cohort.length,4);
const actual=leaderboardModel(builtins,publicRows,own,{...filters,type:'public'});
assert.deepEqual(actual.cohort.map(e=>[e.record.id,e.place]),[['public',1]]);
assert.equal(rankingEligible(publicRows[1]),true);
assert.equal(rankingEligible(publicRows[2]),false);
assert.equal(trustLabel(publicRows[1]),'平台核验');
assert.equal(trustLabel(publicRows[2]),'公开报告 · 未入榜');
assert.deepEqual(chartModel(actual.visible.map(e=>e.record)).frontier.map(p=>p.rows[0].id),['public']);
assert.deepEqual(leaderboardModel(builtins,publicRows,own,{...filters,source:'cloud'}).cohort,[]);
assert.ok(rankings.every(r=>r.source==='local'),'Built-in demos use the implemented local execution path');
assert.deepEqual(executionSourceOptions(rankings),[{value:'all',label:'全部来源'},{value:'local',label:'本地执行'}]);
const declared=[sample('local-source',80),sample('cloud-source',80,{source:'cloud'}),sample('missing-source',80,{source:null}),sample('other-source',80,{source:'external-runner'})];
assert.deepEqual(executionSourceOptions(declared).map(o=>o.value),['all','local','cloud','unreported','external-runner']);
assert.equal(executionSourceLabel('cloud'),'云端（报告声明）');
assert.equal(executionSourceLabel(null),'未提供来源');
for(const [key,id] of [['local','local-source'],['cloud','cloud-source'],['unreported','missing-source'],['external-runner','other-source']]){
 assert.deepEqual(leaderboardModel([],declared,[],{...filters,source:key}).cohort.map(e=>e.record.id),[id]);
}
assert.equal(declared[2].source,null,'Missing execution sources are never rewritten as local');
assert.deepEqual(leaderboardCsvRows(actual.visible).map(r=>r[0]),['demo',false]);
assert.deepEqual(leaderboardCsvRows(data.visible).slice(1).map(r=>r[0]),[true,true,true,true]);
assert.equal(leaderboardCsvRows(data.visible)[0].length,27);
assert.equal(JSON.stringify([builtins,publicRows,own]),snapshot,'Source records remain untouched');
const paperFilters={dataset:'cryptobench-sc-v4',version:'4.2.0',budget:'official',source:'all',type:'official',sort:'score'};
const paper=leaderboardModel(paperLeaderboardRows,publicRows,own,paperFilters);
assert.equal(paperLeaderboardRows.length,24);
assert.equal(paperBenchmark.combinationCount,24);
assert.deepEqual(paperBenchmark.metricMeans,{vcr:30.8,fullAcc:37.3,vpr:49.8,process:68});
assert.equal(paper.cohort.length,24);
assert.equal(paper.cohort[0].record.model,'Claude Opus 5.5');
assert.equal(paper.cohort[0].record.score,37.1);
assert.equal(paper.cohort[1].record.score,36.5);
assert.equal(paper.cohort[0].record.passed,63);
assert.equal(paper.cohort[0].record.fullAcc,42.9);
assert.equal(paper.cohort[0].record.vpr,72.9);
assert.equal(paper.cohort[0].record.process,85.3);
assert.equal(paper.cohort[0].record.categories.reduce((sum,row)=>sum+row.passed,0),paper.cohort[0].record.passed);
assert.ok(paper.cohort.every(row=>row.record.categories.length===4&&row.record.officialBaseline));
for(const row of paperLeaderboardRows){
 assert.equal(row.categories.reduce((sum,category)=>sum+category.passed,0),row.passed);
 assert.equal(row.vcr,Number((row.passed/row.total*100).toFixed(1)));
 for(const category of row.categories){
  assert.equal(category.score,Number((category.passed/category.total*100).toFixed(1)));
 }
}
assert.equal(trustLabel(paper.cohort[0].record),'正式版本');
assert.equal(executionSourceLabel('official'),'正式版本');
assert.deepEqual(executionSourceOptions(paperLeaderboardRows).map(option=>option.value),['all','official']);
assert.deepEqual(leaderboardModel(paperLeaderboardRows,publicRows,own,{...paperFilters,sort:'fullAcc'}).visible[0].record.model,'Claude Opus 5.5');
assert.equal(leaderboardCsvRows(paper.visible).length,25);
assert.deepEqual(leaderboardCsvRows(paper.visible)[0].slice(10,14),['vcr','full_acc','vpr','process']);
const points=chartModel(data.visible.map(e=>e.record)).points;
assert.equal(chartAxes(points).yMin,0);assert.equal(chartAxes(points).yMax,100);
assert.ok(chartAxes(points,'cost',true).yMin>0,'Zoom is explicit');
for(const zoom of [false,true])for(const y of [0,100]){
 const axes=chartAxes([{x:0,y}],'cost',zoom);assert.ok(axes.yMin<=y&&axes.yMax>=y&&axes.yMax>axes.yMin);
}
console.log('PASS leaderboard model: rounded ties, stable ranks, search, cohorts, coverage, missing values, CSV flags, source immutability, axis zoom.');

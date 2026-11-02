import assert from 'node:assert/strict';
import {timeMetrics,averageTime,reportCategories,alignedCategories,reportCsvRows} from '../src/main/resources/static/js/report-metrics.js';
import {leaderboardModel,leaderboardCsvRows} from '../src/main/resources/static/js/leaderboard-model.js';
import {chartModel} from '../src/main/resources/static/js/leaderboard-chart.js';

const legacy={total:2,time:4,passed:1,results:[{taskId:'main::1',elapsedMs:1000,passed:true,category:' A '},{taskId:'main::2',elapsedMs:3000,passed:false,category:'B'}]};
const snapshot=JSON.stringify(legacy);
assert.deepEqual(timeMetrics(legacy),{totalTimeSeconds:4,averageTimeSeconds:2});
assert.equal(averageTime({time:42,total:2}),null,'Legacy time is never assumed to be an average');
assert.equal(averageTime({...legacy,results:legacy.results.slice(0,1)}),null);
assert.equal(averageTime({...legacy,results:[legacy.results[0],legacy.results[0]]}),null);
assert.equal(averageTime({...legacy,results:legacy.results.map(r=>({...r,elapsedMs:null}))}),null);
assert.equal(averageTime({...legacy,averageTimeSeconds:null}),null,'Explicit unknown metrics remain unknown');
for(const value of [NaN,Infinity,-1,'2'])assert.equal(averageTime({averageTimeSeconds:value}),null);
assert.equal(averageTime({averageTimeSeconds:0}),0);
assert.equal(averageTime({...legacy,averageTimeSeconds:10}),10,'Explicit fields take precedence');
assert.deepEqual(reportCategories(legacy),[{name:'A',passed:1,total:1,score:100},{name:'B',passed:0,total:1,score:0}]);
assert.deepEqual(reportCategories({...legacy,results:legacy.results.map(({category,...r})=>r)}),[{name:'全部任务',passed:1,total:2,score:50}]);
assert.deepEqual(reportCategories({...legacy,categories:[{name:'历史分类',passed:1,total:2}]}),[{name:'历史分类',passed:1,total:2,score:50}]);
assert.equal(JSON.stringify(legacy),snapshot,'Reading metrics must not change exported JSON');

const pk={kind:'pk',total:2,competitors:[{total:2,passed:1},{total:2,passed:0}],results:[
 ...legacy.results.map(r=>({...r,taskId:r.taskId.replace('main::','a::')})),
 ...legacy.results.map(r=>({...r,taskId:r.taskId.replace('main::','b::'),elapsedMs:5000,passed:false}))
]};
assert.equal(timeMetrics(pk.competitors[0],pk).averageTimeSeconds,2);
assert.equal(timeMetrics(pk.competitors[1],pk).averageTimeSeconds,5);
assert.deepEqual(alignedCategories(pk).map(r=>[r.name,r.a.passed,r.b.passed]),[['A',1,0],['B',0,0]]);
const reordered={...pk,competitors:[{participantKey:'b',total:2,passed:0},{participantKey:'a',total:2,passed:1}]};
assert.equal(timeMetrics(reordered.competitors[0],reordered).averageTimeSeconds,5);
const oldPk={kind:'pk',total:2,categories:[{name:'全部任务',total:2,passed:1}],competitors:[{total:2,passed:1},{total:2,passed:0}]};
assert.deepEqual(alignedCategories(oldPk).map(r=>[r.name,r.a.passed,r.b.passed]),[['全部任务',1,0]]);
const mismatch={...oldPk,competitors:[{categories:[{name:'A',passed:1,total:1}]},{categories:[{name:'B',passed:0,total:1}]}]};
assert.equal(alignedCategories(mismatch)[0].b,undefined,'Different category names never borrow another side’s total');

const filters={dataset:'set',version:'1',budget:'standard',source:'all',type:'public',sort:'time'};
const common={scope:'full',datasetId:'set',version:'1',budget:'standard',source:'local',score:50};
const rows=[{...common,id:'large',time:100,averageTimeSeconds:1,totalTimeSeconds:100},
 {...common,id:'small',time:4,...legacy},
 {...common,id:'unknown',time:0,total:1}];
const sorted=leaderboardModel([],rows,[],filters).visible;
assert.deepEqual(sorted.map(e=>e.record.id),['large','small','unknown'],'Sort by average, including legacy derivation, unknown last');
assert.deepEqual(chartModel(rows,'time').points.map(p=>p.x),[1,2]);
assert.equal(chartModel(rows,'time').excluded,1);
const csv=leaderboardCsvRows(sorted);
assert.deepEqual(csv[0].slice(-2),['total_time_s','average_time_s']);
assert.equal(csv[1][9],100,'Keep the original time_s column');
assert.deepEqual(csv[1].slice(-2),[100,1]);
assert.deepEqual(reportCsvRows(legacy)[0].slice(-2),['category','participant_key']);
assert.deepEqual(reportCsvRows(legacy)[1].slice(-2),[' A ','main']);
console.log('PASS report metrics: cumulative/average timing, strict legacy fallback, null/zero, PK isolation, named categories, sorting/chart/CSV, raw immutability');

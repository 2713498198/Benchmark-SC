import assert from 'node:assert/strict';
import {leaderboardModel,leaderboardCsvRows,displayScore} from '../js/leaderboard-model.js';
import {chartModel,chartAxes} from '../js/leaderboard-chart.js';

const filters={dataset:'set',version:'1',budget:'standard',source:'all',type:'demo',sort:'score'};
const row={datasetId:'set',version:'1',budget:'standard',scope:'full',source:'local',total:100,agent:'Agent',model:'Model'};
const sample=(id,score,extra={})=>({...row,id,score,demo:true,...extra});
const builtins=[sample('a',90.04,{cost:8,time:10}),sample('b',90.01,{cost:1,time:30}),sample('c',80,{cost:null,time:null})];
const publicRows=[sample('demo',70,{published:true,cost:0,time:40}),sample('public',99,{demo:false,cost:2}),sample('unknown',85,{demo:undefined}),sample('partial',100,{scope:'sample'}),sample('pk',100,{kind:'pk'}),sample('other-version',100,{version:'2'}),sample('other-budget',100,{budget:'extended'})];
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
assert.deepEqual(actual.cohort.map(e=>[e.record.id,e.place]),[['public',1],['unknown',2]]);
assert.deepEqual(chartModel(actual.visible.map(e=>e.record)).frontier.map(p=>p.rows[0].id),['public']);
assert.deepEqual(leaderboardModel(builtins,publicRows,own,{...filters,source:'cloud'}).cohort,[]);
assert.deepEqual(leaderboardCsvRows(actual.visible).map(r=>r[0]),['demo',false,'']);
assert.deepEqual(leaderboardCsvRows(data.visible).slice(1).map(r=>r[0]),[true,true,true,true]);
assert.equal(leaderboardCsvRows(data.visible)[0].length,12);
assert.equal(JSON.stringify([builtins,publicRows,own]),snapshot,'Source records remain untouched');
const points=chartModel(data.visible.map(e=>e.record)).points;
assert.equal(chartAxes(points).yMin,0);assert.equal(chartAxes(points).yMax,100);
assert.ok(chartAxes(points,'cost',true).yMin>0,'Zoom is explicit');
for(const zoom of [false,true])for(const y of [0,100]){
 const axes=chartAxes([{x:0,y}],'cost',zoom);assert.ok(axes.yMin<=y&&axes.yMax>=y&&axes.yMax>axes.yMin);
}
console.log('PASS leaderboard model: rounded ties, stable ranks, search, cohorts, coverage, missing values, CSV flags, source immutability, axis zoom.');

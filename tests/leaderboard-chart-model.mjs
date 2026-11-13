import assert from 'node:assert/strict';
import {chartModel,chartAxes,renderRankChart} from '../src/main/resources/static/js/leaderboard-chart.js';
const rankings=[
 {id:'rank-cipher-pilot',rank:1,agent:'CipherPilot',score:91.7,time:38.6,averageTimeSeconds:38.6,totalTimeSeconds:4632,cost:2.84,source:'local'},
 {id:'rank-sbox-sage',rank:2,agent:'S-box Sage',score:88.3,time:42.1,averageTimeSeconds:42.1,totalTimeSeconds:5052,cost:3.16,source:'local'},
 {id:'rank-diff-hunter',rank:3,agent:'DiffHunter',score:85.8,time:35.4,averageTimeSeconds:35.4,totalTimeSeconds:4248,cost:0.76,source:'local'},
 {id:'rank-round-key',rank:4,agent:'RoundKey',score:81.7,time:31.8,averageTimeSeconds:31.8,totalTimeSeconds:3816,cost:0.62,source:'local'},
 {id:'rank-linear-lab',rank:5,agent:'LinearLab',score:77.5,time:46.2,averageTimeSeconds:46.2,totalTimeSeconds:5544,cost:null,source:'local'}
];

const demo=chartModel(rankings);
assert.equal(demo.included,4);
assert.equal(demo.excluded,1,'Missing cost must not become a free result');
assert.deepEqual(demo.frontier.map(p=>p.rows[0].agent),['RoundKey','DiffHunter','CipherPilot']);
assert.equal(chartModel(rankings,'time').included,5);

const fixture=[
 {id:'free',cost:0,score:60},
 {id:'a',cost:1,score:80},{id:'tie',cost:1,score:80},
 {id:'same-cost-worse',cost:1,score:70},{id:'same-score-costlier',cost:2,score:80},
 {id:'best',cost:3,score:100},
 {id:'null',cost:null,score:90},{id:'missing',score:90},
 {id:'nan',cost:NaN,score:90},{id:'infinite',cost:Infinity,score:90},
 {id:'negative',cost:-1,score:90},{id:'string',cost:'1',score:90},
 {id:'invalid-score',cost:1,score:101},{id:'no-score',cost:1,score:null}
];
const result=chartModel(fixture);
assert.equal(result.excluded,8);
assert.equal(result.included,6);
assert.equal(result.points.length,5,'Coincident points retain all underlying records');
assert.deepEqual(result.frontier.map(p=>p.rows.map(r=>r.id)),[['free'],['a','tie'],['best']]);
assert.deepEqual(chartModel([...fixture].reverse()).frontier.map(p=>[p.x,p.y]),[[0,60],[1,80],[3,100]],'Frontier must not depend on table sorting');
assert.equal(fixture[0].frontier,undefined,'Chart does not mutate source records');
assert.equal(chartModel(fixture,'invalid').metric,'cost');

// Each domain has finite, ordered ticks and contains every measured point,
// including all-free, zero-score, perfect-score, tiny-fee and single-row cases.
for(const points of [[],[{x:0,y:0}],[{x:0,y:100}],[{x:31.8,y:91.7}],[{x:.000001,y:50}],[{x:1e6,y:99}],demo.points,chartModel(rankings,'time').points]){
 for(const metric of ['cost','time']){
  const axes=chartAxes(points,metric);
  assert.ok(axes.xMax>axes.xMin&&axes.yMax>axes.yMin);
  assert.ok(axes.xTicks.every(Number.isFinite)&&axes.yTicks.every(Number.isFinite));
  assert.ok(axes.yMin>=0&&axes.yMax<=100);
  for(const p of points)assert.ok(p.x>=axes.xMin&&p.x<=axes.xMax&&p.y>=axes.yMin&&p.y<=axes.yMax);
 }
}
assert.match(renderRankChart([]),/当前条件下暂无成绩/);
assert.match(renderRankChart([{cost:null,score:80}]),/暂无可绘制的费用数据/);
console.log('PASS chart model: Pareto dominance, equal-cost/score ties, missing fees, zero values, domains, empty states, and source immutability.');

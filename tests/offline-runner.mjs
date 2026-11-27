import assert from 'node:assert/strict';
import {buildDockerArgs,normalizeDockerProfile} from '../runner/src/offline/docker-agent.mjs';
import {evaluateTask} from '../runner/src/offline/evaluator.mjs';

const response = answer => ({status:'completed',artifact:{answer},evidence:[]});

assert.equal((await evaluateTask(
  {verification:{type:'numeric',expected:25,absoluteTolerance:0,relativeTolerance:0}},
  response(25),
)).passed,true);
assert.equal((await evaluateTask(
  {verification:{type:'numeric',expected:25,absoluteTolerance:0,relativeTolerance:0}},
  response(24),
)).passed,false);
assert.equal((await evaluateTask(
  {verification:{type:'string',expected:'AES-128',ignoreCase:true}},
  response('aes-128'),
)).passed,true);
assert.equal((await evaluateTask(
  {verification:{type:'boolean',expected:true}},
  response(true),
)).passed,true);
assert.equal((await evaluateTask(
  {verification:{type:'fields',numericTolerance:0.01,fields:{rounds:4,activeSBoxes:25}}},
  response({rounds:4,active_sboxes:25}),
)).passed,true);
assert.equal((await evaluateTask(
  {verification:{type:'piecewise',cases:[
    {condition:'x >= 0',y:'x+1',advantage:'2^-10'},
    {condition:'x < 0',y:'x-1',advantage:'2^-11'},
  ]}},
  response([
    {condition:'x<0',y:'x-1',advantage:'2^-11'},
    {condition:'x>=0',y:'x+1',advantage:'2^-10'},
  ]),
)).passed,true);

const profile=normalizeDockerProfile({
  name:'Offline agent',
  model:'fixture',
  adapter:'docker-command',
  image:'cryptobench-agent-fixture:test',
  command:'fixture-agent',
  args:['--task','{taskId}'],
  stdin:'prompt',
  output:'json',
  network:'none',
  env:{AGENT_TOKEN:'secret-value'},
});
assert.equal(profile.network,'none');
assert.equal(profile.cpus,'2');

const args=buildDockerArgs(profile,{runId:'offline-run',taskId:'task-1'},'C:\\tmp\\task',{
  taskId:'task-1',
});
assert.ok(args.includes('--network'));
assert.equal(args[args.indexOf('--network')+1],'none');
assert.ok(args.includes('--read-only'));
assert.ok(args.includes('--cap-drop'));
assert.ok(args.includes('--security-opt'));
assert.ok(args.includes('--pids-limit'));
assert.ok(args.includes('cryptobench-agent-fixture:test'));
assert.ok(args.includes('fixture-agent'));
assert.ok(args.includes('--task'));
assert.ok(args.includes('task-1'));
assert.ok(args.includes('AGENT_TOKEN'));
assert.ok(!args.includes('secret-value'),'credentials must not appear in Docker arguments');
assert.match(args.find(value=>value.startsWith('type=bind,source=')),/target=\/workspace/);

console.log('offline Docker runner tests passed');

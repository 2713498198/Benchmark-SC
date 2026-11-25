import process from 'node:process';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';

const modeFlag = process.argv.indexOf('--mode');
const mode = modeFlag >= 0 ? process.argv[modeFlag + 1] : 'jsonl';
let input = '';
for await (const chunk of process.stdin) input += chunk;

if (mode === 'jsonl') {
  console.log(JSON.stringify({type: 'thread.started', thread_id: 'fixture-thread'}));
  console.log(JSON.stringify({type: 'item.completed', item: {type: 'reasoning', text: '检查题目中的轮数和 S-box。'}}));
  console.log(JSON.stringify({
    type: 'item.completed',
    item: {
      type: 'agent_message',
      text: `active_sbox_bound=4\nobserver=${process.env.AGENT_API_TOKEN || ''}\ninput=${input.includes('Task ID: mini-spn-001') ? 'ok' : 'missing'}`
    }
  }));
  console.log(JSON.stringify({
    type: 'turn.completed',
    usage: {input_tokens: 120, output_tokens: 30, total_tokens: 150}
  }));
} else if (mode === 'text') {
  console.log(`active_sbox_bound=4\n${input.includes('mini-spn-001') ? 'received-task' : 'missing-task'}`);
} else if (mode === 'jsonl-number') {
  console.log(JSON.stringify({
    type: 'item.completed',
    item: {
      type: 'agent_message',
      text: '已完成轨迹分析并验算。\n\n最终答案：**25**。\n\n关键证据保存在 aes4_witness.json。'
    }
  }));
  console.log(JSON.stringify({type: 'turn.completed', usage: {input_tokens: 20, output_tokens: 10}}));
} else if (mode === 'jsonl-object') {
  console.log(JSON.stringify({
    type: 'item.completed',
    item: {
      type: 'agent_message',
      text: '分析完成。\n\n```json\n{"answer":{"rounds":4,"verified":true}}\n```'
    }
  }));
} else if (mode === 'jsonl-live') {
  console.log(JSON.stringify({type: 'turn.started'}));
  await new Promise(resolve => setTimeout(resolve, 150));
  console.log(JSON.stringify({
    type: 'item.started',
    item: {type: 'command_execution', command: 'python solve.py', status: 'in_progress'}
  }));
  await new Promise(resolve => setTimeout(resolve, 150));
  console.log(JSON.stringify({
    type: 'item.completed',
    item: {
      type: 'command_execution',
      command: 'python solve.py',
      status: 'completed',
      exit_code: 0,
      aggregated_output: 'active_sbox_bound=25'
    }
  }));
  console.log(JSON.stringify({
    type: 'item.completed',
    item: {type: 'agent_message', text: '最终答案：25'}
  }));
} else if (mode === 'fail') {
  console.error(`failed with ${process.env.AGENT_API_TOKEN || 'unknown-secret'}`);
  process.exitCode = 7;
} else if (mode === 'hang') {
  setInterval(() => {}, 1000);
} else if (mode === 'workspace') {
  const workspace = process.env.CRYPTOBENCH_WORKSPACE;
  if (!workspace) throw new Error('missing workspace');
  const attachment = JSON.parse(await readFile(join(workspace, 'attachments', 'input.json'), 'utf8'));
  await mkdir(join(workspace, 'code'), {recursive: true});
  await mkdir(join(workspace, 'logs'), {recursive: true});
  await writeFile(join(workspace, 'code', 'solve.py'), 'print("ok")\n', 'utf8');
  await writeFile(join(workspace, 'logs', 'agent_stdout.txt'), 'fixture execution\n', 'utf8');
  console.log(JSON.stringify({
    type: 'item.completed',
    item: {type: 'agent_message', text: `attachment_value=${attachment.value} prompt_contains_base64=${input.includes('eyJ2YWx1ZSI6NDJ9')}`}
  }));
} else {
  throw new Error(`unknown fixture mode: ${mode}`);
}

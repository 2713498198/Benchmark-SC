import process from 'node:process';

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
} else if (mode === 'fail') {
  console.error(`failed with ${process.env.AGENT_API_TOKEN || 'unknown-secret'}`);
  process.exitCode = 7;
} else if (mode === 'hang') {
  setInterval(() => {}, 1000);
} else {
  throw new Error(`unknown fixture mode: ${mode}`);
}

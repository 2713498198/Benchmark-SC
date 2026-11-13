import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {mkdtemp,readFile,writeFile} from 'node:fs/promises';
import http from 'node:http';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {promisify} from 'node:util';
import {loadAgentProfiles,runCommand,validateCommandProfile} from '../runner/src/command-adapter.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const fixture = join(root, 'tests', 'fixtures', 'command-agent.mjs');
const request = {
  runId: 'run-fixture',
  taskId: 'mini-spn-001',
  attempt: 1,
  model: 'fixture-model',
  task: {question: '求活跃 S-box 上界', input: {rounds: 4}},
  budget: {timeoutSeconds: 5}
};
const base = {
  name: 'Fixture CLI',
  model: 'fixture-model',
  version: '1.0.0',
  adapter: 'command',
  command: process.execPath,
  cwd: root,
  stdin: 'prompt',
  output: 'jsonl',
  timeoutSeconds: 5
};

const profile = await validateCommandProfile({
  ...base,
  args: [fixture, '--mode', 'jsonl'],
  env: {AGENT_API_TOKEN: 'fixture-secret-value'}
});
assert.equal(profile.adapter, 'command');

const structured = await runCommand({
  ...base,
  args: [fixture, '--mode', 'jsonl'],
  env: {AGENT_API_TOKEN: 'fixture-secret-value'}
}, request);
assert.equal(structured.status, 'completed');
assert.match(structured.artifact.answer, /active_sbox_bound=4/);
assert.match(structured.artifact.answer, /input=ok/);
assert.doesNotMatch(JSON.stringify(structured), /fixture-secret-value/);
assert.match(JSON.stringify(structured), /\[redacted\]/);
assert.equal(structured.usage.inputTokens, 120);
assert.equal(structured.usage.outputTokens, 30);
assert.equal(structured.usage.totalTokens, 150);
assert.ok(structured.evidence.length >= 4);

const text = await runCommand({
  ...base,
  args: [fixture, '--mode', 'text'],
  output: 'text'
}, request);
assert.match(text.artifact.answer, /received-task/);

const isolated = await runCommand({
  ...base,
  cwd: undefined,
  args: [fixture, '--mode', 'text'],
  output: 'text'
}, request);
assert.equal(isolated.meta.isolatedWorkingDirectory, true);

if (process.platform === 'win32') {
  const wrapper = await validateCommandProfile({
    ...base,
    command: 'npm.cmd',
    args: ['--version'],
    stdin: 'none',
    output: 'text'
  });
  assert.equal(wrapper.shellMode, 'cmd');
  const wrapperResult = await runCommand(wrapper, request);
  assert.match(wrapperResult.artifact.answer, /^\d+\.\d+\.\d+/);
}

await assert.rejects(
  runCommand({
    ...base,
    args: [fixture, '--mode', 'fail'],
    output: 'text',
    env: {AGENT_API_TOKEN: 'fixture-secret-value'}
  }, request),
  error => error.code === 'agent_exit_failed' && !error.message.includes('fixture-secret-value')
);

const timeoutStarted = Date.now();
await assert.rejects(
  runCommand({
    ...base,
    args: [fixture, '--mode', 'hang'],
    timeoutMs: 200
  }, request),
  error => error.code === 'agent_timeout'
);
assert.ok(Date.now() - timeoutStarted < 5000);

const directory = await mkdtemp(join(tmpdir(), 'cryptobench-runner-'));
const manifestPath = join(directory, 'agents.json');
await writeFile(manifestPath, JSON.stringify({
  agents: [
    {...base, args: [fixture, '--mode', 'jsonl']},
    {...base, name: 'Second CLI', model: 'second', args: [fixture, '--mode', 'text'], output: 'text'}
  ]
}), 'utf8');
const profiles = await loadAgentProfiles(manifestPath);
assert.equal(profiles.length, 2);
assert.equal(profiles[1].model, 'second');

let registration = null;
const server = http.createServer(async (request, response) => {
  let body = '';
  for await (const chunk of request) body += chunk;
  registration = {method: request.method, url: request.url, body: JSON.parse(body)};
  response.writeHead(201, {'content-type': 'application/json'});
  response.end(JSON.stringify({id: 'runner-fixture', runnerToken: 'runner-secret-token'}));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
try {
  const address = server.address();
  const runnerConfigPath = join(directory, 'runner.json');
  await promisify(execFile)(process.execPath, [
    'runner/src/cli.mjs',
    'pair',
    '--url', `http://127.0.0.1:${address.port}`,
    '--token', 'pairing-fixture',
    '--agent-config', manifestPath
  ], {
    cwd: root,
    env: {...process.env, CRYPTOBENCH_RUNNER_CONFIG: runnerConfigPath},
    timeout: 10000
  });
  assert.equal(registration.method, 'POST');
  assert.equal(registration.url, '/api/runner/register');
  assert.equal(registration.body.pairingCode, 'pairing-fixture');
  assert.equal(registration.body.agents.profiles.length, 2);
  assert.equal(registration.body.agents.profiles[0].adapter, 'command');
  assert.equal(JSON.stringify(registration.body).includes(fixture), false);
  const saved = JSON.parse(await readFile(runnerConfigPath, 'utf8'));
  assert.equal(saved.runnerId, 'runner-fixture');
  assert.equal(saved.runnerToken, 'runner-secret-token');
  assert.equal(saved.agents[0].command, process.execPath);
  const doctor = await promisify(execFile)(process.execPath, ['runner/src/cli.mjs', 'doctor'], {
    cwd: root,
    env: {...process.env, CRYPTOBENCH_RUNNER_CONFIG: runnerConfigPath},
    timeout: 10000
  });
  assert.match(doctor.stdout, /OK\s+Fixture CLI/);
  const directConfigPath = join(directory, 'runner-direct.json');
  await promisify(execFile)(process.execPath, [
    'runner/src/cli.mjs',
    'pair',
    '--url', `http://127.0.0.1:${address.port}`,
    '--token', 'pairing-direct',
    '--adapter', 'command',
    '--agent-name', 'Direct CLI',
    '--model', 'direct-model',
    '--command', process.execPath,
    '--arg', fixture,
    '--arg', '--mode',
    '--arg', 'jsonl',
    '--stdin', 'prompt',
    '--output', 'jsonl',
    '--cwd', root
  ], {
    cwd: root,
    env: {...process.env, CRYPTOBENCH_RUNNER_CONFIG: directConfigPath},
    timeout: 10000
  });
  assert.equal(registration.body.agents.profiles[0].name, 'Direct CLI');
  assert.equal(registration.body.agents.profiles[0].adapter, 'command');
  const directSaved = JSON.parse(await readFile(directConfigPath, 'utf8'));
  assert.deepEqual(directSaved.agent.args, [fixture, '--mode', 'jsonl']);
} finally {
  await new Promise(resolve => server.close(resolve));
}

console.log('runner command adapter tests passed');

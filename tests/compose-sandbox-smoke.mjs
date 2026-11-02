import assert from 'node:assert/strict';
import {access} from 'node:fs/promises';
import {resolve} from 'node:path';
import {apiClient, assertDemoPolicy, composeTest, pause} from './compose-test-utils.mjs';

// Run on a dedicated engine: the application itself still reaps globally owned resources at startup.
const test = await composeTest(true);
const client = apiClient(test.base);
const image = 'cryptobench-mock-agent:compose';
const runIds = [];
const credentials = {email: `${test.project}@example.com`, password: 'compose-sandbox-password'};
let started = false, basicMode = false;

async function cleanupRunResources() {
  for (const runId of runIds) {
    const filters = ['--filter', 'label=com.cryptobench.owner=cryptobench',
      '--filter', `label=com.cryptobench.run-id=${runId}`];
    for (let attempt = 0; attempt < 10; attempt++) {
      const containers = await test.docker(['ps', '-aq', ...filters]);
      // The worker's finally block can already be removing these resources.
      for (const id of containers.stdout.split(/\r?\n/).filter(Boolean)) await test.docker(['rm', '-f', id], {allowFailure: true});
      const networks = await test.docker(['network', 'ls', '-q', ...filters]);
      for (const id of networks.stdout.split(/\r?\n/).filter(Boolean)) await test.docker(['network', 'rm', id], {allowFailure: true});
      const remaining = await test.docker(['network', 'ls', '-q', ...filters]);
      const remainingContainers = await test.docker(['ps', '-aq', ...filters]);
      if (!remaining.stdout && !remainingContainers.stdout) break;
      if (attempt === 9) throw new Error(`Test run resources remain: ${runId}`);
      await pause(500);
    }
  }
}

async function evaluate() {
  const created = await client.api('/api/runs', {source: 'docker', image, kind: 'evaluation',
    datasetId: 'mini-spn', version: '1.0.0', scope: 'sample', budget: 'standard',
    model: 'GPT-4.1', agentName: 'Compose Mock Agent'}, 202);
  const runId = created.runId || created.id;
  runIds.push(runId);
  const deadline = Date.now() + 120_000;
  let run;
  while (Date.now() < deadline) {
    run = await client.api(`/api/runs/${runId}`);
    if (['completed', 'failed', 'timeout', 'cancelled'].includes(run.status)) break;
    await pause(400);
  }
  assert.equal(run.status, 'completed', JSON.stringify(run));
  assert.equal(run.progress.completed, 8);
  const report = await client.api(`/api/reports/${run.reportId}`);
  assert.equal(report.verified, true);
  assert.equal(report.source, 'docker');
  assert.equal(report.score, 100);
  await cleanupRunResources();
  return {runId, run, report};
}

async function assertPersisted(user, saved) {
  client.cookies.clear();
  assert.equal((await client.api('/api/auth/login', credentials)).user.id, user.id);
  const run = await client.api(`/api/runs/${saved.runId}`);
  assert.equal(run.status, 'completed');
  assert.equal(run.reportId, saved.run.reportId);
  assert.deepEqual(run.progress, saved.run.progress);
  assert.deepEqual(await client.api(`/api/reports/${run.reportId}`), saved.report);
  await assertDemoPolicy(apiClient(test.base), false);
}

try {
  await test.docker(['info', '--format', '{{.ServerVersion}}']);
  started = true;
  await test.compose(['up', '-d', '--build']);
  await test.ready();
  await assertDemoPolicy(client, false);
  const info = await test.compose(['exec', '-T', 'cryptobench', 'docker', 'info', '--format', '{{.ServerVersion}}']);
  assert.match(info.stdout, /^\d+\./);
  const user = (await client.api('/api/auth/register', {name: 'Sandbox Researcher', ...credentials}, 201)).user;
  assert.equal(user.role, 'researcher');
  const runtime = await client.api('/api/agent/runtime');
  assert.equal(runtime.dockerEnabled, true);
  assert.equal(runtime.dockerAvailable, true);
  assert.equal(runtime.allowedImages.find(item => item.name === image)?.available, true);
  const saved = await evaluate();
  await access(resolve(test.dataDir, 'data/cryptobench.mv.db'));

  await test.compose(['restart', 'cryptobench']);
  await test.ready();
  await assertPersisted(user, saved);

  await test.compose(['down', '--remove-orphans']);
  await test.compose(['up', '-d']);
  await test.ready();
  await assertPersisted(user, saved);
  const second = await evaluate();
  assert.notEqual(second.runId, saved.runId);

  await test.compose(['down', '--remove-orphans']);
  basicMode = true;
  await test.compose(['up', '-d', '--build'], {sandbox: false});
  await test.ready({sandbox: false});
  await assertPersisted(user, saved);
  await assertPersisted(user, second);
  assert.equal((await client.api('/api/agent/runtime')).dockerEnabled, false);
  console.log('PASS sandbox evaluation, restart/recreation persistence, second evaluation and basic Compose compatibility');
} finally {
  // Stop the test server first so a still-running worker cannot create more resources.
  try {
    if (started) await test.compose(['down', '--remove-orphans'], {sandbox: !basicMode});
  } finally {
    if (runIds.length) await cleanupRunResources();
  }
  // Shared fixture image tags are kept: deleting them could affect unrelated deployments.
}

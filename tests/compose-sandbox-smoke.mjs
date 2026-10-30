import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {mkdtemp, mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {availablePort} from './portable-test-utils.mjs';

const root = process.cwd();
const suffix = randomUUID().slice(0, 8);
const project = `cryptobench-sandbox-${suffix}`;
await mkdir('tmp', {recursive: true});
const dataDir = resolve(await mkdtemp('tmp/compose-sandbox-data-'));
const port = await availablePort();
const base = `http://127.0.0.1:${port}`;
const image = 'cryptobench-mock-agent:compose';
const proxyImage = 'cryptobench-agent-proxy:compose';
const compose = ['compose', '-p', project, '-f', 'compose.yaml', '-f', 'compose.sandbox.yaml', '--profile', 'sandbox'];
const cookies = new Map();
let runId = '';
let started = false;

function command(args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(args[0], args.slice(1), {cwd: root, env: {...process.env, HOST_PORT: String(port), CRYPTOBENCH_DATA_DIR: dataDir}, stdio: ['ignore', 'pipe', 'pipe']});
    let stdout = '', stderr = '';
    child.stdout.on('data', chunk => stdout += chunk);
    child.stderr.on('data', chunk => stderr += chunk);
    child.on('error', error => options.allowFailure ? resolve({code: null, stdout, stderr: error.message}) : reject(error));
    child.on('close', code => {
      const result = {code, stdout: stdout.trim(), stderr: stderr.trim()};
      if (code !== 0 && !options.allowFailure) reject(new Error(result.stderr || result.stdout || `${args[0]} exited ${code}`));
      else resolve(result);
    });
  });
}

function captureCookies(response) {
  const values = typeof response.headers.getSetCookie === 'function'
    ? response.headers.getSetCookie()
    : (response.headers.get('set-cookie') || '').split(/,(?=[^;]+=)/);
  for (const value of values) {
    const pair = value.split(';', 1)[0];
    const separator = pair.indexOf('=');
    if (separator > 0 && pair.slice(separator + 1)) cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
  }
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  const cookieHeader = [...cookies].map(([name, value]) => `${name}=${value}`).join('; ');
  if (cookieHeader) headers.set('cookie', cookieHeader);
  if (options.body !== undefined) {
    headers.set('content-type', 'application/json');
    const csrf = cookies.get('XSRF-TOKEN');
    if (csrf) headers.set('X-XSRF-TOKEN', decodeURIComponent(csrf));
  }
  const response = await fetch(base + path, {...options, headers});
  captureCookies(response);
  const text = await response.text();
  let body;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!response.ok) throw new Error(`${options.method || 'GET'} ${path} -> ${response.status}: ${text}`);
  return {response, body};
}

async function refreshCsrf() {
  const result = await api('/api/auth/csrf');
  if (result.body?.token) cookies.set('XSRF-TOKEN', encodeURIComponent(result.body.token));
}

async function waitFor(url, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`Timed out waiting for ${url}`);
}

async function cleanupRunResources() {
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const containers = await command(['docker', 'ps', '-aq', '--filter', 'label=com.cryptobench.owner=cryptobench'], {allowFailure: true});
    for (const id of containers.stdout.split(/\r?\n/).filter(Boolean)) await command(['docker', 'rm', '-f', id], {allowFailure: true});
    const networks = await command(['docker', 'network', 'ls', '-q', '--filter', 'label=com.cryptobench.owner=cryptobench'], {allowFailure: true});
    for (const id of networks.stdout.split(/\r?\n/).filter(Boolean)) await command(['docker', 'network', 'rm', id], {allowFailure: true});
    const remaining = await command(['docker', 'network', 'ls', '-q', '--filter', 'label=com.cryptobench.owner=cryptobench'], {allowFailure: true});
    if (!remaining.stdout) return;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`Compose sandbox resources were not cleaned: ${runId}`);
}

try {
  started = true;
  await command(['docker', ...compose, 'up', '-d', '--build']);
  await waitFor(`${base}/actuator/health`);

  const dockerInfo = await command(['docker', ...compose, 'exec', '-T', 'cryptobench', 'docker', 'info', '--format', '{{.ServerVersion}}']);
  assert.match(dockerInfo.stdout, /^\d+\./, 'Compose app container must reach Docker Engine');
  await command(['docker', ...compose, 'exec', '-T', 'cryptobench', 'docker', 'image', 'inspect', image]);
  await command(['docker', ...compose, 'exec', '-T', 'cryptobench', 'docker', 'image', 'inspect', proxyImage]);

  await refreshCsrf();
  const email = `compose-sandbox-${suffix}@example.com`;
  const registration = await api('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({name: 'Compose Sandbox Researcher', email, password: 'compose-sandbox-password'})
  });
  assert.equal(registration.body.user.role, 'researcher');
  await refreshCsrf();

  const runtime = await api('/api/agent/runtime');
  assert.equal(runtime.body.dockerEnabled, true);
  assert.equal(runtime.body.dockerAvailable, true);
  assert.equal(runtime.body.allowedImages.find(item => item.name === image)?.available, true);

  const created = await api('/api/runs', {
    method: 'POST',
    body: JSON.stringify({source: 'docker', image, kind: 'evaluation', datasetId: 'mini-spn', version: '1.0.0',
      scope: 'sample', budget: 'standard', model: 'GPT-4.1', agentName: 'Compose Mock Agent'})
  });
  assert.equal(created.response.status, 202);
  runId = created.body.runId || created.body.id;
  let run;
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    run = (await api(`/api/runs/${encodeURIComponent(runId)}`)).body;
    if (['completed', 'failed', 'timeout', 'cancelled'].includes(run.status)) break;
    await new Promise(resolve => setTimeout(resolve, 400));
  }
  assert.equal(run.status, 'completed', JSON.stringify(run));
  assert.equal(run.progress.completed, 8);
  const report = (await api(`/api/reports/${encodeURIComponent(run.reportId)}`)).body;
  assert.equal(report.verified, true);
  assert.equal(report.source, 'docker');
  assert.equal(report.score, 100);
  await cleanupRunResources();
  console.log(`PASS self-contained Compose Docker E2E: ${runId} -> ${run.reportId}`);
} catch (error) {
  console.error(`FAIL self-contained Compose Docker E2E: ${error.message}`);
  process.exitCode = 1;
} finally {
  if (runId) await cleanupRunResources().catch(error => { console.error(`FAIL sandbox cleanup: ${error.message}`); process.exitCode = 1; });
  if (started) await command(['docker', ...compose, 'down', '--remove-orphans'], {allowFailure: true});
  await command(['docker', 'image', 'rm', '-f', image], {allowFailure: true});
  await command(['docker', 'image', 'rm', '-f', proxyImage], {allowFailure: true});
}

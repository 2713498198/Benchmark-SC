import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {mkdtemp, mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {availablePort} from './portable-test-utils.mjs';

const project = `cryptobench-compose-${randomUUID().slice(0, 8)}`;
await mkdir('tmp', {recursive: true});
const dataDir = resolve(await mkdtemp('tmp/compose-data-'));
const port = await availablePort();
const base = `http://127.0.0.1:${port}`;
let composeStarted = false;

function run(args, {allowFailure = false} = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', args, {
      env: {...process.env, COMPOSE_PROJECT_NAME: project, HOST_PORT: String(port), CRYPTOBENCH_DATA_DIR: dataDir},
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let stdout = '', stderr = '', settled = false;
    child.stdout.on('data', chunk => stdout += chunk);
    child.stderr.on('data', chunk => stderr += chunk);
    child.on('error', error => {
      if (settled) return;
      settled = true;
      if (allowFailure) resolve({code: null, stdout, stderr: error.message});
      else reject(error);
    });
    child.on('close', code => {
      if (settled) return;
      settled = true;
      const result = {code, stdout: stdout.trim(), stderr: stderr.trim()};
      if (code !== 0 && !allowFailure) reject(new Error(result.stderr || result.stdout || `docker exit ${code}`));
      else resolve(result);
    });
  });
}

async function waitForHealthy(container, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = await run(['inspect', '--format', '{{json .State.Health.Status}}', container], {allowFailure: true});
    if (result.code === 0 && result.stdout === '"healthy"') return;
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error('Compose container did not become healthy before the timeout.');
}

try {
  composeStarted = true;
  await run(['compose', '-p', project, 'up', '-d', '--build']);
  const listed = await run(['compose', '-p', project, 'ps', '-q', 'cryptobench']);
  const container = listed.stdout.trim();
  assert.ok(container, 'Compose did not return the platform container id.');
  await waitForHealthy(container);
  const response = await fetch(`${base}/actuator/health`);
  assert.equal(response.status, 200, 'Compose health endpoint did not return HTTP 200.');
  const body = await response.json();
  assert.equal(body.status, 'UP', 'Compose health endpoint did not report UP.');
  // Verify actual H2 persistence across a container recreation, using isolated data.
  const cookies = new Map();
  async function api(path, payload) {
    let csrf;
    if (payload) csrf = await api('/api/auth/csrf');
    const headers = {};
    if (cookies.size) headers.cookie = [...cookies].map(([k, v]) => `${k}=${v}`).join('; ');
    if (payload) { headers['content-type'] = 'application/json'; headers[csrf.headerName] = csrf.token; }
    const result = await fetch(base + path, {method: payload ? 'POST' : 'GET', headers, body: payload ? JSON.stringify(payload) : undefined});
    for (const cookie of result.headers.getSetCookie()) {
      const pair = cookie.split(';')[0], separator = pair.indexOf('=');
      cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
    assert.equal(result.ok, true, `${path}: ${result.status}`);
    return result.json();
  }
  const email = `${project}@example.com`, password = 'compose-persistence-password';
  await api('/api/auth/register', {name: 'Persistence test', email, password});
  await run(['compose', '-p', project, 'down']);
  await run(['compose', '-p', project, 'up', '-d']);
  const restarted = await run(['compose', '-p', project, 'ps', '-q', 'cryptobench']);
  await waitForHealthy(restarted.stdout.trim());
  cookies.clear();
  const login = await api('/api/auth/login', {email, password});
  assert.equal(login.user.email, email, 'Registered user must survive container recreation.');
  console.log(`PASS Compose build, health and database persistence: HTTP ${response.status}`);
} catch (error) {
  console.error(`FAIL Compose smoke: ${error.message}`);
  process.exitCode = 1;
} finally {
  if (composeStarted) {
    const down = await run(['compose', '-p', project, 'down', '--remove-orphans'], {allowFailure: true});
    if (down.code !== 0) {
      console.error(`FAIL Compose smoke cleanup: ${down.stderr || down.stdout || `docker exit ${down.code}`}`);
      process.exitCode = 1;
    }
  }
}

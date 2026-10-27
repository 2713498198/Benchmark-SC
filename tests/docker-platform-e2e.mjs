import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir, readdir} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';

const root = process.cwd();
const suffix = randomUUID().slice(0, 8);
const image = `cryptobench-mock-agent:platform-${suffix}`;
const proxyImage = `cryptobench-agent-proxy:platform-${suffix}`;
const port = 4300 + Math.floor(Math.random() * 500);
const base = `http://127.0.0.1:${port}`;
const dbFile = `./tmp/docker-platform-${suffix}`;
let appProcess;
let appExit;
let runId = '';
const cookies = new Map();

function command(args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(args[0], args.slice(1), {
      cwd: root,
      env: {...process.env, ...(options.env || {})},
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let stdout = '', stderr = '';
    child.stdout.on('data', chunk => stdout += chunk);
    child.stderr.on('data', chunk => stderr += chunk);
    let settled = false;
    child.on('error', error => {
      if (settled) return;
      settled = true;
      if (options.allowFailure === true) resolve({code: null, stdout, stderr: error.message});
      else reject(error);
    });
    child.on('close', code => {
      if (settled) return;
      settled = true;
      const result = {code, stdout: stdout.trim(), stderr: stderr.trim()};
      if (code !== 0 && options.allowFailure !== true) {
        reject(new Error(result.stderr || result.stdout || `${args[0]} exited ${code}`));
      } else resolve(result);
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
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!response.ok) {
    throw new Error(`${options.method || 'GET'} ${path} -> ${response.status}: ${text}`);
  }
  return {response, body};
}

async function refreshCsrf() {
  const result = await api('/api/auth/csrf');
  if (result.body?.token) cookies.set('XSRF-TOKEN', encodeURIComponent(result.body.token));
}

async function waitFor(url, predicate = response => response.ok, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = '';
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (await predicate(response)) return response;
    } catch (error) {
      lastError = error.message;
    }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error(`Timed out waiting for ${url}${lastError ? ` (${lastError})` : ''}`);
}

async function startApplication() {
  const jars = (await readdir(`${root}/target`)).filter(name => /^cryptobench-.*\.jar$/.test(name));
  assert.ok(jars.length, 'mvn verify did not produce an application jar');
  const jar = jars.find(name => !name.endsWith('.original')) || jars[0];
  appProcess = spawn('java', ['-jar', `target/${jar}`], {
    cwd: root,
    env: {
      ...process.env,
      SPRING_PROFILES_ACTIVE: 'local',
      SERVER_ADDRESS: '127.0.0.1',
      PORT: String(port),
      CRYPTOBENCH_DOCKER_ENABLED: 'true',
      CRYPTOBENCH_DOCKER_MOCK_IMAGE: image,
      CRYPTOBENCH_DOCKER_PROXY_IMAGE: proxyImage,
      DB_URL: `jdbc:h2:file:${dbFile};MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE`
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  appProcess.stdout.on('data', chunk => output += chunk);
  appProcess.stderr.on('data', chunk => output += chunk);
  appExit = new Promise(resolve => appProcess.once('close', code => resolve({code, output})));
  await waitFor(`${base}/actuator/health`);
}

async function stopApplication() {
  if (!appProcess || appProcess.exitCode !== null) return;
  appProcess.kill('SIGTERM');
  await Promise.race([appExit, new Promise(resolve => setTimeout(resolve, 12_000))]);
  if (appProcess.exitCode === null) appProcess.kill('SIGKILL');
}

async function removeRunResources(runId) {
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const containers = await command(['docker', 'ps', '-aq', '--filter', `label=com.cryptobench.run-id=${runId}`], {allowFailure: true});
    for (const id of containers.stdout.split(/\r?\n/).filter(Boolean)) {
      await command(['docker', 'rm', '-f', id.trim()], {allowFailure: true});
    }
    const networks = await command(['docker', 'network', 'ls', '-q', '--filter', `label=com.cryptobench.run-id=${runId}`], {allowFailure: true});
    for (const id of networks.stdout.split(/\r?\n/).filter(Boolean)) {
      await command(['docker', 'network', 'rm', id.trim()], {allowFailure: true});
    }
    const remaining = await command(['docker', 'network', 'ls', '-q', '--filter', `label=com.cryptobench.run-id=${runId}`], {allowFailure: true});
    if (!remaining.stdout) return;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`Docker run resources were not cleaned: ${runId}`);
}

try {
  await mkdir(`${root}/tmp`, {recursive: true});
  await command(['docker', 'build', '-f', 'tests/fixtures/mock-agent.Dockerfile', '-t', image, 'tests/fixtures']);
  await command(['docker', 'build', '-f', 'docker/agent-proxy.Dockerfile', '-t', proxyImage, 'docker']);
  await startApplication();

  await refreshCsrf();
  const email = `docker-platform-${suffix}@example.com`;
  const registration = await api('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({name: 'Docker E2E Researcher', email, password: 'docker-platform-password'})
  });
  assert.equal(registration.body.user.role, 'researcher');
  // Registration creates the session; refresh the CSRF cookie before the first
  // protected write so the test mirrors a browser bootstrap sequence.
  await refreshCsrf();

  const runtime = await api('/api/agent/runtime');
  assert.equal(runtime.body.dockerEnabled, true);
  assert.equal(runtime.body.dockerAvailable, true);
  assert.equal(runtime.body.allowedImages.find(item => item.name === image)?.available, true);

  const created = await api('/api/runs', {
    method: 'POST',
    body: JSON.stringify({
      source: 'docker', image, kind: 'evaluation', datasetId: 'mini-spn', version: '1.0.0',
      scope: 'sample', budget: 'standard', model: 'GPT-4.1', agentName: 'Docker Mock Agent'
    })
  });
  assert.equal(created.response.status, 202);
  runId = created.body.runId || created.body.id;
  assert.ok(runId);

  let run;
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    run = (await api(`/api/runs/${encodeURIComponent(runId)}`)).body;
    if (['completed', 'failed', 'timeout', 'cancelled'].includes(run.status)) break;
    await new Promise(resolve => setTimeout(resolve, 300));
  }
  assert.equal(run.status, 'completed', JSON.stringify(run));
  assert.equal(run.progress.completed, 8);
  assert.equal(run.progress.passed, 8);
  assert.ok(run.reportId);

  const report = (await api(`/api/reports/${encodeURIComponent(run.reportId)}`)).body;
  assert.equal(report.verified, true);
  assert.equal(report.source, 'docker');
  assert.equal(report.score, 100);
  assert.equal(report.usage.inputTokens, 80);
  assert.equal(report.usage.outputTokens, 80);
  assert.equal(report.usage.totalTokens, 160);
  assert.equal(report.usage.knownTasks, 8);
  assert.ok(typeof report.cost === 'number' && report.cost > 0);

  const logs = (await api(`/api/runs/${encodeURIComponent(runId)}/logs`)).body;
  assert.ok(logs.some(log => log.message.includes('Docker Agent 容器已启动')));
  console.log(`PASS platform Docker E2E: ${runId} -> ${run.reportId}`);
} catch (error) {
  console.error(`FAIL platform Docker E2E: ${error.message}`);
  const annotation = String(error.stack || error.message).replace(/%/g, '%25').replace(/\r?\n/g, '%0A').replace(/\r/g, '%0D');
  console.log(`::error title=Platform Docker E2E::${annotation}`);
  if (appExit) {
    const exited = await Promise.race([appExit, new Promise(resolve => setTimeout(() => resolve(null), 100))]);
    if (exited?.output) console.error(exited.output.slice(-4000));
  }
  process.exitCode = 1;
} finally {
  await stopApplication();
  if (runId) {
    await new Promise(resolve => setTimeout(resolve, 1000));
    try { await removeRunResources(runId); }
    catch (error) { console.error(`FAIL platform Docker E2E cleanup: ${error.message}`); process.exitCode = 1; }
  }
  await command(['docker', 'image', 'rm', '-f', image], {allowFailure: true});
  await command(['docker', 'image', 'rm', '-f', proxyImage], {allowFailure: true});
}

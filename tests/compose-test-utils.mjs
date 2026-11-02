import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {mkdir, mkdtemp, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {availablePort} from './portable-test-utils.mjs';

export const persistentDb = 'jdbc:h2:file:/app/var/data/cryptobench;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE';
export const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

export async function composeTest(sandbox = false) {
  const project = `cryptobench-${sandbox ? 'sandbox' : 'compose'}-${randomUUID().slice(0, 8)}`;
  await mkdir('tmp', {recursive: true});
  const dataDir = resolve(await mkdtemp('tmp/compose-data-'));
  const envFile = resolve(dataDir, 'empty.env');
  await writeFile(envFile, ''); // Never load the developer's .env credentials or opt-in settings.
  const port = await availablePort();
  const env = {...process.env, COMPOSE_PROFILES: '', COMPOSE_ENV_FILES: '',
    HOST_PORT: String(port), CRYPTOBENCH_DATA_DIR: dataDir,
    CRYPTOBENCH_COMPOSE_DEMO_ENABLED: '', CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL: '',
    CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME: '', CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD: ''};

  function docker(args, {allowFailure = false} = {}) {
    return new Promise((resolve, reject) => {
      const child = spawn('docker', args, {env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']});
      let stdout = '', stderr = '', settled = false;
      child.stdout.on('data', chunk => stdout += chunk);
      child.stderr.on('data', chunk => stderr += chunk);
      child.on('error', error => finish(null, error.message));
      child.on('close', code => finish(code));
      function finish(code, error = '') {
        if (settled) return;
        settled = true;
        const result = {code, stdout: stdout.trim(), stderr: (error || stderr).trim()};
        if (code !== 0 && !allowFailure) reject(new Error(result.stderr || result.stdout || `docker exited ${code}`));
        else resolve(result);
      }
    });
  }
  function compose(args, options = {}) {
    const files = ['-f', 'compose.yaml'];
    if (options.sandbox ?? sandbox) files.push('-f', 'compose.sandbox.yaml', '--profile', 'sandbox');
    return docker(['compose', '--env-file', envFile, '-p', project, ...files, ...args], options);
  }
  async function ready(options = {}) {
    const container = (await compose(['ps', '-q', 'cryptobench'], options)).stdout;
    assert.ok(container, 'Platform container exists');
    const deadline = Date.now() + 120_000;
    while (Date.now() < deadline) {
      const health = await docker(['inspect', '--format', '{{.State.Health.Status}}', container]);
      if (health.stdout === 'healthy') {
        const bindings = JSON.parse((await docker(['inspect', '--format', '{{json .NetworkSettings.Ports}}', container])).stdout);
        assert.ok(bindings['4173/tcp']?.length);
        for (const binding of bindings['4173/tcp']) {
          assert.equal(binding.HostIp, '127.0.0.1');
          assert.equal(binding.HostPort, String(port));
        }
        return;
      }
      await pause(500);
    }
    throw new Error('Compose health check timed out');
  }
  return {project, dataDir, port, base: `http://127.0.0.1:${port}`, env, docker, compose, ready};
}

export function apiClient(base, cookies = new Map()) {
  async function api(path, payload, expectedStatus = 200) {
    const csrf = payload === undefined ? null : await api('/api/auth/csrf');
    const headers = {};
    if (cookies.size) headers.cookie = [...cookies].map(([k, v]) => `${k}=${v}`).join('; ');
    if (csrf) {
      headers['content-type'] = 'application/json';
      headers[csrf.headerName] = csrf.token;
    }
    const response = await fetch(base + path, {method: payload === undefined ? 'GET' : 'POST', headers,
      body: payload === undefined ? undefined : JSON.stringify(payload), signal: AbortSignal.timeout(15_000)});
    for (const value of response.headers.getSetCookie()) {
      const pair = value.split(';')[0], separator = pair.indexOf('=');
      const name = pair.slice(0, separator), content = pair.slice(separator + 1);
      if (content) cookies.set(name, content); else cookies.delete(name);
    }
    assert.equal(response.status, expectedStatus, `${path}: unexpected HTTP status`);
    return response.json();
  }
  return {api, cookies};
}

export async function assertDemoPolicy(client, enabled) {
  assert.equal((await client.api('/api/bootstrap')).demoEnabled, enabled);
  if (!enabled) {
    for (const role of ['researcher', 'admin']) await client.api('/api/auth/demo', {role}, 403);
  }
}

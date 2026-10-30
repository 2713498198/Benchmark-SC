import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {access, mkdir, mkdtemp} from 'node:fs/promises';
import path from 'node:path';
import {availablePort} from './portable-test-utils.mjs';

if (process.platform !== 'win32') {
  console.log('SKIP Windows app-image smoke test: Windows is required');
  process.exit(0);
}

const executable = path.resolve(process.argv[2] || 'output/desktop/CryptoBench/CryptoBench.exe');
await access(executable);
await mkdir('tmp', {recursive: true});
const data = path.resolve(await mkdtemp('tmp/desktop-data-'));
const port = await availablePort();
const child = spawn(executable, [`--server.port=${port}`, `--spring.datasource.url=jdbc:h2:file:${data.replaceAll('\\', '/')}/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE`], {
  cwd: path.dirname(executable),
  windowsHide: true,
  stdio: 'ignore',
  env: {...process.env, JAVA_HOME: path.join(data, 'no-system-java'), JAVA_TOOL_OPTIONS: '-Djava.awt.headless=true', CRYPTOBENCH_DOCKER_ENABLED: 'false'}
});
let spawnError;
child.on('error', error => { spawnError = error; });

async function stop() {
  if (child.exitCode !== null) return;
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], {stdio: 'ignore'});
  } else {
    child.kill();
  }
  await new Promise(resolve => setTimeout(resolve, 1000));
  if (child.exitCode === null) child.kill('SIGKILL');
}

try {
  let response;
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    if (spawnError) throw spawnError;
    assert.equal(child.exitCode, null, 'Desktop launcher exited before becoming healthy');
    try {
      response = await fetch(`http://127.0.0.1:${port}/actuator/health`);
      if (response.ok) break;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert.ok(response?.ok, `CryptoBench desktop app did not become healthy on port ${port}`);
  console.log(`PASS Windows app-image smoke: HTTP ${response.status}`);
} finally {
  await stop();
}

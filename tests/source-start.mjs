import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {mkdir, mkdtemp, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {availablePort} from './portable-test-utils.mjs';

await mkdir('tmp', {recursive: true});
const data = resolve(await mkdtemp('tmp/source-start-'));
const port = await availablePort();
const windows = process.platform === 'win32';
const child = spawn(windows ? 'powershell.exe' : 'bash', windows
  ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', resolve('scripts/run.ps1')]
  : [resolve('start.sh')], {
  windowsHide: true, detached: !windows, stdio: ['ignore', 'pipe', 'pipe'],
  env: {...process.env, PORT: String(port), CRYPTOBENCH_DOCKER_ENABLED: 'false',
    DB_URL: `jdbc:h2:file:${data.replaceAll('\\', '/')}/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE`}
});
let output = '', spawnError;
child.stdout.on('data', chunk => output += chunk);
child.stderr.on('data', chunk => output += chunk);
child.on('error', error => { spawnError = error; });
try {
  const deadline = Date.now() + 180_000;
  let healthy = false;
  while (Date.now() < deadline) {
    if (spawnError) throw spawnError;
    if (child.exitCode !== null) throw new Error(`Launcher exited ${child.exitCode}: ${output}`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/actuator/health`);
      if (response.ok && (await response.json()).status === 'UP') { healthy = true; break; }
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert.equal(healthy, true, `Source startup did not become healthy: ${output}`);
  console.log(`PASS source launcher: HTTP 200 on isolated port ${port}`);
} finally {
  await writeFile(resolve(data, 'startup.log'), output);
  if (child.pid && child.exitCode === null) {
    if (windows) spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], {windowsHide: true, stdio: 'ignore'});
    else process.kill(-child.pid, 'SIGTERM');
  }
}

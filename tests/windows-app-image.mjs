import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {access} from 'node:fs/promises';
import path from 'node:path';

if (process.platform !== 'win32') {
  console.log('SKIP Windows app-image smoke test: Windows is required');
  process.exit(0);
}

const executable = path.resolve(process.argv[2] || 'output/desktop/CryptoBench/CryptoBench.exe');
await access(executable);
const child = spawn(executable, [], {
  cwd: path.dirname(executable),
  windowsHide: true,
  stdio: 'ignore'
});

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
    try {
      response = await fetch('http://127.0.0.1:4173/actuator/health');
      if (response.ok) break;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert.ok(response?.ok, 'CryptoBench desktop app did not become healthy on port 4173');
  console.log(`PASS Windows app-image smoke: HTTP ${response.status}`);
} finally {
  await stop();
}

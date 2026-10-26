import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';

const project = `cryptobench-compose-${randomUUID().slice(0, 8)}`;
let composeStarted = false;

function run(args, {allowFailure = false} = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', args, {
      env: {...process.env, COMPOSE_PROJECT_NAME: project},
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
  const response = await fetch('http://127.0.0.1:4173/actuator/health');
  assert.equal(response.status, 200, 'Compose health endpoint did not return HTTP 200.');
  const body = await response.json();
  assert.equal(body.status, 'UP', 'Compose health endpoint did not report UP.');
  console.log(`PASS Compose smoke: ${container.slice(0, 12)} HTTP ${response.status}`);
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

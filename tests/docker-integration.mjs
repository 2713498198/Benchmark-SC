import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';

const image = `cryptobench-mock-agent:test-${randomUUID().slice(0, 8)}`;
const network = `cryptobench-test-${randomUUID().slice(0, 8)}`;
let container = '';
let dockerAvailable = false;

function run(args, {allowFailure = false} = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', args, {stdio: ['ignore', 'pipe', 'pipe']});
    let stdout = '', stderr = '';
    child.stdout.on('data', chunk => stdout += chunk);
    child.stderr.on('data', chunk => stderr += chunk);
    child.on('error', error => reject(error));
    child.on('close', code => {
      const result = {code, stdout: stdout.trim(), stderr: stderr.trim()};
      if (code !== 0 && !allowFailure) reject(new Error(result.stderr || result.stdout || `docker exit ${code}`));
      else resolve(result);
    });
  });
}

async function waitFor(url, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return response.json();
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error(`Agent readiness timeout: ${url}`);
}

try {
  await run(['version']);
  dockerAvailable = true;
  await run(['info']);
  await run(['build', '-f', 'tests/fixtures/mock-agent.Dockerfile', '-t', image, 'tests/fixtures']);
  await run(['network', 'create', '--driver', 'bridge', '--internal', network]);
  const started = await run(['run', '-d', '--rm', '--network', network, '--publish', '127.0.0.1::8000',
    '--cpus', '1', '--memory', '512m', '--pids-limit', '128', '--read-only', '--cap-drop', 'ALL',
    '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m',
    '--label', 'com.cryptobench.sandbox=true', '--label', `com.cryptobench.network=${network}`, image]);
  container = started.stdout;
  const inspection = JSON.parse((await run(['inspect', container])).stdout)[0];
  if (inspection.HostConfig.ReadonlyRootfs !== true || inspection.HostConfig.Memory !== 512 * 1024 * 1024
      || inspection.HostConfig.PidsLimit !== 128 || inspection.HostConfig.NanoCpus !== 1_000_000_000
      || inspection.Config.Labels['com.cryptobench.sandbox'] !== 'true') {
    throw new Error('Sandbox resource restrictions or labels are missing');
  }
  const networkInspection = JSON.parse((await run(['network', 'inspect', network])).stdout)[0];
  if (networkInspection.Internal !== true) throw new Error('Sandbox network is not internal');
  const portResult = await run(['port', container, '8000/tcp']);
  const port = Number(portResult.stdout.split(':').at(-1));
  if (!port) throw new Error('Docker did not return an Agent port');
  const base = `http://127.0.0.1:${port}`;
  const health = await waitFor(`${base}/health`);
  const capabilities = await waitFor(`${base}/capabilities`);
  if (health.status !== 'ok' || !capabilities.models?.includes('mock-model')) throw new Error('Agent protocol check failed');
  const analyze = await fetch(`${base}/analyze`, {method: 'POST', headers: {'content-type': 'application/json'},
    body: JSON.stringify({taskId: 'mini-spn-001', model: 'mock-model'})});
  if (!analyze.ok || (await analyze.json()).status !== 'completed') throw new Error('Agent analyze check failed');
  console.log(`PASS Docker Agent E2E: ${container.slice(0, 12)} (${image})`);
} catch (error) {
  console.error(`FAIL Docker Agent E2E: ${error.message}`);
  process.exitCode = 1;
} finally {
  if (!dockerAvailable) process.exitCode = 1;
  if (dockerAvailable) {
    if (container) await run(['rm', '-f', container], {allowFailure: true});
    await run(['network', 'rm', network], {allowFailure: true});
    const remainingContainer = await run(['inspect', container || 'missing'], {allowFailure: true});
    const remainingNetwork = await run(['network', 'inspect', network], {allowFailure: true});
    if (remainingContainer.code === 0 || remainingNetwork.code === 0) {
      console.error('FAIL Docker Agent E2E: sandbox resources were not cleaned');
      process.exitCode = 1;
    }
    await run(['image', 'rm', '-f', image], {allowFailure: true});
  }
}

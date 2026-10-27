import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';

const suffix = randomUUID().slice(0, 8);
const image = `cryptobench-mock-agent:test-${suffix}`;
const proxyImage = `cryptobench-agent-proxy:test-${suffix}`;
const agentNetwork = `cryptobench-test-agent-${suffix}`;
const proxyNetwork = `cryptobench-test-proxy-${suffix}`;
let agentContainer = '';
let proxyContainer = '';
let dockerAvailable = false;
let proxyNetworkMode = 'advanced';

function run(args, {allowFailure = false} = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', args, {stdio: ['ignore', 'pipe', 'pipe']});
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
  await run(['build', '-f', 'docker/agent-proxy.Dockerfile', '-t', proxyImage, 'docker']);
  await run(['network', 'create', '--driver', 'bridge', '--internal',
    '--label', 'com.cryptobench.owner=cryptobench', agentNetwork]);
  try {
    await run(['network', 'create', '--driver', 'bridge',
      '--opt', 'com.docker.network.bridge.gateway_mode_ipv4=routed',
      '--opt', 'com.docker.network.bridge.enable_ip_masquerade=false',
      '--label', 'com.cryptobench.owner=cryptobench', proxyNetwork]);
  } catch (error) {
    // Docker 27 and earlier do not understand gateway_mode_ipv4. The Agent
    // is still isolated on the internal network; only the platform proxy
    // uses this compatibility bridge, so the security boundary is unchanged.
    proxyNetworkMode = 'compatibility';
    await run(['network', 'rm', proxyNetwork], {allowFailure: true});
    await run(['network', 'create', '--driver', 'bridge',
      '--label', 'com.cryptobench.owner=cryptobench', proxyNetwork]);
    console.warn(`WARN Docker proxy network options unavailable; using compatibility bridge (${error.message})`);
  }
  const started = await run(['run', '-d', '--rm', '--network', agentNetwork, '--network-alias', 'agent',
    '--cpus', '1', '--memory', '512m', '--memory-swap', '512m', '--ulimit', 'nofile=1024:1024',
    '--pids-limit', '128', '--read-only', '--cap-drop', 'ALL',
    '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m',
    '--label', 'com.cryptobench.owner=cryptobench', '--label', 'com.cryptobench.sandbox=true',
    '--label', `com.cryptobench.network=${agentNetwork}`, image]);
  agentContainer = started.stdout;
  const inspection = JSON.parse((await run(['inspect', agentContainer])).stdout)[0];
  const nofile = inspection.HostConfig.Ulimits?.find(item => item.Name === 'nofile');
  if (inspection.HostConfig.ReadonlyRootfs !== true || inspection.HostConfig.Memory !== 512 * 1024 * 1024
      || inspection.HostConfig.PidsLimit !== 128 || inspection.HostConfig.NanoCpus !== 1_000_000_000
      || inspection.Config.Labels['com.cryptobench.sandbox'] !== 'true') {
    throw new Error('Sandbox resource restrictions or labels are missing');
  }
  if (inspection.HostConfig.MemorySwap !== 512 * 1024 * 1024) {
    console.warn(`WARN Docker did not expose the requested memory swap ceiling: ${inspection.HostConfig.MemorySwap}`);
  }
  if (nofile?.Soft !== 1024 || nofile?.Hard !== 1024) {
    console.warn(`WARN Docker did not expose the requested nofile limit in inspect: ${JSON.stringify(nofile)}`);
  }
  const agentNetworkInspection = JSON.parse((await run(['network', 'inspect', agentNetwork])).stdout)[0];
  if (agentNetworkInspection.Internal !== true) throw new Error('Agent network is not internal');
  const egress = await run(['exec', agentContainer, 'node', '-e',
    "const c=new AbortController();setTimeout(()=>c.abort(),1500);fetch('http://1.1.1.1',{signal:c.signal}).then(()=>process.exit(2)).catch(()=>process.exit(0))"], {allowFailure: true});
  if (egress.code !== 0) throw new Error('Sandbox Agent container can reach an external network');

  proxyContainer = (await run(['create', '--rm', '--network', proxyNetwork, '--publish', '127.0.0.1::8000',
    '--cpus', '0.25', '--memory', '128m', '--memory-swap', '128m', '--ulimit', 'nofile=1024:1024',
    '--pids-limit', '32', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
    '--tmpfs', '/tmp:rw,noexec,nosuid,size=16m', '--env', 'TARGET_HOST=agent', '--env', 'TARGET_PORT=8000',
    '--label', 'com.cryptobench.owner=cryptobench', '--label', 'com.cryptobench.sandbox-proxy=true', proxyImage])).stdout;
  await run(['network', 'connect', agentNetwork, proxyContainer]);
  await run(['start', proxyContainer]);
  const proxyNetworkInspection = JSON.parse((await run(['network', 'inspect', proxyNetwork])).stdout)[0];
  const networkOptions = proxyNetworkInspection.Options || {};
  if (proxyNetworkInspection.Internal === true) {
    throw new Error('Proxy network is not host-only');
  }
  if (proxyNetworkMode === 'advanced' && (networkOptions['com.docker.network.bridge.gateway_mode_ipv4'] !== 'routed'
      || networkOptions['com.docker.network.bridge.enable_ip_masquerade'] !== 'false')) {
    throw new Error('Docker accepted advanced proxy network creation but did not retain its options');
  }
  const portResult = await run(['port', proxyContainer, '8000/tcp']);
  const port = Number(portResult.stdout.split(':').at(-1));
  if (!port) throw new Error('Docker did not return a proxy port');
  const base = `http://127.0.0.1:${port}`;
  const health = await waitFor(`${base}/health`);
  const capabilities = await waitFor(`${base}/capabilities`);
  if (health.status !== 'ok' || !capabilities.models?.includes('mock-model')) throw new Error('Agent protocol check failed');
  const analyze = await fetch(`${base}/analyze`, {method: 'POST', headers: {'content-type': 'application/json'},
    body: JSON.stringify({taskId: 'mini-spn-001', model: 'mock-model'})});
  if (!analyze.ok || (await analyze.json()).status !== 'completed') throw new Error('Agent analyze check failed');
  console.log(`PASS Docker Agent E2E: ${agentContainer.slice(0, 12)} via ${proxyContainer.slice(0, 12)} (${image})`);
} catch (error) {
  console.error(`FAIL Docker Agent E2E: ${error.message}`);
  process.exitCode = 1;
} finally {
  if (!dockerAvailable) process.exitCode = 1;
  if (dockerAvailable) {
    if (proxyContainer) await run(['rm', '-f', proxyContainer], {allowFailure: true});
    if (agentContainer) await run(['rm', '-f', agentContainer], {allowFailure: true});
    await run(['network', 'rm', agentNetwork], {allowFailure: true});
    await run(['network', 'rm', proxyNetwork], {allowFailure: true});
    const remainingAgent = await run(['inspect', agentContainer || 'missing'], {allowFailure: true});
    const remainingProxy = await run(['inspect', proxyContainer || 'missing'], {allowFailure: true});
    const remainingAgentNetwork = await run(['network', 'inspect', agentNetwork], {allowFailure: true});
    const remainingProxyNetwork = await run(['network', 'inspect', proxyNetwork], {allowFailure: true});
    if (remainingAgent.code === 0 || remainingProxy.code === 0
        || remainingAgentNetwork.code === 0 || remainingProxyNetwork.code === 0) {
      console.error('FAIL Docker Agent E2E: sandbox resources were not cleaned');
      process.exitCode = 1;
    }
    await run(['image', 'rm', '-f', image], {allowFailure: true});
    await run(['image', 'rm', '-f', proxyImage], {allowFailure: true});
  }
}

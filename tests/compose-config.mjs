import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {composeTest, persistentDb} from './compose-test-utils.mjs';

const test = await composeTest();
test.env.CRYPTOBENCH_DOCKER_PROXY_NETWORK_MODE = '';
for (const sandbox of [false, true]) {
  for (const enabled of ['', 'true']) {
    test.env.CRYPTOBENCH_COMPOSE_DEMO_ENABLED = enabled;
    test.env.CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL = enabled ? 'config-test@example.com' : '';
    test.env.CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME = enabled ? 'Config Test' : '';
    test.env.CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD = enabled ? 'synthetic-config-test-password' : '';
    const config = JSON.parse((await test.compose(['config', '--format', 'json'], {sandbox})).stdout);
    const service = config.services.cryptobench;
    assert.equal(service.ports.length, 1);
    assert.equal(service.ports[0].host_ip, '127.0.0.1');
    assert.equal(Number(service.ports[0].published), test.port);
    assert.equal(Number(service.ports[0].target), 4173);
    assert.equal(service.environment.SERVER_ADDRESS, '0.0.0.0');
    assert.equal(service.environment.BENCHMARK_DEMOENABLED, enabled || 'false');
    assert.equal(service.environment.DB_URL, persistentDb);
    assert.equal(service.environment.SPRING_PROFILES_ACTIVE, 'local');
    for (const key of ['EMAIL', 'NAME', 'PASSWORD']) {
      assert.equal(service.environment[`CRYPTOBENCH_BOOTSTRAP_ADMIN_${key}`], test.env[`CRYPTOBENCH_BOOTSTRAP_ADMIN_${key}`]);
    }
    const data = service.volumes.find(volume => volume.target === '/app/var');
    assert.equal(data?.type, 'bind');
    assert.equal(resolve(data.source), test.dataDir);
    assert.equal(service.volumes.some(volume => volume.target === '/var/run/docker.sock'), sandbox);
    assert.equal(service.environment.CRYPTOBENCH_DOCKER_ENABLED, String(sandbox));
    if (sandbox) assert.equal(service.environment.CRYPTOBENCH_DOCKER_PROXY_NETWORK_MODE, '');
    console.log(`PASS Compose config: sandbox=${sandbox}, demo=${enabled || 'default(false)'}`);
  }
}
test.env.CRYPTOBENCH_DOCKER_PROXY_NETWORK_MODE = 'compatibility';
const compatible = JSON.parse((await test.compose(['config', '--format', 'json'], {sandbox: true})).stdout).services.cryptobench;
assert.equal(compatible.environment.CRYPTOBENCH_DOCKER_PROXY_NETWORK_MODE, 'compatibility');
console.log('PASS sandbox forwards the CI proxy network compatibility mode');
test.env.HOST_PORT = '';
test.env.CRYPTOBENCH_DATA_DIR = '';
const defaults = JSON.parse((await test.compose(['config', '--format', 'json'])).stdout).services.cryptobench;
assert.equal(Number(defaults.ports[0].published), 4173);
assert.equal(resolve(defaults.volumes.find(v => v.target === '/app/var').source), resolve('var'));
console.log('PASS Compose default port and data directory');

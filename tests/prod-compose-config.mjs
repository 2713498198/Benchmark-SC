import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {readFile} from 'node:fs/promises';

function run(command, args) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(command, args, {
      cwd: process.cwd(),
      env: process.env,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let stdout = '', stderr = '';
    child.stdout.on('data', chunk => stdout += chunk);
    child.stderr.on('data', chunk => stderr += chunk);
    child.on('error', reject);
    child.on('close', code => resolveRun({code, stdout: stdout.trim(), stderr: stderr.trim()}));
  });
}

const compose = (...args) => ['compose', '--env-file', '.env.prod.example', '-f', 'compose.prod.yaml', ...args];

const valid = await run('docker', compose('config', '--services'));
assert.equal(valid.code, 0, valid.stderr);
assert.deepEqual(valid.stdout.split(/\r?\n/), ['mysql', 'app', 'caddy']);

const rendered = await run('docker', compose('config', '--format', 'json'));
assert.equal(rendered.code, 0, rendered.stderr);
const app = JSON.parse(rendered.stdout).services.app;
assert.equal(app.image, 'cryptobench:1.1.0');
assert.equal(app.environment.SPRING_PROFILES_ACTIVE, 'prod');
assert.equal(app.environment.CRYPTOBENCH_RUN_MAX_ACTIVE_PER_USER, '2');
assert.equal(app.environment.CRYPTOBENCH_RUN_MAX_CREATED_PER_DAY_PER_USER, '10');
assert.equal(app.environment.CRYPTOBENCH_RUN_MAX_ACTIVE_GLOBAL, '100');
assert.equal(app.environment.CRYPTOBENCH_V4_SUITE_ROOT, '/app/private/v4-suite');
assert.equal(app.environment.CRYPTOBENCH_V4_PYTHON, 'python3');
assert.ok(app.volumes.some(volume => volume.target === '/app/private/v4-suite' && volume.read_only === true));

const missing = await run('docker', [
  'compose', '--env-file', 'target/missing-prod.env', '-f', 'compose.prod.yaml', 'config'
]);
assert.notEqual(missing.code, 0, 'missing production environment files must be rejected');
assert.ok(missing.stderr.length > 0);

const docs = await readFile('docs/DEPLOY-PROD.md', 'utf8');
for (const command of [
  'docker compose --env-file .env.prod -f compose.prod.yaml up -d --build',
  'docker compose --env-file .env.prod -f compose.prod.yaml ps',
  'docker compose --env-file .env.prod -f compose.prod.yaml up -d --force-recreate app',
  'docker compose --env-file .env.prod -f compose.prod.yaml build app',
  'docker compose --env-file .env.prod -f compose.prod.yaml up -d app',
  'docker compose --env-file .env.prod -f compose.prod.yaml exec -T app'
]) assert.ok(docs.includes(command), `deployment docs must use: ${command}`);
assert.ok(!docs.includes('prod-compose.sh'), 'deployment docs must not reference the removed wrapper');

const backup = await readFile('scripts/backup-prod.sh', 'utf8');
assert.ok(backup.includes('docker compose --env-file .env.prod -f compose.prod.yaml exec -T mysql'));
assert.ok(backup.includes('docker compose --env-file .env.prod -f compose.prod.yaml cp app:/app/var/secrets'));
assert.ok(!backup.includes('prod-compose.sh'), 'backup must not depend on a separate Compose wrapper');

console.log('PASS production Compose commands load .env.prod directly and are consistent across deployment and backup.');

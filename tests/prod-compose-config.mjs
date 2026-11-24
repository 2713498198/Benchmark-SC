import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {existsSync} from 'node:fs';
import {resolve} from 'node:path';

function run(command, args, env = {}) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(command, args, {
      cwd: process.cwd(),
      env: {...process.env, ...env},
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

const wrapper = resolve('scripts/prod-compose.sh');
const bash = process.env.BASH_PATH || (process.platform === 'win32'
  ? ['D:/Git/bin/bash.exe', 'C:/Program Files/Git/bin/bash.exe'].find(existsSync)
  : 'bash');
assert.ok(bash, 'A Bash-compatible shell is required to test the production wrapper');

const valid = await run(bash, [wrapper, 'config', '--services'], {
  PROD_ENV_FILE: '.env.prod.example'
});
assert.equal(valid.code, 0, valid.stderr);
assert.deepEqual(valid.stdout.split(/\r?\n/), ['mysql', 'app', 'caddy']);
const rendered = await run(bash, [wrapper, 'config', '--format', 'json'], {
  PROD_ENV_FILE: '.env.prod.example'
});
assert.equal(rendered.code, 0, rendered.stderr);
const app = JSON.parse(rendered.stdout).services.app;
assert.equal(app.environment.SPRING_PROFILES_ACTIVE, 'prod');
assert.equal(app.environment.CRYPTOBENCH_RUN_MAX_ACTIVE_PER_USER, '2');
assert.equal(app.environment.CRYPTOBENCH_RUN_MAX_CREATED_PER_DAY_PER_USER, '10');
assert.equal(app.environment.CRYPTOBENCH_RUN_MAX_ACTIVE_GLOBAL, '100');

const missing = await run(bash, [wrapper, 'config'], {
  PROD_ENV_FILE: 'target/missing-prod.env'
});
assert.notEqual(missing.code, 0);
assert.match(missing.stderr, /Production environment file not found/);

console.log('PASS production Compose wrapper loads .env.prod and rejects missing configuration.');

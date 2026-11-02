import assert from 'node:assert/strict';
import {access} from 'node:fs/promises';
import {resolve} from 'node:path';
import {apiClient, assertDemoPolicy, composeTest} from './compose-test-utils.mjs';

const test = await composeTest();
const researcher = apiClient(test.base), admin = apiClient(test.base);
const email = `${test.project}@example.com`, password = 'compose-user-password';
const adminEmail = `${test.project}-admin@example.com`, adminPassword = 'compose-admin-password';
let started = false;
try {
  await test.docker(['info', '--format', '{{.ServerVersion}}']);
  started = true;
  await test.compose(['up', '-d', '--build']);
  await test.ready();
  await assertDemoPolicy(researcher, false);
  const user = (await researcher.api('/api/auth/register', {name: 'Persistence test', email, password}, 201)).user;
  await access(resolve(test.dataDir, 'data/cryptobench.mv.db'));

  await test.compose(['restart', 'cryptobench']);
  await test.ready();
  assert.equal((await researcher.api('/api/auth/login', {email, password})).user.id, user.id);
  const originalSession = apiClient(test.base, new Map(researcher.cookies));

  test.env.CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL = adminEmail;
  test.env.CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME = 'Compose Admin';
  test.env.CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD = adminPassword;
  await test.compose(['down']);
  await test.compose(['up', '-d']);
  await test.ready();
  assert.equal((await originalSession.api('/api/auth/me')).user.id, user.id);
  const administrator = (await admin.api('/api/auth/login', {email: adminEmail, password: adminPassword})).user;
  assert.equal(administrator.role, 'admin');
  await admin.api('/api/admin/users');

  for (const key of ['EMAIL', 'NAME', 'PASSWORD']) test.env[`CRYPTOBENCH_BOOTSTRAP_ADMIN_${key}`] = '';
  test.env.CRYPTOBENCH_COMPOSE_DEMO_ENABLED = 'true';
  await test.compose(['up', '-d', '--force-recreate', '--no-deps', 'cryptobench']);
  await test.ready();
  await assertDemoPolicy(apiClient(test.base), true);
  assert.equal((await admin.api('/api/auth/login', {email: adminEmail, password: adminPassword})).user.id, administrator.id);
  const demoCookies = [];
  for (const role of ['researcher', 'admin']) {
    const demo = apiClient(test.base);
    assert.equal((await demo.api('/api/auth/demo', {role})).user.role, role);
    demoCookies.push(new Map(demo.cookies));
  }

  test.env.CRYPTOBENCH_COMPOSE_DEMO_ENABLED = 'false';
  await test.compose(['up', '-d', '--force-recreate', '--no-deps', 'cryptobench']);
  await test.ready();
  await assertDemoPolicy(apiClient(test.base), false);
  for (const cookies of demoCookies) await apiClient(test.base, new Map(cookies)).api('/api/connections', undefined, 401);
  assert.equal((await originalSession.api('/api/auth/me')).user.id, user.id);
  await admin.api('/api/admin/users');

  test.env.CRYPTOBENCH_COMPOSE_DEMO_ENABLED = 'true';
  await test.compose(['up', '-d', '--force-recreate', '--no-deps', 'cryptobench']);
  await test.ready();
  for (const cookies of demoCookies) await apiClient(test.base, new Map(cookies)).api('/api/connections', undefined, 401);
  assert.equal((await apiClient(test.base).api('/api/auth/demo', {role: 'admin'})).user.role, 'admin');
  console.log('PASS Compose loopback binding, demo opt-in/revocation, administrator bootstrap and persistent accounts');
} finally {
  if (started) await test.compose(['down', '--remove-orphans']);
}

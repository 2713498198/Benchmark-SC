import assert from 'node:assert/strict';
import {access} from 'node:fs/promises';
import {resolve} from 'node:path';
import {apiClient, assertDemoRemoved, composeTest} from './compose-test-utils.mjs';

const test = await composeTest();
const researcher = apiClient(test.base);
const email = `${test.project}@example.com`, password = 'compose-user-password';
let started = false;
try {
  await test.docker(['info', '--format', '{{.ServerVersion}}']);
  started = true;
  await test.compose(['up', '-d', '--build']);
  await test.ready();
  await assertDemoRemoved(researcher);
  const user = (await researcher.api('/api/auth/register', {name: 'Persistence test', email, password}, 201)).user;
  await access(resolve(test.dataDir, 'data/cryptobench.mv.db'));

  await test.compose(['restart', 'cryptobench']);
  await test.ready();
  assert.equal((await researcher.api('/api/auth/login', {email, password})).user.id, user.id);
  const originalSession = apiClient(test.base, new Map(researcher.cookies));

  await test.compose(['down']);
  await test.compose(['up', '-d']);
  await test.ready();
  assert.equal((await originalSession.api('/api/auth/me')).user.id, user.id);
  await assertDemoRemoved(apiClient(test.base));
  assert.equal((await originalSession.api('/api/auth/me')).user.id, user.id);

  console.log('PASS Compose loopback binding, removed account-management routes and persistent email/password accounts');
} finally {
  if (started) await test.compose(['down', '--remove-orphans']);
}

import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {access, mkdir, mkdtemp,readFile} from 'node:fs/promises';
import path from 'node:path';
import {availablePort} from './portable-test-utils.mjs';

if (process.platform !== 'win32') {
  console.log('SKIP Windows app-image smoke test: Windows is required');
  process.exit(0);
}

const executable = path.resolve(process.argv[2] || 'output/desktop/CryptoBench/CryptoBench.exe');
await access(executable);
await mkdir('tmp', {recursive: true});
const data = path.resolve(process.argv[3]||await mkdtemp('tmp/desktop-data-'));
await mkdir(data,{recursive:true});
const port = await availablePort();
const serverLog=path.join(data,'server.log');
// This test ends the native process with taskkill. Synchronous H2 writes ensure
// the just-created fixture is durable before that abrupt test-only shutdown.
const child = spawn(executable, [`--server.port=${port}`, `--spring.datasource.url=jdbc:h2:file:${data.replaceAll('\\', '/')}/db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0`,
  `--logging.file.name=${serverLog}`, '--benchmark.demo-enabled=false','--benchmark.legacy-import-enabled=false',
  '--benchmark.mail.worker-enabled=false','--benchmark.mail.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
  '--benchmark.admin.bootstrap.email=','--benchmark.admin.bootstrap.password='], {
  cwd: path.dirname(executable),
  windowsHide: true,
  stdio: 'ignore',
  env: {...process.env, JAVA_HOME: path.join(data, 'no-system-java'), JAVA_TOOL_OPTIONS: '-Djava.awt.headless=true', CRYPTOBENCH_DOCKER_ENABLED: 'false'}
});
let spawnError;
child.on('error', error => { spawnError = error; });

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
    if (spawnError) throw spawnError;
    assert.equal(child.exitCode, null, 'Desktop launcher exited before becoming healthy');
    try {
      response = await fetch(`http://127.0.0.1:${port}/actuator/health`);
      if (response.ok) break;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert.ok(response?.ok, `CryptoBench desktop app did not become healthy on port ${port}`);
  const expectedUrl=`http://127.0.0.1:${port}/#/`;
  let diagnostics='';
  while(Date.now()<deadline){
    try{diagnostics=await readFile(serverLog,'utf8');}catch{}
    if(diagnostics.includes('open '+expectedUrl+' manually'))break;
    await new Promise(resolve=>setTimeout(resolve,200));
  }
  assert.equal(diagnostics.split('Desktop UI ready: '+expectedUrl).length-1,1,'The packaged executable must invoke the desktop entry once with its actual bound port');
  assert.ok(diagnostics.includes('open '+expectedUrl+' manually'),'Headless fallback must retain a usable URL');
  assert.equal((await fetch(`http://127.0.0.1:${port}/`)).status,200,'Web page remains accessible after browser failure');
  if(process.argv[3]){
    const csrfResponse=await fetch(`http://127.0.0.1:${port}/api/auth/csrf`),csrf=await csrfResponse.json();
    const cookie=csrfResponse.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');
    const persisted=process.argv.includes('--expect-persisted-user');
    const auth=await fetch(`http://127.0.0.1:${port}/api/auth/${persisted?'login':'register'}`,{
      method:'POST',headers:{'Content-Type':'application/json',Cookie:cookie,[csrf.headerName]:csrf.token},
      body:JSON.stringify({name:'Desktop fixture',email:'desktop-fixture@example.test',password:'fixture-desktop-2026'})
    });
    assert.equal(auth.status,persisted?200:201,persisted?'An account created before upgrade must survive in the same H2 database':'Create an isolated account for the upgrade persistence check');
  }
  console.log(`PASS Windows app-image: bundled Java with invalid JAVA_HOME, ready URL ${expectedUrl}, headless browser fallback, health/page HTTP 200. Log: ${serverLog}`);
} finally {
  await stop();
}

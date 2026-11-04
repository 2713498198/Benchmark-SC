// Previous releases predate the desktop IPC/default-profile contract. Exercise
// their public APIs and real file database, then let the candidate open that DB.
import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {mkdir,readFile} from 'node:fs/promises';
import path from 'node:path';
import {availablePort} from './portable-test-utils.mjs';
import {desktopPassword,desktopAdmin,verifyAccountsAndReport} from './desktop-auth-fixture.mjs';

const executable=path.resolve(process.argv[2]),directory=path.dirname(executable),port=await availablePort();
const data=path.join(directory,'var/data');
await mkdir(data,{recursive:true});
await mkdir(path.join(directory,'var/logs'),{recursive:true});
const child=spawn(executable,[`--server.port=${port}`,
  `--spring.datasource.url=jdbc:h2:file:${path.join(data,'cryptobench').replaceAll('\\','/')};MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0`,
  `--logging.file.name=${path.join(directory,'var/logs/legacy-startup.log')}`,
  '--benchmark.demo-enabled=false','--benchmark.docker.enabled=false','--benchmark.mail.mode=outbox',
  `--benchmark.password-reset.outbox=${path.join(data,'fixture-outbox.jsonl')}`],{
  cwd:directory,windowsHide:true,stdio:'ignore',env:{...process.env,JAVA_TOOL_OPTIONS:'-Djava.awt.headless=true',
  CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL:desktopAdmin,CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME:'Desktop administrator',CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD:desktopPassword}
});
let error;child.on('error',value=>{error=value;});
try{
  const deadline=Date.now()+60_000;
  let ready=false;
  while(Date.now()<deadline){
    if(error)throw error;
    assert.equal(child.exitCode,null,'Previous version must start successfully');
    try{ready=(await fetch(`http://127.0.0.1:${port}/actuator/health`,{signal:AbortSignal.timeout(2000)})).ok;}catch{}
    if(ready)break;
    await new Promise(resolve=>setTimeout(resolve,200));
  }
  assert.ok(ready,'Previous release health check');
  await verifyAccountsAndReport(`http://127.0.0.1:${port}`,{legacy:true});
  assert.equal(Buffer.from((await readFile(path.join(directory,'var/secrets/mail.key'),'utf8')).trim(),'base64').length,32,'Previous release created a real mail key');
  console.log('PASS previous release: real administrator, research account, private report and mail key fixture');
}finally{
  // Only historical binaries lack graceful IPC. WRITE_DELAY=0 above ensures the
  // fixture's committed rows are durable before test-only forced termination.
  if(child.exitCode===null&&child.pid)spawnSync('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore',timeout:10_000});
  const deadline=Date.now()+5000;
  while(child.exitCode===null&&child.signalCode===null&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,100));
  if(child.exitCode===null&&child.signalCode===null)child.kill('SIGKILL');
  assert.ok(child.exitCode!==null||child.signalCode!==null,'Historical process must be cleaned up');
}

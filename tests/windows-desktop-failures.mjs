import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {createServer} from 'node:net';
import {cp,mkdir,mkdtemp,readFile,readdir,rm,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {desktopAdmin,desktopPassword} from './desktop-auth-fixture.mjs';
import {availablePort} from './portable-test-utils.mjs';

if(process.platform!=='win32'){console.log('SKIP desktop failure scenarios: Windows is required');process.exit(0);}
const source=path.resolve(process.argv[2]||'output/desktop/CryptoBench/CryptoBench.exe');
await mkdir('tmp',{recursive:true});
const fixture=path.resolve(await mkdtemp('tmp/desktop-failures-'));
const directory=path.join(fixture,'中文 failures');
await cp(path.dirname(source),directory,{recursive:true,filter:entry=>entry!==path.join(path.dirname(source),'var')});
const executable=path.join(directory,path.basename(source));
const env={...process.env,JAVA_TOOL_OPTIONS:'-Djava.awt.headless=true',CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL:desktopAdmin,
  CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME:'Failure fixture',CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD:desktopPassword};
async function waitExit(child,timeout=60_000){
  const deadline=Date.now()+timeout;
  while(child.exitCode===null&&child.signalCode===null&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,100));
  if(child.exitCode===null&&child.signalCode===null){child.kill('SIGKILL');throw new Error('Scenario timed out; own fixture process was stopped');}
  return child.exitCode??-1;
}
function launch(args=[],override={}){
  const child=spawn(executable,args,{cwd:fixture,env:{...env,...override},windowsHide:true,stdio:'ignore'});
  child.on('error',error=>{throw error;});return child;
}
async function clearData(){await rm(path.join(directory,'var'),{recursive:true,force:true});}
async function failure(args=[],override={}){
  assert.notEqual(await waitExit(launch(args,override)),0,'Failure must produce a nonzero exit code');
  const text=await readFile(path.join(directory,'var/logs/startup-failure.log'),'utf8');
  assert.ok(!text.includes(desktopPassword),'Failure diagnostics must redact credentials');return text;
}
const listener=createServer();
await new Promise(resolve=>listener.listen(0,'127.0.0.1',resolve));
try{
  const log=await failure([`--server.port=${listener.address().port}`]);
  assert.match(log,/PortInUse|BindException|already in use/i,'Port conflict diagnosis');
}finally{await new Promise(resolve=>listener.close(resolve));}
await clearData();
let log=await failure(['--server.port=0'],{CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL:'',CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME:'',CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD:''});
assert.ok(log.includes('CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL'),'Headless setup explains the required credentials');
await clearData();
await mkdir(path.join(directory,'var/data'),{recursive:true});
await writeFile(path.join(directory,'var/data/cryptobench.mv.db'),'deliberately invalid isolated H2 fixture');
log=await failure(['--server.port=0']);
assert.match(log,/JdbcSQL|Flyway|database/i,'Database startup failure is recorded');
await clearData();

// Real NTFS denial, not a test-only application flag.
await mkdir(path.join(directory,'var'));
const user=(process.env.USERDOMAIN?process.env.USERDOMAIN+'\\':'')+process.env.USERNAME;
const denied=spawnSync('icacls.exe',[path.join(directory,'var'),'/inheritance:r','/grant:r',`${user}:(OI)(CI)RX`],{windowsHide:true,encoding:'utf8'});
assert.equal(denied.status,0,denied.stderr);
try{
  const tempBefore=new Set(await readdir(process.env.TEMP));
  assert.notEqual(await waitExit(launch(['--server.port=0'])),0,'Read-only data directory must fail visibly');
  const added=(await readdir(process.env.TEMP)).filter(name=>name.startsWith('cryptobench-startup-')&&name.endsWith('.log')&&!tempBefore.has(name));
  assert.ok(added.length>0,'Unwritable data uses a temporary diagnostic file');
  assert.ok((await readFile(path.join(process.env.TEMP,added[0]),'utf8')).match(/AccessDenied|access|denied/i));
}finally{
  const restored=spawnSync('icacls.exe',[path.join(directory,'var'),'/grant:r',`${user}:(OI)(CI)F`],{windowsHide:true,encoding:'utf8'});
  assert.equal(restored.status,0,restored.stderr);
}
await clearData();

async function healthy(child,port){
  const deadline=Date.now()+60_000;
  while(Date.now()<deadline){
    assert.ok(child.exitCode===null&&child.signalCode===null,'Crash recovery process must remain alive');
    try{if((await fetch(`http://127.0.0.1:${port}/actuator/health`,{signal:AbortSignal.timeout(1000)})).ok && (await readFile(path.join(directory,'var/logs/cryptobench.log'),'utf8')).includes(`Desktop UI ready: http://127.0.0.1:${port}/#/`))return;}catch{}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw new Error('Crash recovery did not become ready');
}
let child;
try{
  let port=await availablePort();child=launch([`--server.port=${port}`]);await healthy(child,port);
  child.kill('SIGKILL');await waitExit(child);
  port=await availablePort();child=launch([`--server.port=${port}`]);await healthy(child,port);
  assert.equal(await waitExit(launch(['--desktop-command=stop'])),0);
  assert.equal(await waitExit(child),0,'Restart after crash still supports graceful shutdown');
}finally{if(child&&child.exitCode===null&&child.signalCode===null){child.kill('SIGKILL');await waitExit(child);}}
console.log('PASS desktop port conflict, headless setup, corrupted DB, read-only NTFS fallback and crash recovery. Fixtures: '+fixture);

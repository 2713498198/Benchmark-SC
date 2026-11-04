import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {access, cp, mkdir, mkdtemp, readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {availablePort} from './portable-test-utils.mjs';
import {desktopPassword,desktopAdmin,verifyAccountsAndReport} from './desktop-auth-fixture.mjs';

if (process.platform !== 'win32') {
  console.log('SKIP Windows app-image smoke test: Windows is required');
  process.exit(0);
}

const source = path.resolve(process.argv[2] || 'output/desktop/CryptoBench/CryptoBench.exe');
await access(source);
await mkdir('tmp', {recursive:true});
const inPlace = process.argv.includes('--in-place');
const dockerTest=process.argv.includes('--docker');
assert.ok(!(inPlace&&dockerTest),'Docker preparation test requires an isolated copy');
const workspace = path.resolve(await mkdtemp('tmp/desktop-check-'));
// Georgian is outside both Chinese CP936 and English CP1252: catch native
// launcher path loss on developer machines as well as English Windows runners.
const directory = inPlace ? path.dirname(source) : path.join(workspace, '中文 ქართული portable app');
if (!inPlace) await cp(path.dirname(source), directory, {recursive:true});
const executable = path.join(directory, path.basename(source));
const dockerVersion='0.0.'+Date.now();
const dockerImages=['cryptobench-agent-proxy:','cryptobench-mock-agent:','cryptobench-model-gateway:'].map(name=>name+dockerVersion);
async function native(file,args){
  const process=spawn(file,args,{windowsHide:true,stdio:'inherit'});
  await new Promise((resolve,reject)=>{process.on('error',reject);process.on('exit',code=>code===0?resolve():reject(new Error(`${path.basename(file)} exited ${code}`)));});
}
if(dockerTest){
  const infoFile=path.join(directory,'support/build-info.json');
  const info=JSON.parse((await readFile(infoFile,'utf8')).replace(/^\uFEFF/,''));
  info.version=dockerVersion;
  await writeFile(infoFile,JSON.stringify(info));
  await mkdir(path.join(directory,'var/config'),{recursive:true});
  await writeFile(path.join(directory,'var/config/desktop.properties'),'benchmark.gateway.enabled=false\n# preserved fixture setting\n');
  await native('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(directory,'support/prepare-desktop-docker.ps1'),'-IncludeGateway']);
  assert.ok((await readFile(path.join(directory,'var/config/desktop.properties'),'utf8')).includes('# preserved fixture setting'));
}
const port = await availablePort();
const configFile=path.join(directory,'var/config/desktop.properties');
await mkdir(path.dirname(configFile),{recursive:true});
const priorConfig=await readFile(configFile,'utf8').catch(()=> '');
await writeFile(configFile,priorConfig.split(/\r?\n/).filter(line=>!/^\s*server\.port\s*[:=]/.test(line)).join('\n')+`\nserver.port=${port}\n`);
const log = path.join(directory, 'var/logs/cryptobench.log');
const keyFile = path.join(directory, 'var/secrets/mail.key');
const persisted = process.argv.includes('--expect-persisted-user');
const password = desktopPassword;
const beforeLog = await readFile(log,'utf8').catch(()=> '');
let previousKey;
if (persisted) previousKey = await readFile(keyFile, 'utf8');
const env = {...process.env, JAVA_HOME:path.join(workspace,'no-system-java'), JAVA_TOOL_OPTIONS:'-Djava.awt.headless=true',
  CRYPTOBENCH_BOOTSTRAP_ADMIN_EMAIL:desktopAdmin, CRYPTOBENCH_BOOTSTRAP_ADMIN_NAME:'Desktop administrator',
  CRYPTOBENCH_BOOTSTRAP_ADMIN_PASSWORD:password};
delete env.SERVER_PORT;delete env.PORT;
// No database, key or log override: this exercises the delivered defaults.
const options = {cwd:workspace, windowsHide:true, stdio:'ignore', env};
const launchOptions = {...options, stdio:['ignore','pipe','pipe']};
const shortcutIndex=process.argv.indexOf('--shortcut');
const shortcut=shortcutIndex>=0?process.argv[shortcutIndex+1]:null;
assert.ok(shortcutIndex<0||(inPlace&&shortcut),'Shortcut launch requires an installed image and shortcut path');
const child = shortcut
  ? spawn('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',path.resolve('tests/windows-shortcut-launch.ps1'),'-Shortcut',shortcut,'-Executable',executable],launchOptions)
  : spawn(executable, [], launchOptions);
let spawnError;
child.on('error', error => { spawnError = error; });
let nativeOutput='';
for(const stream of [child.stdout,child.stderr]){
  stream.setEncoding('utf8');
  stream.on('data',text=>{nativeOutput=(nativeOutput+text).slice(-16000);});
}

async function waitExit(process, timeout=60_000) {
  const deadline=Date.now()+timeout;
  while(process.exitCode===null && process.signalCode===null && Date.now()<deadline) await new Promise(resolve=>setTimeout(resolve,100));
  assert.ok(process.exitCode!==null||process.signalCode!==null,'Native process must terminate within the deadline');
  return process.exitCode??-1;
}
async function command(name) {
  const control=spawn(executable,[`--desktop-command=${name}`],options);
  let error; control.on('error',value=>{error=value;});
  const code=await waitExit(control);
  if(error)throw error;
  assert.equal(code,0,`Desktop command ${name} succeeds`);
}

try {
  const deadline=Date.now()+60_000;
  let ready=false,diagnostics='';
  while(Date.now()<deadline){
    if(spawnError)throw spawnError;
    assert.equal(child.exitCode,null,`Desktop exited before readiness; inspect ${log}`);
    try {
      diagnostics=(await readFile(log,'utf8')).slice(beforeLog.length);
      ready=diagnostics.includes(`Desktop UI ready: http://127.0.0.1:${port}/#/`) && (await fetch(`http://127.0.0.1:${port}/actuator/health`,{signal:AbortSignal.timeout(2000)})).ok;
      if(ready&&diagnostics.includes(' manually'))break;
    }catch{}
    await new Promise(resolve=>setTimeout(resolve,200));
  }
  assert.ok(ready,'Delivered desktop defaults must become ready');
  assert.equal(diagnostics.split('Desktop UI ready: ').length-1,1,'Exactly one readiness event per launch');
  assert.ok(diagnostics.includes(' manually'),'Browser failure leaves a manual URL in the default log');
  assert.ok(!diagnostics.includes(password),'Bootstrap password must not be logged');
  const key=await readFile(keyFile,'utf8');
  assert.equal(Buffer.from(key.trim(),'base64').length,32,'Default private mail key is generated');
  if(persisted)assert.equal(key,previousKey,'Upgrade keeps the real mail key');
  assert.equal((await fetch(`http://127.0.0.1:${port}/`)).status,200);
  const api=await verifyAccountsAndReport(`http://127.0.0.1:${port}`,{persisted});
  if(dockerTest){
    const runtime=await api('/api/agent/runtime');
    assert.equal(runtime.body.dockerAvailable,true,JSON.stringify(runtime.body));
    assert.ok(runtime.body.allowedImages.some(image=>image.name===dockerImages[1]&&image.available));
    const created=await api('/api/runs',{source:'docker',image:dockerImages[1],kind:'evaluation',datasetId:'mini-spn',version:'1.0.0',scope:'sample',budget:'standard',model:'GPT-4.1',agentName:'Packaged Docker fixture'});
    assert.equal(created.status,202,JSON.stringify(created.body));
    const runId=created.body.runId||created.body.id;
    let run;
    const deadline=Date.now()+120_000;
    while(Date.now()<deadline){
      run=(await api('/api/runs/'+runId)).body;
      if(['completed','failed','timeout','cancelled'].includes(run.status))break;
      await new Promise(resolve=>setTimeout(resolve,500));
    }
    if(run.status!=='completed')await api('/api/runs/'+runId+'/cancel',{});
    assert.equal(run.status,'completed',JSON.stringify(run));
    assert.equal(run.progress.completed,8);
    const report=(await api('/api/reports/'+run.reportId)).body;
    assert.equal(report.verified,true);
    assert.equal(report.source,'docker');
    assert.equal(report.score,100);
    console.log('PASS packaged Docker preparation and real sandbox report: '+run.reportId);
  }
  await command('status');
  const duplicate=spawn(executable,[],options);
  assert.equal(await waitExit(duplicate),0,'Repeated launch activates the original instance');
  assert.equal((await fetch(`http://127.0.0.1:${port}/actuator/health`)).status,200);
  await command('stop');
  assert.equal(await waitExit(child),0,'Graceful stop exits without taskkill');
  await assert.rejects(fetch(`http://127.0.0.1:${port}/actuator/health`,{signal:AbortSignal.timeout(1000)}));
  await assert.rejects(access(path.join(directory,'var/run/instance.properties')));
  console.log(`PASS desktop defaults, real administrator, private key, demo disabled, independent cwd, single instance and graceful exit. Log: ${log}`);
} catch(error) {
  // Native JVM loading can fail before logback or startup-failure.log exists.
  // Always leave an artifact for this case, even for a GUI launcher with no output.
  const diagnostic=JSON.stringify({executable,cwd:workspace,exitCode:child.exitCode,
    signal:child.signalCode,error:error.message,nativeOutput},null,2).replaceAll(password,'[REDACTED]');
  await writeFile(path.join(workspace,'launch-failure.log'),diagnostic);
  console.error(diagnostic);
  throw error;
} finally {
  if(child.exitCode===null && child.pid){
    try{await command('stop');await waitExit(child,5000);}catch{}
    if(child.exitCode===null)spawnSync('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{stdio:'ignore',timeout:5000});
    if(child.exitCode===null)child.kill('SIGKILL');
  }
  if(dockerTest)for(const image of dockerImages)await native('docker.exe',['image','rm',image]);
}

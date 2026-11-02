import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createWriteStream,existsSync} from 'node:fs';
import {mkdir,mkdtemp,readFile} from 'node:fs/promises';
import path from 'node:path';
import {availablePort} from './portable-test-utils.mjs';

// The application process is always started with a fresh database and a local test outbox.
const jar=path.resolve('target/cryptobench-1.0.0-SNAPSHOT.jar');assert.ok(existsSync(jar),'Build the application JAR first');
await mkdir('target',{recursive:true});const temporary=await mkdtemp(path.resolve('target/workflow-integration-'));
const port=await availablePort(),base='http://127.0.0.1:'+port,outbox=path.join(temporary,'outbox.jsonl');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const logPath=path.join(temporary,'server.log'),log=createWriteStream(logPath);
const flags=['-jar',jar,'--server.address=127.0.0.1','--server.port='+port,'--spring.profiles.active=local',
 '--spring.datasource.url=jdbc:h2:file:'+path.join(temporary,'db').replaceAll('\\','/')+';MODE=MySQL;DATABASE_TO_LOWER=TRUE',
 '--spring.datasource.username=sa','--spring.datasource.password=','--benchmark.legacy-import-enabled=false','--benchmark.demo-enabled=false','--benchmark.secure-cookie=false',
 '--benchmark.docker.enabled=false','--benchmark.gateway.enabled=false','--benchmark.mail.mode=outbox','--benchmark.mail.outbox='+outbox,
 '--benchmark.mail.base-url='+base,'--benchmark.mail.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=','--benchmark.mail.poll-ms=100',
 '--benchmark.admin.bootstrap.email=testadmin@example.test','--benchmark.admin.bootstrap.name=Browser Test Admin','--benchmark.admin.bootstrap.password=fixture-admin-2026'];
const backend=spawn(java,flags,{windowsHide:true,stdio:['ignore','pipe','pipe']});backend.stdout.pipe(log);backend.stderr.pipe(log);
let backendError;backend.on('error',error=>backendError=error);
let testProcess;
try{
 const deadline=Date.now()+90_000;let healthy=false;
 while(Date.now()<deadline){if(backendError)throw backendError;if(backend.exitCode!==null)throw Error('Isolated backend exited: '+backend.exitCode);try{healthy=(await fetch(base+'/actuator/health')).ok;}catch{}if(healthy)break;await new Promise(resolve=>setTimeout(resolve,500));}
 assert.ok(healthy,'Isolated backend did not become healthy');
 testProcess=spawn(process.execPath,['tests/workflow-integration-browser.mjs'],{windowsHide:true,stdio:'inherit',env:{...process.env,TEST_ISOLATED:'1',TEST_URL:base,TEST_ADMIN_EMAIL:'testadmin@example.test',TEST_ADMIN_PASSWORD:'fixture-admin-2026',TEST_MAIL_OUTBOX:outbox}});
 const code=await new Promise((resolve,reject)=>{testProcess.once('error',reject);testProcess.once('exit',resolve);});
 assert.equal(code,0,'Real browser workflow failed');console.log('Isolated workflow server log: '+logPath);
}catch(error){console.error('Isolated server diagnostics: '+logPath);throw error;}
finally{
 if(testProcess?.exitCode===null)testProcess.kill();
 if(backend.exitCode===null){backend.kill();await new Promise(resolve=>{const timeout=setTimeout(resolve,5000);backend.once('exit',()=>{clearTimeout(timeout);resolve();});});}
 backend.stdout.unpipe(log);backend.stderr.unpipe(log);await new Promise(resolve=>log.end(resolve));
}

import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import path from 'node:path';
import {chromium} from 'playwright';

// Serve static files only; intercept every API call. No application database or real mailbox is used.
const root=path.resolve('src/main/resources/static');
const server=createServer(async(req,res)=>{try{const relative=decodeURIComponent(new URL(req.url,'http://localhost').pathname),file=path.resolve(root,'.'+(relative==='/'?'/index.html':relative));if(!file.startsWith(root+path.sep))throw Error();const body=await readFile(file);const ext=path.extname(file);res.writeHead(200,{'Content-Type':({'.js':'text/javascript','.css':'text/css','.html':'text/html','.json':'application/json','.svg':'image/svg+xml'})[ext]||'application/octet-stream'});res.end(body);}catch{res.writeHead(404);res.end();}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base='http://127.0.0.1:'+server.address().port;
const installed='C:/Program Files/Google/Chrome/Application/chrome.exe';
const browser=await chromium.launch({headless:true,...((process.env.CHROME_PATH||existsSync(installed))?{executablePath:process.env.CHROME_PATH||installed}:{})});
const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],requests=[];
page.on('pageerror',e=>errors.push(e.message));
let user=null,bootstrapFailure=false,runNetworkFailure=false,invites=[];
let gatewayFixture={enabled:false,providers:[]},dockerFixture={dockerEnabled:false,dockerAvailable:false,allowedImages:[],limits:{}};
const makeReport=(id,published=true)=>({id,ownerId:'owner',published,synced:false,kind:'evaluation',scope:'full',source:'local',datasetId:'mini-spn',version:'1.0.0',budget:'standard',agent:'Fixture Agent',model:'Fixture Model',score:100,passed:8,total:8,time:1,cost:0.1,date:'2026-11-01',seed:987,evaluatorVersion:'fixture-evaluator-2.1',pricingVersion:'fixture-prices',categories:[{name:'全部任务',passed:8,total:8}],results:[{taskId:'fixture-task',status:'passed',passed:true,elapsedMs:12,costUsd:0.1}]});
const reports={report_public:makeReport('report_public'),private:makeReport('private',false),slow:makeReport('slow'),fast:makeReport('fast')};
Object.assign(reports.report_public,{executorType:'bridge',usageSource:'device_gateway_reported',cost:null,costStatus:'partial',costStatuses:['missing_price','missing_usage'],costCurrency:'USD'});
let currentRun={runId:'run_fixture',kind:'evaluation',source:'local',datasetId:'mini-spn',version:'1.0.0',budget:'standard',scope:'full',model:'Fixture Model',seed:987,status:'paused',pauseReason:'credentials_required',errorMessage:'请输入凭证后继续',canResume:true,credentialsRequired:['main'],config:{agentName:'Saved Agent',endpoint:'http://127.0.0.1:8123',participants:[{key:'main',label:'Agent',model:'Fixture Model',credentialRequired:true}]},tasks:[{taskId:'task-unconfirmed',status:'interrupted',attempt:1}],progress:{completed:1,total:8,percent:12.5}};
await page.route('**/api/**',async route=>{
 const req=route.request(),url=new URL(req.url()),p=url.pathname,method=req.method(),body=req.postDataJSON?.();requests.push({p,method,body});
 const json=(data,status=200)=>route.fulfill({status,json:data});
 if(p==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'fixture'});
 if(p==='/api/bootstrap')return bootstrapFailure?json({error:'bootstrap offline'},503):json({user,reports:user?.id==='owner'?[reports.private]:[],connections:[],customDatasets:[],publicReports:[],demoEnabled:true});
 if(p==='/api/auth/login')return json({});
 if(p==='/api/auth/logout'){user=null;return json({});}
 if(p==='/api/reports/public')return json([]);
 if(p.startsWith('/api/reports/')){
  const [,id,action]=p.match(/^\/api\/reports\/([^/]+)(?:\/(\w+))?$/)||[];
  if(id==='offline')return json({error:'temporary report failure'},503);
  const report=reports[id];if(!report)return json({error:'missing'},404);
  if(!report.published&&user?.id!==report.ownerId)return json({error:'private'},user?404:401);
  if(action==='sync'){report.synced=true;return json(report);}
  if(action==='publish'){report.published=true;return json(report);}
  if(id==='slow')await new Promise(resolve=>setTimeout(resolve,350));
  try{return await json({...report,permissions:{isOwner:user?.id===report.ownerId,canSync:user?.id===report.ownerId&&!report.synced,canPublish:user?.id===report.ownerId&&report.synced&&!report.published}});}catch{return;}
 }
 if(p==='/api/agent/runtime')return json(dockerFixture);
 if(p==='/api/agent/gateway')return json(gatewayFixture);
 if(p==='/api/agent/check')return json({health:{version:'fixture-agent'},capabilities:{models:['Fixture Model']}});
 if(p==='/api/runs'){
  if(method==='GET')return json(user?[currentRun]:[]);
  currentRun={...currentRun,runId:'run_created',source:body.source,status:'queued',seed:body.seed,credentialsRequired:[],config:{...currentRun.config,executorType:'server',seed:body.seed}};
  return json(currentRun);
 }
 if(p.startsWith('/api/runs/')){
  if(runNetworkFailure)return json({error:'network interrupted'},503);
  if(p.endsWith('/logs'))return json([{sequence:1,level:'INFO',message:'fixture-log-once'}]);
  if(p.endsWith('/resume')){currentRun={...currentRun,status:'running',credentialsRequired:[]};return json(currentRun);}
  if(p.endsWith('/retry')){currentRun={...currentRun,runId:'run_retried',status:'queued',credentialsRequired:[]};return json(currentRun);}
  if(p.endsWith('/cancel')){currentRun={...currentRun,status:'cancelled'};return json(currentRun);}
  return json(currentRun);
 }
 if(p==='/api/admin/mail/status')return json({mode:'http',ready:true});
 if(p==='/api/admin/invitations'){
  if(method==='POST'){const row={id:'invite_fixture',email:body.email,name:body.name,status:'pending',createdAt:'2026-11-01',expiresAt:'2026-11-03',deliveryStatus:{state:'queued',transport:'http',attempts:0}};invites=[row];return json(row,202);}
  return json({items:invites,total:invites.length,page:0,size:20});
 }
 if(p.endsWith('/resend')){invites[0].deliveryStatus.state='queued';return json(invites[0],202);}
 if(p.endsWith('/revoke')){invites[0].status='revoked';invites[0].deliveryStatus.state='cancelled';return json(invites[0]);}
 if(p==='/api/admin/users'||p==='/api/admin/audit')return json({items:[],total:0,page:0,size:20});
 if(p==='/api/admin/pricing')return json([]);
 if(p==='/api/pricing/active')return json({version:'fixture-default',status:'config',prices:[]});
 return json({error:'unhandled fixture: '+p},404);
});
let visits=0;
async function go(route){await page.goto(base+'/?test='+(++visits)+'#'+route);await page.waitForSelector('#main');}
async function navigate(route){await page.evaluate(route=>{location.hash=route;},route);}
async function confirm(){await page.locator('#modal .dialog-actions button').click();}
try{
 await go('/report/report_public');await page.locator('[data-report-json]').waitFor();
 assert.equal(await page.locator('[data-sync-report]').count(),0,'public visitors do not get mutation controls');
 assert.match(await page.locator('[data-report-location]').innerText(),/历史执行设备（已停用）/);
 assert.match(await page.locator('[data-report-usage-source]').innerText(),/设备网关上报/);
 assert.match(await page.locator('[data-report-cost-status]').innerText(),/缺少模型价格.*缺少 Token 用量/);
 assert.match(await page.locator('[data-report-pricing]').innerText(),/fixture-prices.*USD/);
 await page.locator('.report-config summary').click();assert.match(await page.locator('.report-config').innerText(),/987.*fixture-evaluator-2\.1/s);
 const download=page.waitForEvent('download');await page.locator('[data-report-json]').click();const exported=JSON.parse(await readFile(await(await download).path(),'utf8'));
 assert.equal(exported.seed,987);assert.equal('permissions' in exported,false,'viewer permissions must not enter exports');
 assert.match(await page.locator('.stat-card').filter({hasText:'平均每题耗时'}).innerText(),/未知/,'Incomplete historical results must not present legacy cumulative time as an average');
 assert.equal(exported.time,1);assert.equal('averageTimeSeconds' in exported,false,'Derived display fields must not enter historical JSON');
 const historical=makeReport('legacy_complete');historical.total=2;historical.passed=1;delete historical.categories;
 historical.results=[{taskId:'main::1',elapsedMs:1000,passed:true,category:'分类一'},{taskId:'main::2',elapsedMs:3000,passed:false,category:'分类二'}];reports.legacy_complete=historical;
 await navigate('/report/legacy_complete');await page.locator('[data-report-json]').waitFor();
 assert.match(await page.locator('.stat-card').filter({hasText:'平均每题耗时'}).innerText(),/2 s/);
 assert.match(await page.locator('.stat-card').filter({hasText:'平均每题耗时'}).innerText(),/累计 4 s/);
 assert.match(await page.locator('.report-grid table').innerText(),/分类一/);assert.match(await page.locator('.report-grid table').innerText(),/0\.0%/);
 reports.legacy_pk={...makeReport('legacy_pk'),kind:'pk',total:2,competitors:[{label:'A',passed:1,total:2},{label:'B',passed:0,total:2}],categories:[{name:'全部任务',passed:1,total:2}],results:[]};
 await navigate('/report/legacy_pk');await page.locator('[data-report-json]').waitFor();const oldPkTable=await page.locator('.report-grid table').innerText();assert.match(oldPkTable,/1 \/ 2 · 50\.0%/);assert.match(oldPkTable,/0 \/ 2 · 0\.0%/);
 assert.equal(JSON.parse(await page.evaluate(()=>localStorage.getItem('cryptobench.v1'))).reports.length,0,'public detail must not enter my reports');
 await navigate('/report/private');await page.locator('[data-report-error]').waitFor();assert.match(await page.locator('[data-report-error]').innerText(),/登录/);
 await navigate('/report/offline');await page.locator('[data-report-error]').waitFor();assert.match(await page.locator('[data-report-error]').innerText(),/temporary report failure/);
 const slow=page.waitForRequest(r=>r.url().endsWith('/api/reports/slow'));await navigate('/report/slow');await slow;await navigate('/report/fast');await page.locator('[data-report-json]').waitFor();await page.waitForTimeout(450);assert.match(await page.locator('.page-header').innerText(),/fast/);
 user={id:'owner',name:'Owner',role:'admin'};await go('/report/private');await page.locator('[data-sync-report]').waitFor();
 await page.locator('[data-sync-report]').click();await confirm();await page.locator('[data-publish-report]').waitFor();
 await page.locator('[data-publish-report]').click();await confirm();await page.waitForFunction(()=>!document.querySelector('[data-publish-report]'));
 assert.ok(requests.filter(r=>r.p==='/api/reports/private'&&r.method==='GET').length>=3,'mutations re-read permissions');
 assert.ok(!requests.some(r=>r.p==='/api/reports'&&r.method==='POST'),'failed sync never silently uploads a replacement');
 await go('/evaluate?run=run_fixture');await page.locator('[data-run-paused]').waitFor();assert.equal(await page.locator('[data-run-seed]').innerText(),'987');
 assert.match(await page.locator('[data-interrupted-tasks]').innerText(),/task-unconfirmed/);assert.match(await page.locator('[data-interrupted-tasks]').innerText(),/可能再次计费/);
 await page.locator('[data-run-credential="main"]').fill('ephemeral-secret');await page.locator('[data-resume-run]').click();await page.waitForFunction(()=>!document.querySelector('[data-run-paused]'));
 assert.deepEqual(requests.find(r=>r.p.endsWith('/resume')).body,{credentials:{main:{token:'ephemeral-secret'}}});
 assert.ok(!(await page.evaluate(()=>localStorage.getItem('cryptobench.v1'))).includes('ephemeral-secret'));
 await navigate('/workspace');await page.locator('[data-logout]').waitFor();await navigate('/evaluate?run=run_fixture');await page.locator('[data-run-progress]').waitFor();
 runNetworkFailure=true;await page.waitForFunction(()=>document.body.innerText.includes('network interrupted'));runNetworkFailure=false;await page.getByRole('button',{name:'重新连接',exact:true}).click();await page.waitForFunction(()=>!document.body.innerText.includes('network interrupted'));
 assert.equal(await page.getByText('[INFO] fixture-log-once',{exact:true}).count(),1,'shared tracker deduplicates logs across navigation');
 const cancellations=requests.filter(r=>r.p.endsWith('/cancel')).length;await page.locator('[data-new-run]').click();await page.locator('[data-form="endpoint"]').waitFor();
 assert.equal(await page.locator('[data-form="endpoint"]').inputValue(),'http://127.0.0.1:8123');assert.equal(requests.filter(r=>r.p.endsWith('/cancel')).length,cancellations,'new form does not cancel the running job');
 assert.equal(await page.locator('[aria-label="访问凭证"]').inputValue(),'');
 await page.locator('[aria-label="访问凭证"]').fill('leave-page-secret');await navigate('/workspace');await navigate('/evaluate?new=1');await page.locator('[aria-label="访问凭证"]').waitFor();assert.equal(await page.locator('[aria-label="访问凭证"]').inputValue(),'');
 currentRun={...currentRun,status:'failed',credentialsRequired:['main'],config:{...currentRun.config,gateway:{credentialRequired:true}}};await go('/evaluate?run=run_fixture');await page.locator('[data-retry-run]').waitFor();await page.locator('[data-run-credential="main"]').fill('retry-secret');await page.locator('[data-run-credential="gateway"]').fill('retry-gateway-key');await page.locator('[data-retry-run]').click();await page.waitForURL(/run=run_retried/);
 assert.deepEqual(requests.find(r=>r.p.endsWith('/retry')).body,{credentials:{main:{token:'retry-secret'},gateway:{token:'retry-gateway-key'}}});assert.equal(await page.locator('[data-run-seed]').innerText(),'987');
 await go('/admin');await page.getByRole('button',{name:'管理员邀请',exact:true}).click();await page.locator('#invitation-email').fill('invitee@example.com');await page.locator('#invitation-name').fill('Invitee');await page.getByRole('button',{name:'发送邀请',exact:true}).click();await page.getByRole('button',{name:'重发',exact:true}).waitFor();
 await page.getByRole('button',{name:'重发',exact:true}).click();assert.match(await page.locator('#modal').innerText(),/之前的邀请链接立即失效/);await confirm();await page.getByRole('button',{name:'撤销',exact:true}).click();await confirm();await page.waitForFunction(()=>!document.querySelector('[aria-label="管理员邀请"] table button'));
 assert.match(await page.locator('[aria-label="管理员邀请"]').innerText(),/已撤销/);
 await page.getByRole('button',{name:'模型价格',exact:true}).click();await page.locator('#pricing-version').waitFor();
 await page.getByRole('button',{name:'测评集管理',exact:true}).click();assert.ok(await page.locator('textarea').count());

 gatewayFixture={enabled:true,providers:[{id:'provider_fixture',name:'Fixture provider',models:['Fixture Model'],priceModelIds:{'Fixture Model':'fixture model'}}]};
 dockerFixture={dockerEnabled:true,dockerAvailable:true,allowedImages:[{name:'fixture:local',available:true}],limits:{}};
 async function finishCreation(){
  await page.locator('[data-check-connection]').click();await page.locator('#connection-status').filter({hasText:'已连接'}).waitFor();await page.locator('[data-eval-next]').click();await page.locator('[data-eval-next]').click();
  assert.match(await page.locator('[data-confirm-location]').innerText(),/本机执行/);
  const created=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/runs'&&r.request().method()==='POST');await page.locator('[data-eval-next]').click();await created;return requests.filter(r=>r.p==='/api/runs'&&r.method==='POST').at(-1).body;
 }
 await go('/workspace');assert.equal(await page.locator('a[href="#/workspace?tab=devices"]').count(),0);
 await go('/evaluate?new=1');assert.equal(await page.locator('#executor-type,#execution-device,#device-agent').count(),0);
 await page.locator('[data-form="endpoint"]').fill('http://127.0.0.1:8124');await page.locator('[aria-label="访问凭证"]').fill('local-ephemeral');
 const localBody=await finishCreation();assert.equal(localBody.source,'local');assert.equal(localBody.endpoint,'http://127.0.0.1:8124');assert.equal(localBody.token,'local-ephemeral');assert.ok(!('executorType' in localBody)&&!('bridgeId' in localBody)&&!('localAgentId' in localBody));
 assert.deepEqual(requests.filter(r=>r.p==='/api/agent/check').at(-1).body,{source:'local',endpoint:'http://127.0.0.1:8124',token:'local-ephemeral'});
 await go('/evaluate?new=1');await page.locator('#execution-source').selectOption('docker');await page.locator('#docker-image').selectOption('fixture:local');await page.locator('#gateway-provider').selectOption('provider_fixture');await page.locator('#gateway-key').fill('gateway-ephemeral');
 const gatewayBody=await finishCreation();assert.deepEqual(gatewayBody.gateway,{providerId:'provider_fixture',apiKey:'gateway-ephemeral'});assert.ok(!(await page.evaluate(()=>localStorage.getItem('cryptobench.v1'))).includes('gateway-ephemeral'));
 assert.equal(gatewayBody.source,'docker');assert.equal(gatewayBody.image,'fixture:local');assert.ok(!('executorType' in gatewayBody)&&!('bridgeId' in gatewayBody)&&!('localAgentId' in gatewayBody));
 assert.deepEqual(requests.filter(r=>r.p==='/api/agent/check').at(-1).body,{source:'docker',image:'fixture:local'});
 // Historical records remain viewable without exposing removed execution actions.
 currentRun={...currentRun,runId:'run_legacy_device',status:'paused',pauseReason:'executor_removed',canResume:false,config:{...currentRun.config,executorType:'bridge'}};
 await go('/evaluate?run=run_legacy_device');await page.locator('[data-executor-removed]').waitFor();
 assert.equal(await page.locator('[data-resume-run],[data-retry-run],[data-run-credentials]').count(),0);assert.equal(await page.locator('[data-cancel-run]').count(),1);
 await page.locator('[data-cancel-run]').click();await page.waitForFunction(()=>!document.querySelector('[data-cancel-run]'));
 assert.equal(await page.locator('[data-retry-run]').count(),0);await page.locator('[data-new-run]').click();await page.locator('#execution-source').waitFor();assert.equal(await page.locator('#execution-source').inputValue(),'docker');
 assert.equal(await page.locator('#executor-type,#execution-device,#device-agent').count(),0);
 await navigate('/evaluate?new=1');await page.locator('#execution-source').selectOption('local');await page.locator('[aria-label="访问凭证"]').fill('previous-account-secret');
 await navigate('/workspace');await page.locator('[data-logout]').click();await page.waitForURL(/#\/$/);user={id:'second-owner',name:'Second Owner',role:'researcher'};
 await navigate('/login');await page.locator('[name="email"]').fill('second@example.test');await page.locator('[name="password"]').fill('test-login-password');await page.locator('#auth-form [type="submit"]').click();await page.waitForURL(/#\/workspace$/);
 await navigate('/evaluate?new=1');await page.locator('[aria-label="访问凭证"]').waitFor();assert.equal(await page.locator('[aria-label="访问凭证"]').inputValue(),'');assert.ok(!(await page.evaluate(()=>Object.values(localStorage).join(''))).includes('previous-account-secret'));
 assert.ok(!requests.some(r=>r.p.startsWith('/api/bridges')||r.p.startsWith('/api/bridge-device')),'removed device APIs must never be requested');
 assert.deepEqual(errors,[]);
 console.log('PASS reports, route races, export permissions, run recovery/retry, ephemeral credentials, administrator flows, local execution, removed executor compatibility and model gateway request boundaries.');
}catch(error){console.error({pageErrors:errors,screen:await page.locator('body').innerText(),requests:requests.map(({p,method})=>({p,method})).slice(-12)});throw error;}
finally{await browser.close();await new Promise(resolve=>server.close(resolve));}

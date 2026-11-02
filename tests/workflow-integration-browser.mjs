import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {createServer} from 'node:http';
import {mkdir,readFile} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {chromium} from 'playwright';
import {secondsText} from '../src/main/resources/static/js/report-metrics.js';

// This test writes fixtures. It intentionally has no default application URL or credentials.
assert.equal(process.env.TEST_ISOLATED,'1','Start a separate test database, then set TEST_ISOLATED=1');
assert.ok(process.env.TEST_URL&&process.env.TEST_ADMIN_EMAIL&&process.env.TEST_ADMIN_PASSWORD,'TEST_URL and isolated administrator credentials are required');
const base=process.env.TEST_URL.replace(/\/$/,''),host=new URL(base).hostname;
assert.ok(['127.0.0.1','localhost','[::1]'].includes(host),'The integration fixture must run on loopback');
const id='browser-'+randomUUID(),priceVersion='browser-prices-'+randomUUID();
const dataset={schema:'cryptobench-custom-dataset-v1',id,name:'Browser numeric fixture',type:'分组密码',algorithm:'Teaching fixture',version:'1.0.0',description:'Isolated browser integration fixture',difficulty:'入门',tasks:[{id:'task-001',question:'Return the public input value.',input:{value:7},category:'数值验证',verification:{type:'numeric-v1',expected:7,absoluteTolerance:0,relativeTolerance:0}}]};
dataset.tasks.push({...structuredClone(dataset.tasks[0]),id:'task-002',verification:{type:'numeric-v1',expected:8,absoluteTolerance:0,relativeTolerance:0}},
 {...structuredClone(dataset.tasks[0]),id:'task-003',category:'边界验证'});
const agent=createServer(async(req,res)=>{const send=(body,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(body));};
 if(req.url==='/health')return send({status:'ok',version:'browser-fixture-1'});
 if(req.url==='/capabilities')return send({protocolVersion:'1.0',agentVersion:'browser-fixture-1',models:['mock-model','mock-model-b'],tools:[],datasets:[{id,version:'1.0.0'}]});
 if(req.url==='/analyze'&&req.method==='POST'){let body='';for await(const chunk of req)body+=chunk;const request=JSON.parse(body);await new Promise(resolve=>setTimeout(resolve,request.participant==='b'?100:1200));return send({status:'completed',artifact:{answer:request.participant==='b'?8:7},evidence:[],usage:{agentCalls:1,inputTokens:10,outputTokens:10},elapsedMs:5});}
 send({error:'missing'},404);
});
await new Promise(resolve=>agent.listen(0,'127.0.0.1',resolve));
const endpoint='http://127.0.0.1:'+agent.address().port;
const installed='C:/Program Files/Google/Chrome/Application/chrome.exe';
let browser,context,page;const errors=[];
const navigate=route=>page.goto(base+'/#'+route);
async function confirm(){await page.locator('#modal .dialog-actions button').click();await page.locator('#modal').waitFor({state:'hidden'});}
try{
 browser=await chromium.launch({headless:true,...((process.env.CHROME_PATH||existsSync(installed))?{executablePath:process.env.CHROME_PATH||installed}:{})});
 context=await browser.newContext({viewport:{width:1440,height:1000}});page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
 await navigate('/login');await page.locator('#auth-form [name="email"]').fill(process.env.TEST_ADMIN_EMAIL);await page.locator('#auth-form [name="password"]').fill(process.env.TEST_ADMIN_PASSWORD);await page.locator('#auth-form [type="submit"]').click();await page.waitForURL(/#\/admin$/);
 await page.locator('#dataset-json').fill(JSON.stringify(dataset));await page.locator('[data-validate]').click();await page.locator('[data-publish-dataset]').waitFor();
 const publishing=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/datasets/custom'&&r.request().method()==='POST');await page.locator('[data-publish-dataset]').click();assert.equal((await publishing).status(),201);
 await page.getByRole('button',{name:'模型价格',exact:true}).click();await page.getByRole('button',{name:'复制当前完整价格表',exact:true}).click();await page.getByRole('button',{name:'添加模型',exact:true}).click();
 const row=await page.locator('input[aria-label$="行模型"]').count();await page.getByLabel('第 '+row+' 行模型',{exact:true}).fill('mock-model');await page.getByLabel('第 '+row+' 行输入价格',{exact:true}).fill('1');await page.getByLabel('第 '+row+' 行输出价格',{exact:true}).fill('2');await page.locator('#pricing-version').fill(priceVersion);
 await page.getByRole('button',{name:'保存完整版本',exact:true}).click();const saved=page.getByRole('row').filter({hasText:priceVersion});await saved.getByRole('button',{name:'查看启用差异'}).click();await page.getByRole('button',{name:'确认启用此完整版本',exact:true}).click();await page.locator('[data-pricing-diff]').waitFor({state:'hidden'});
 const active=await(await context.request.get(base+'/api/pricing/active')).json();assert.equal(active.version,priceVersion);assert.ok(active.prices.length>1,'copy retains other models');
 await navigate('/workspace');await page.locator('[data-logout]').waitFor();assert.equal(await page.locator('a[href="#/workspace?tab=devices"]').count(),0,'removed device management is absent from the workspace');
 await navigate('/evaluate?dataset='+id+'&version=1.0.0&new=1');await page.locator('[data-form="endpoint"]').fill(endpoint);await page.locator('[data-check-connection]').click();await page.locator('#connection-status').filter({hasText:'已连接'}).waitFor();
 assert.equal(await page.locator('#executor-type,#execution-device,#device-agent').count(),0,'local execution does not offer device selection');
 await page.locator('[data-eval-next]').click();await page.locator('#evaluation-seed').fill('987');await page.locator('[data-eval-next]').click();
 const createdResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/runs'&&r.request().method()==='POST');await page.locator('[data-eval-next]').click();const created=await(await createdResponse).json();assert.equal(created.seed,987);
 await page.reload();await page.locator('[data-run-progress]').waitFor();assert.equal(await page.locator('[data-run-seed]').innerText(),'987');await page.locator('[data-run-progress] a[href^="#/report/"]').click();await page.locator('[data-report-json]').waitFor();
 const reportId=page.url().split('/report/')[1],report=await(await context.request.get(base+'/api/reports/'+reportId)).json();assert.equal(report.score,66.7);assert.equal(report.seed,987);assert.equal(report.pricingVersion,priceVersion);assert.equal(report.cost,0.00009);
 function verifyMetrics(record,rows){
  const total=rows.reduce((sum,row)=>sum+row.elapsedMs/1000,0);
  assert.ok(Math.abs(record.totalTimeSeconds-total)<1e-9);assert.ok(Math.abs(record.averageTimeSeconds-total/record.total)<1e-9);
  assert.ok(Math.abs(record.time-total)<1e-9,'Legacy time retains its cumulative meaning');
  assert.equal(record.categories.reduce((sum,row)=>sum+row.total,0),record.total);
  for(const category of record.categories){const own=rows.filter(row=>row.category===category.name);assert.equal(category.total,own.length);assert.equal(category.passed,own.filter(row=>row.passed).length);assert.equal(category.score,Math.round(category.passed*1000/category.total)/10);}
 }
 verifyMetrics(report,report.results);assert.ok(report.totalTimeSeconds>3,'Server measures elapsed time instead of trusting agent elapsedMs=5');
 assert.equal(report.results.filter(row=>row.passed===false).length,1,'Failed answers remain in timing and category totals');
 assert.deepEqual(report.categories.map(row=>[row.name,row.passed,row.total]),[['数值验证',1,2],['边界验证',1,1]]);
 assert.ok(report.results.every(row=>row.participantKey==='main'&&typeof row.category==='string'));
 assert.match(await page.locator('.stat-card').filter({hasText:'平均每题耗时'}).innerText(),new RegExp(secondsText(report.averageTimeSeconds).replace('.','\\.')));
 assert.match(await page.locator('.report-grid table').innerText(),/50\.0%/);
 const csvDownload=page.waitForEvent('download');await page.locator('[data-report-csv]').click();const reportCsv=await readFile(await(await csvDownload).path(),'utf8');assert.match(reportCsv,/category.*participant_key/);assert.match(reportCsv,/数值验证/);
 assert.match(await page.locator('[data-report-pricing]').innerText(),new RegExp(priceVersion));assert.match(await page.locator('[data-report-cost-status]').innerText(),/已计算/);assert.match(await page.locator('[data-report-usage-source]').innerText(),/Agent 自报/);assert.match(await page.locator('[data-report-location]').innerText(),/本机执行/);
 await mkdir('tmp/verification/workflow-live',{recursive:true});await page.screenshot({path:'tmp/verification/workflow-live/report.png',fullPage:true});
 const downloading=page.waitForEvent('download');await page.locator('[data-report-json]').click();const exported=JSON.parse(await readFile(await(await downloading).path(),'utf8'));assert.equal('permissions' in exported,false);assert.equal('ownerId' in exported,false);
 await page.locator('[data-sync-report]').click();await confirm();await page.locator('[data-publish-report]').click();await confirm();await page.waitForFunction(()=>!document.querySelector('[data-publish-report]'));
 const anonymous=await browser.newContext();const publicPage=await anonymous.newPage();await publicPage.goto(base+'/#/report/'+reportId);await publicPage.locator('[data-report-json]').waitFor();assert.equal(await publicPage.locator('[data-sync-report],[data-publish-report]').count(),0);
 const publicExport=publicPage.waitForEvent('download');await publicPage.locator('[data-report-json]').click();const publicReport=JSON.parse(await readFile(await(await publicExport).path(),'utf8'));assert.equal(publicReport.id,reportId);for(const field of ['ownerId','permissions','token','credentials'])assert.equal(field in publicReport,false);
 await publicPage.goto(base+'/#/datasets/'+id+'?version=1.0.0');await publicPage.locator('[data-custom-suite]').waitFor();const catalogDownload=publicPage.waitForEvent('download');await publicPage.getByRole('button',{name:'下载公开目录',exact:true}).click();const publicCatalog=JSON.parse(await readFile(await(await catalogDownload).path(),'utf8'));assert.equal(publicCatalog.dataset.id,id);assert.equal(publicCatalog.tasks[0].input.value,7);for(const field of ['verification','expected','hidden','oracle','reference'])assert.equal(field in publicCatalog.tasks[0],false);
 await anonymous.close();
 await navigate('/leaderboard?dataset='+id+'&version=1.0.0&type=public');await page.reload();await page.locator('[data-rank="dataset"]').selectOption(id);const rankRow=page.locator('[data-rank-record="'+reportId+'"]');await rankRow.waitFor();
 assert.equal(await rankRow.locator('td').nth(5).innerText(),secondsText(report.averageTimeSeconds));
 await page.locator('[data-rank-view="analysis"]').click();await page.locator('[data-chart-metric="time"]').click();await page.locator('[data-chart-point]').click();assert.ok((await page.locator('.rank-chart-tooltip').innerText()).includes(secondsText(report.averageTimeSeconds)));
 const csrf=await(await context.request.get(base+'/api/auth/csrf')).json();
 const pkResponse=await context.request.post(base+'/api/runs',{headers:{[csrf.headerName]:csrf.token},data:{kind:'pk',source:'local',datasetId:id,version:'1.0.0',scope:'full',budget:'standard',seed:987,endpoint,players:[{label:'A',agent:'Fixture A',model:'mock-model'},{label:'B',agent:'Fixture B',model:'mock-model-b'}]}});
 assert.equal(pkResponse.status(),202);let pkRun=await pkResponse.json();
 const deadline=Date.now()+30000;while(pkRun.status!=='completed'&&Date.now()<deadline){await new Promise(resolve=>setTimeout(resolve,250));pkRun=await(await context.request.get(base+'/api/runs/'+pkRun.runId)).json();}
 assert.equal(pkRun.status,'completed');const pkReport=await(await context.request.get(base+'/api/reports/'+pkRun.reportId)).json();
 for(const side of pkReport.competitors)verifyMetrics(side,pkReport.results.filter(row=>row.participantKey===side.participantKey));
 assert.deepEqual(pkReport.categories,pkReport.competitors[0].categories);assert.equal(pkReport.competitors[0].passed,2);assert.equal(pkReport.competitors[1].passed,1);
 await navigate('/report/'+pkReport.id);await page.locator('[data-report-json]').waitFor();const pkTable=await page.locator('.report-grid table').innerText();assert.match(pkTable,/数值验证/);assert.match(pkTable,/边界验证/);assert.match(pkTable,/0 \/ 1 · 0\.0%/);
 await page.screenshot({path:'tmp/verification/workflow-live/pk-metrics.png',fullPage:true});
 await navigate('/evaluate?run='+created.runId);await page.locator('[data-retry-run]').waitFor();const retriedResponse=page.waitForResponse(r=>r.url().endsWith('/retry')&&r.request().method()==='POST');await page.locator('[data-retry-run]').click();const retried=await(await retriedResponse).json();assert.equal(retried.seed,987);assert.notEqual(retried.runId,created.runId);
 await page.locator('[data-run-progress] a[href^="#/report/"]').waitFor();
 const retryId=(await page.locator('[data-run-progress] a[href^="#/report/"]').getAttribute('href')).split('/report/')[1];
 for(const action of ['sync','publish']){
  const currentCsrf=await(await context.request.get(base+'/api/auth/csrf')).json();
  const response=await context.request.post(base+'/api/reports/'+retryId+'/'+action,{headers:{[currentCsrf.headerName]:currentCsrf.token}});
  assert.equal(response.status(),200,action+': '+await response.text());
 }
 await navigate('/leaderboard?dataset='+id+'&version=1.0.0&type=public');await page.reload();await page.locator('[data-rank="dataset"]').selectOption(id);await page.locator('[data-rank-record="'+retryId+'"]').waitFor();
 await page.locator('[data-compare="'+reportId+'"]').check();await page.locator('[data-compare="'+retryId+'"]').check();await page.locator('[data-open-compare]').click();assert.ok((await page.locator('#modal').innerText()).includes(secondsText(report.averageTimeSeconds)));await page.locator('#modal [data-close-modal]').click();
 await navigate('/admin');await page.getByRole('button',{name:'管理员邀请',exact:true}).click();const readiness=await(await context.request.get(base+'/api/admin/mail/status')).json();assert.equal(readiness.mode,'outbox','Use an isolated outbox, never real delivery for this browser test');assert.equal(readiness.ready,true);
 const invitedEmail='invite-'+randomUUID()+'@example.com';await page.locator('#invitation-email').fill(invitedEmail);await page.locator('#invitation-name').fill('Browser invite');await page.getByRole('button',{name:'发送邀请',exact:true}).click();const invitation=page.getByRole('row').filter({hasText:invitedEmail});await invitation.getByRole('button',{name:'重发',exact:true}).click();await confirm();await invitation.getByRole('button',{name:'撤销',exact:true}).click();await confirm();assert.match(await invitation.innerText(),/已撤销/);
 await mkdir('tmp/verification/workflow-live',{recursive:true});await page.screenshot({path:'tmp/verification/workflow-live/invitations.png',fullPage:true});
 assert.deepEqual(errors,[]);console.log('PASS isolated real APIs: admin login, custom dataset validation/publication, multi-model prices, seeded execution and reload, report export/publication, retry, invitation resend/revoke.');
}catch(error){console.error({pageErrors:errors,url:page?.url()});throw error;}
finally{await browser?.close();await new Promise(resolve=>agent.close(resolve));}

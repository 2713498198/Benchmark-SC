import assert from 'node:assert/strict';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
import {labelsClearOfFrontier} from './chart-label-layout.mjs';

// Browser-only fixtures: no accounts or reports are written to the server.
const base=process.env.TEST_URL||'http://127.0.0.1:4173';
const out=resolve(process.env.TEST_ARTIFACT_DIR||'tmp/verification/home-chart');
await mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const checks=[],errors=[];
const check=(name,result)=>{assert.ok(result,name);checks.push(name);};
let reports=[],failure=false,requests=0,bootstraps=0;
const own={id:'own-private',agent:'Private Agent',model:'Private Model',published:false,score:90,cost:1};
const custom={id:'mini-spn',name:'Mini-SPN 差分分析',version:'2.0.0',published:true,tasks:120};
async function prepare(page){
 page.on('pageerror',error=>errors.push(error.message));
 await page.route('**/api/bootstrap',route=>{bootstraps++;return route.fulfill({json:{user:{id:'owner',name:'研究者',role:'researcher'},reports:[own],connections:[{id:'connection-original',name:'Agent',model:'private'}],customDatasets:[custom],publicReports:reports}});});
 await page.route('**/api/reports/public',route=>{requests++;return route.fulfill(failure?{status:503,json:{error:'unavailable'}}:{json:reports});});
 await page.addInitScript(()=>{
  history.scrollRestoration='manual';
  const Native=window.ResizeObserver;window.activeChartObservers=0;
  window.ResizeObserver=class extends Native{
   observe(target,options){if(target.matches('.rank-chart-stage')&&!this.chartActive){this.chartActive=true;window.activeChartObservers++;}return super.observe(target,options);}
   disconnect(){if(this.chartActive){window.activeChartObservers--;this.chartActive=false;}return super.disconnect();}
  };
 });
}
async function go(page){if(page.url()===base+'/#/')await page.reload();else await page.goto(base+'/#/');await page.waitForLoadState('networkidle');await page.evaluate(()=>document.fonts.ready);await page.evaluate(()=>window.scrollTo({top:0,behavior:'instant'}));}
async function geometry(page){return page.evaluate(()=>Object.fromEntries(['.home-hero','.hero-title-shell','.hero-subtitle','.hero-actions'].map(selector=>{const b=document.querySelector(selector).getBoundingClientRect();return [selector,{x:b.x,y:b.y,width:b.width,height:b.height}];})));}
const pointCount=page=>page.locator('[data-chart-point]').count();
const text=page=>page.locator('[data-home-cost-chart]').innerText();
const row=(id,extra={})=>({id,agent:'Agent '+id,model:'Model '+id,datasetId:'mini-spn',version:'1.0.0',budget:'standard',scope:'full',kind:'evaluation',source:'local',demo:false,published:true,total:120,passed:96,score:80,cost:1,time:30,...extra});

try{
 const visual=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});await prepare(visual);
 let baseline=false;
 if(process.env.HOME_BASELINE_DIR){
  const original=await readFile(resolve(process.env.HOME_BASELINE_DIR,'HomePage.before.js'),'utf8');
  const css=await readFile(resolve(process.env.HOME_BASELINE_DIR,'pages.before.css'),'utf8');
  await visual.route('**/js/vue/HomePage.js',route=>baseline?route.fulfill({body:original,contentType:'text/javascript'}):route.continue());
  await visual.route('**/css/pages.css',route=>baseline?route.fulfill({body:css,contentType:'text/css'}):route.continue());
 }
 for(const [width,height] of [[1440,1000],[768,1000],[390,844],[360,800]]){
  await visual.setViewportSize({width,height});
  let before,imageBefore;
  if(process.env.HOME_BASELINE_DIR){baseline=true;await go(visual);before=await geometry(visual);imageBefore=await visual.screenshot({path:resolve(out,`before-first-screen-${width}.png`),animations:'disabled'});}
  baseline=false;await go(visual);
  const imageAfter=await visual.screenshot({path:resolve(out,`after-first-screen-${width}.png`),animations:'disabled'});
  if(before){assert.deepEqual(await geometry(visual),before);check(`${width}: first-screen element bounds unchanged`,true);check(`${width}: first-screen pixels unchanged`,imageBefore.equals(imageAfter));}
  const layout=await visual.evaluate(()=>{
   const chart=document.querySelector('[data-home-cost-chart]').getBoundingClientRect(),hero=document.querySelector('.home-hero').getBoundingClientRect(),explore=document.querySelector('.home-explore').getBoundingClientRect();
   const stage=document.querySelector('.rank-chart-stage').getBoundingClientRect();
   const labels=[...document.querySelectorAll('.rank-chart-label')].filter(el=>!el.hidden).map(el=>el.getBoundingClientRect());
   return {below:chart.top>=hero.bottom&&chart.top>=innerHeight-1,beforeExplore:chart.bottom<=explore.top,noOverflow:document.documentElement.scrollWidth<=innerWidth+1,labels:labels.length,agents:[...document.querySelectorAll('.rank-chart-label small')].every(el=>getComputedStyle(el).display!=='none'),fit:labels.every(b=>b.left>=stage.left&&b.right<=stage.right&&b.top>=stage.top&&b.bottom<=stage.bottom),collision:labels.some((a,i)=>labels.slice(i+1).some(b=>a.left<b.right&&a.right>b.left&&a.top<b.bottom&&a.bottom>b.top))};
  });
  check(`${width}: chart below first screen and before Explore`,layout.below&&layout.beforeExplore);
  check(`${width}: no horizontal overflow`,layout.noOverflow);
  check(`${width}: model and agent labels fit without overlap`,layout.labels===4&&layout.agents&&layout.fit&&!layout.collision);
  check(`${width}: labels leave the frontier unobstructed`,await visual.evaluate(labelsClearOfFrontier));
  await visual.locator('[data-chart-zoom]').check();
  await visual.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  check(`${width}: zoomed labels leave the frontier unobstructed`,await visual.evaluate(labelsClearOfFrontier));
  await visual.locator('[data-chart-zoom]').uncheck();
  await visual.locator('[data-home-cost-chart]').screenshot({path:resolve(out,`chart-${width}.png`),animations:'disabled',style:'.topbar{visibility:hidden}'});
 }
 check('No public records use clearly labelled demo fallback',(await text(visual)).includes('当前条件暂无公开记录')&&(await visual.locator('.home-cost-trust .badge').innerText())==='示例数据');
 await visual.locator('.home-data-note summary').click();
 check('Demo explanation remains available on demand',await visual.locator('.home-data-note p').isVisible()&&(await text(visual)).includes('示例数据不代表真实'));
 await visual.locator('.home-data-note summary').click();
 check('Demo frontier lists all three preferred combinations',await visual.locator('.home-frontier li').count()===3);
 check('Homepage locks horizontal axis to cost',await visual.locator('[data-chart-metric]').count()===0);
 check('Homepage title and description use the requested wording',await visual.locator('#home-cost-title').innerText()==='测评榜单'&&(await text(visual)).includes('快速找到更适合你的模型与智能体组合。'));
 check('Local demos do not advertise cloud execution',await visual.locator('[data-home-filter="source"] option').allTextContents().then(labels=>JSON.stringify(labels)===JSON.stringify(['全部来源','本地执行'])));
 await visual.locator('[data-home-filter="type"]').selectOption('public');check('Manual public mode stays empty',await visual.locator('.rank-chart-empty').isVisible());
 await visual.locator('[data-home-filter="type"]').selectOption('demo');check('Manual demo mode restores examples',await pointCount(visual)===4);
 await visual.locator('[data-chart-zoom]').check();check('Zoom updates axes',Number(await visual.locator('.rank-chart-stage').getAttribute('data-y-min'))>0);
 await visual.locator('[data-chart-point]').first().focus();check('Keyboard opens point details',await visual.locator('.rank-chart-tooltip').isVisible());
 await visual.keyboard.press('Escape');check('Escape closes point details',await visual.locator('.rank-chart-tooltip').isHidden());
 await visual.close();

 const page=await browser.newPage({viewport:{width:1440,height:1000}});await prepare(page);
 await page.clock.install({time:new Date('2026-10-23T10:00:00Z')});await page.clock.pauseAt(new Date('2026-10-23T10:00:01Z'));
 requests=0;bootstraps=0;await go(page);
 const saved=await page.evaluate(()=>localStorage.getItem('cryptobench.v1'));
 async function tick(){const response=page.waitForResponse(r=>r.url().endsWith('/api/reports/public'));await page.clock.runFor(30001);await response;await page.locator('.home-cost-update').filter({hasText:failure?'更新失败':'最近更新'}).waitFor();}
 check('Initial load refreshes public feed exactly once',requests===1&&bootstraps===1);
 reports=[row('free',{cost:0,agent:'A & B',model:'Model <Alpha>'}),row('tie',{cost:0}),row('best',{cost:2,score:95}),row('missing',{cost:null}),row('negative',{cost:-1}),row('string',{cost:'1'}),row('invalid-score',{score:101}),row('other-budget',{budget:'extended'}),row('other-version',{version:'2.0.0'}),row('other-dataset',{datasetId:'key-recovery'}),row('partial',{scope:'sample'}),row('pk',{kind:'pk'}),row('free',{cost:4})];
 await tick();
 check('30-second refresh switches automatically to public records',(await text(page)).includes('公开记录 · 未核验')&&await pointCount(page)===2);
 check('Missing/invalid costs and scores excluded, never zero-filled',/3 \/ 7 项成绩绘入图表/.test(await text(page))&&/4 项缺少有效费用/.test(await text(page)));
 check('Coincident points retain separate preferred combinations',await page.locator('.home-frontier li').count()===3);
 await page.locator('[data-chart-point]').first().click();
 check('Grouped details retain identities, zero fee and escaped text',/A & B/.test(await page.locator('.rank-chart-tooltip').innerText())&&/Model <Alpha>/.test(await page.locator('.rank-chart-tooltip').innerText())&&/\$0/.test(await page.locator('.rank-chart-tooltip').innerText())&&await page.locator('alpha').count()===0);
 await tick();check('Unchanged refresh preserves open details',await page.locator('.rank-chart-tooltip').isVisible());
 reports.push(row('remote',{source:'cloud',cost:1.5,score:92}),row('unreported',{source:null,cost:1.2,score:82}));await tick();
 check('Other execution locations appear only when actual reports declare them',await page.locator('[data-home-filter="source"] option[value="cloud"]').innerText()==='云端（报告声明）');
 await page.locator('[data-home-filter="source"]').selectOption('cloud');
 check('Homepage source filter isolates cloud declarations',await pointCount(page)===1&&(await text(page)).includes('Agent remote'));
 await page.locator('[data-chart-point]').first().click();check('Chart tooltip labels declared cloud location honestly',(await page.locator('.rank-chart-tooltip').innerText()).includes('云端（报告声明）'));
 await page.locator('[data-home-filter="source"]').selectOption('unreported');
 check('Missing execution source stays separate from local execution',await pointCount(page)===1&&(await text(page)).includes('Agent unreported'));
 await page.locator('[data-home-filter="source"]').selectOption('all');
 await page.locator('[data-home-filter="budget"]').selectOption('extended');check('Budget filter isolates matching records',await pointCount(page)===1&&(await text(page)).includes('Agent other-budget'));
 await page.locator('[data-home-filter="budget"]').selectOption('standard');
 await page.locator('[data-home-filter="version"]').selectOption('2.0.0');check('Version filter isolates matching records',await pointCount(page)===1&&(await text(page)).includes('Agent other-version'));
 await page.locator('[data-home-filter="dataset"]').selectOption('key-recovery');check('Dataset change resets to available version',await page.locator('[data-home-filter="version"]').inputValue()==='1.0.0'&&await pointCount(page)===1);
 await page.locator('[data-home-filter="dataset"]').selectOption('mini-spn');await page.locator('[data-home-filter="version"]').selectOption('1.0.0');
 await page.locator('[data-home-filter="type"]').selectOption('demo');check('Explicit demo selection overrides public preference',await pointCount(page)===4);
 await tick();check('Refresh preserves explicit source selection',await page.locator('[data-home-filter="type"]').inputValue()==='demo');
 await page.locator('[data-home-filter="type"]').selectOption('auto');
 reports=[row('missing-only',{cost:null})];await tick();check('Public records with missing fees show honest empty state',await page.locator('.rank-chart-empty').isVisible()&&(await text(page)).includes('公开记录 · 未核验'));
 const previousStatus=await page.locator('.home-cost-update').innerText();failure=true;await tick();
 check('Failure retains data and last successful update',(await text(page)).includes('更新失败')&&(await text(page)).includes('暂无可绘制的费用数据')&&(await page.locator('.home-cost-update').innerText()).includes(previousStatus.split(' · ')[0]));
 failure=false;reports=[row('recovered',{score:99})];await tick();check('Following refresh recovers automatically',await pointCount(page)===1&&!(await text(page)).includes('更新失败'));
 await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});document.dispatchEvent(new Event('visibilitychange'));});
 const pausedRequests=requests;await page.clock.runFor(90001);check('Hidden page stops polling',requests===pausedRequests);
 const resumed=page.waitForResponse(r=>r.url().endsWith('/api/reports/public'));
 await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>false});document.dispatchEvent(new Event('visibilitychange'));});await resumed;
 check('Becoming visible refreshes immediately',requests===pausedRequests+1);
 check('Polling never rehydrates or overwrites private workspace',bootstraps===1&&saved===await page.evaluate(()=>localStorage.getItem('cryptobench.v1')));
 check('Exactly one chart observer remains after repeated updates',await page.evaluate(()=>window.activeChartObservers===1));
 const homeBudgets=await page.locator('[data-home-filter="budget"] option').evaluateAll(options=>options.map(o=>[o.value,o.textContent]));
 await page.locator('[data-home-filter="source"]').selectOption('local');
 await page.locator('.home-cost-heading .text-link').click();await page.locator('.leaderboard-page').waitFor();
 check('Full ranking link preserves selected public context',await page.locator('[data-rank="type"]').inputValue()==='public'&&await page.locator('[data-rank="version"]').inputValue()==='1.0.0');
 check('Homepage and ranking budget options match exactly',JSON.stringify(homeBudgets)===JSON.stringify(await page.locator('[data-rank="budget"] option').evaluateAll(options=>options.map(o=>[o.value,o.textContent]))));
 check('Full ranking link preserves execution source filter',await page.locator('[data-rank="source"]').inputValue()==='local');
 const leftRequests=requests;await page.clock.runFor(90001);check('Leaving homepage stops polling and releases observer',requests===leftRequests&&await page.evaluate(()=>window.activeChartObservers===0));
 await page.evaluate(()=>{location.hash='/';});await page.locator('[data-home-cost-chart]').waitFor();
 check('Returning home fetches immediately',requests===leftRequests+1);
 await page.close();

 reports=[];
 const touch=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});const mobile=await touch.newPage();await prepare(mobile);await go(mobile);
 await mobile.locator('[data-chart-point]').first().tap();check('Touch tap opens details',await mobile.locator('.rank-chart-tooltip').isVisible());
 await mobile.locator('[data-home-cost-chart]').screenshot({path:resolve(out,'touch-details-390.png'),animations:'disabled'});await touch.close();
 check('No browser errors',errors.length===0);
 await writeFile(resolve(out,'results.json'),JSON.stringify({passed:checks.length,checks,errors},null,2));
 console.log(`PASS ${checks.length} homepage chart browser checks.`);
}finally{if(errors.length)console.error('Browser errors:',errors);await browser.close();}

import assert from 'node:assert/strict';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
import {datasets} from '../src/main/resources/static/js/data.js';

// Read-only browser acceptance. Bootstrap fixtures exist only in this browser;
// registration, publication and account tests run separately on an isolated site.
const base=process.env.TEST_URL||'http://127.0.0.1:4173';
const out=resolve(process.env.TEST_ARTIFACT_DIR||'tmp/verification/leaderboard-chart');
await mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const page=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});
const checks=[],errors=[];
const check=(name,result)=>{assert.ok(result,name);checks.push(name);};
page.on('pageerror',error=>errors.push(error.message));
let publicReports=[];
await page.route('**/api/bootstrap',route=>route.fulfill({json:{user:null,reports:[],connections:[],customDatasets:[],publicReports}}));
await page.addInitScript(()=>{
 const Native=window.ResizeObserver;window.activeChartObservers=0;
 window.ResizeObserver=class extends Native{
  observe(target,options){if(target.matches('.rank-chart-stage')&&!this.chartActive){this.chartActive=true;window.activeChartObservers++;}return super.observe(target,options);}
  disconnect(){if(this.chartActive){window.activeChartObservers--;this.chartActive=false;}return super.disconnect();}
 };
});
async function go(route='/leaderboard'){
 await page.goto(base+'/#'+route);await page.reload();await page.waitForLoadState('networkidle');
 await page.waitForFunction(route=>document.querySelector('#main')?.dataset.route===route,route);
}
async function view(name){await page.locator('[data-rank-view="'+name+'"]').click();}
async function settle(){await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));}
async function shot(name,locator){await settle();await (locator||page).screenshot({path:resolve(out,name+'.png'),...(locator?{}:{fullPage:true}),animations:'disabled'});}

try{
 await go();
 check('All five records remain in rankings',await page.locator('[data-compare]').count()===5);await view('analysis');
 check('Four cost points; all five records remain in details',await page.locator('[data-chart-point]').count()===4);
 check('Missing cost disclosed',/1 项缺少有效费用/.test(await page.locator('.rank-chart-foot').innerText()));
 check('Demo identity stays visible',/示例数据不代表真实/.test(await page.locator('.demo-banner').innerText()));
 for(const width of [1440,1024,768,390,360]){
  await page.setViewportSize({width,height:width>=1024?1000:844});
  for(const metric of ['cost','time']){
   await page.locator(`[data-chart-metric="${metric}"]`).click();await settle();
   check(`${width} ${metric} metric switch retains keyboard focus`,await page.locator(`[data-chart-metric="${metric}"]`).evaluate(el=>el===document.activeElement&&el.getAttribute('aria-pressed')==='true'));
   const layout=await page.evaluate(()=>{
    const stage=document.querySelector('.rank-chart-stage').getBoundingClientRect();
    const labels=[...document.querySelectorAll('.rank-chart-label')].filter(el=>!el.hidden).map(el=>el.getBoundingClientRect());
    return {
     noOverflow:document.documentElement.scrollWidth<=innerWidth+1,
     allNames:labels.length===document.querySelectorAll('[data-chart-point]').length,
     inBounds:labels.every(r=>r.left>=stage.left&&r.right<=stage.right&&r.top>=stage.top&&r.bottom<=stage.bottom),
     collision:labels.some((a,i)=>labels.slice(i+1).some(b=>a.left<b.right&&a.right>b.left&&a.top<b.bottom&&a.bottom>b.top)),
     observers:window.activeChartObservers
    };
   });
   check(`${width} ${metric} no page overflow`,layout.noOverflow);
   check(`${width} ${metric} model names fit without overlap`,layout.allNames&&layout.inBounds&&!layout.collision);
   check(`${width} ${metric} one active resize observer`,layout.observers===1);
   await shot(`final-${metric}-${width}`);
   if(width===1440||width===360)await shot(`plot-${metric}-${width}`,page.locator('.rank-chart-stage'));
  }
 }
 await page.setViewportSize({width:1440,height:1000});
 await page.locator('[data-chart-metric="cost"]').click();
 const point=page.getByRole('button',{name:/DiffHunter \/ DeepSeek V3，/});
 await point.focus();
 check('Keyboard point details include measured values and provenance',/85.8%/.test(await page.locator('.rank-chart-tooltip').innerText())&&/\$0.76/.test(await page.locator('.rank-chart-tooltip').innerText())&&/示例成绩/.test(await page.locator('.rank-chart-tooltip').innerText()));
 await shot('keyboard-tooltip-1440',page.locator('.rank-chart'));
 await page.keyboard.press('Escape');check('Escape dismisses tooltip',await page.locator('.rank-chart-tooltip').isHidden());
 await page.locator('[data-chart-metric="cost"]').focus();await point.hover();
 check('Mouse hover opens tooltip',await page.locator('.rank-chart-tooltip').isVisible());
 await page.locator('.rank-chart-tooltip').hover();check('Tooltip is hoverable',await page.locator('.rank-chart-tooltip').isVisible());
 await point.click();await page.mouse.move(0,0);check('Click pins point details',await page.locator('.rank-chart-tooltip').isVisible());
 await page.locator('h1').click();check('Outside click dismisses details',await page.locator('.rank-chart-tooltip').isHidden());
 await page.locator('[data-rank="source"]').selectOption('local');
 check('Source filter updates chart',await page.locator('[data-chart-point]').count()===2);await view('ranking');check('Source filter updates table',await page.locator('[data-compare]').count()===2);await view('analysis');
 const before=await page.locator('[data-chart-point]').evaluateAll(buttons=>buttons.map(b=>b.getAttribute('aria-label')));
 await page.locator('[data-rank="sort"]').selectOption('cost');
 check('Table sort does not change frontier',JSON.stringify(before)===JSON.stringify(await page.locator('[data-chart-point]').evaluateAll(buttons=>buttons.map(b=>b.getAttribute('aria-label')))));
 const download=page.waitForEvent('download');await page.locator('[data-export-rank]').click();
 const csv=await readFile(await (await download).path(),'utf8');
 check('CSV preserves filtered records and schema',csv.includes('pass_rate')&&csv.includes('S-box Sage')&&csv.includes('RoundKey')&&!csv.includes('CipherPilot'));
 await view('ranking');await page.locator('[data-compare]').nth(0).check();await page.locator('[data-compare]').nth(1).check();await page.locator('[data-open-compare]').click();
 check('Comparison works after returning to ranking',await page.locator('#modal').isVisible());await page.locator('[data-close-modal]').click();
 await page.locator('[data-expand]').first().click();check('Record evidence expands',await page.locator('.leaderboard-details').isVisible());
 await view('analysis');await page.locator('[data-rank="budget"]').selectOption('extended');
 check('Empty filters remove points and show empty state',await page.locator('[data-chart-point]').count()===0&&await page.locator('.rank-chart-empty').isVisible());await shot('empty-1440');
 await page.locator('[data-reset-rank]').click();check('Reset restores cost chart',await page.locator('[data-chart-point]').count()===4);
 await page.locator('.rank-chart-foot summary').click();check('Reading guidance opens',await page.locator('#rank-chart-help').isVisible());
 await page.locator('a[href="#/datasets"]').first().click();await page.waitForFunction(()=>document.querySelector('#main').dataset.route==='/datasets');check('Route change disposes chart observer',await page.evaluate(()=>window.activeChartObservers===0));

 const dataset=datasets.find(d=>d.id!=='mini-spn'&&d.id!=='cryptobench-sc-v4');
 const row={datasetId:dataset.id,version:dataset.version,budget:'standard',scope:'full',source:'local',agent:'A & B',model:'Model <Alpha>',total:10,passed:8,score:80,time:32,cost:null,demo:true};
 publicReports=[{...row,id:'chart-missing'}];await go('/leaderboard?dataset='+dataset.id+'&version='+dataset.version);
 check('Missing fee record stays in rankings',await page.locator('[data-compare]').count()===1);await view('analysis');check('All-missing cost state is honest',/暂无可绘制的费用数据/.test(await page.locator('.rank-chart-empty').innerText()));await shot('missing-cost-1440');
 await page.locator('[data-chart-metric="time"]').click();check('Missing-cost record appears in time mode',await page.locator('[data-chart-point]').count()===1);
 publicReports=[{...row,id:'chart-free',cost:0},{...row,id:'chart-tie',cost:0,agent:'Same result'},{...row,id:'chart-best',cost:1,score:100,passed:10}];
 await go('/leaderboard?dataset='+dataset.id+'&version='+dataset.version);
 check('Coincident rows preserved in rankings',await page.locator('[data-compare]').count()===3);await view('analysis');check('Coincident points grouped without dropping table rows',await page.locator('[data-chart-point]').count()===2);
 await page.locator('[data-chart-point]').first().click();
 check('Grouped tooltip includes every identity and real zero fee',/Same result/.test(await page.locator('.rank-chart-tooltip').innerText())&&/\$0/.test(await page.locator('.rank-chart-tooltip').innerText()));
 check('Model and agent text remain escaped',await page.locator('alpha').count()===0&&/Model <Alpha>/.test(await page.locator('.rank-chart-tooltip').innerText()));await shot('coincident-zero-cost-1440',page.locator('.rank-chart'));
 publicReports=[];await go();
 await page.emulateMedia({reducedMotion:'reduce'});await page.setViewportSize({width:360,height:800});
 await page.locator('.rank-filter-disclosure summary').click();await page.locator('[data-rank="version"]').focus();await page.keyboard.press('Tab');
 check('Mobile filter keyboard order follows visual order',await page.locator('[data-rank="source"]').evaluate(el=>el===document.activeElement));
 await view('analysis');await page.locator('[data-chart-metric="time"]').click();await settle();
 check('Reduced motion retains fully rendered chart',await page.locator('[data-chart-point]').count()===5&&await page.locator('canvas').evaluate(c=>c.width>0));
 const touch=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
 const mobile=await touch.newPage();await mobile.route('**/api/bootstrap',route=>route.fulfill({json:{user:null,reports:[],connections:[],customDatasets:[],publicReports:[]}}));
 await mobile.goto(base+'/#/leaderboard');await mobile.waitForLoadState('networkidle');await mobile.locator('[data-rank-view="analysis"]').click();await mobile.locator('[data-chart-point]').first().tap();
 check('Touch tap opens chart details',await mobile.locator('.rank-chart-tooltip').isVisible());await mobile.locator('.rank-chart').screenshot({path:resolve(out,'touch-tooltip-390.png')});await touch.close();
 check('No browser errors',errors.length===0);
 await writeFile(resolve(out,'chart-browser-results.json'),JSON.stringify({passed:checks.length,checks,errors},null,2));
 console.log(`PASS ${checks.length} leaderboard chart browser checks.`);
}finally{await browser.close();}

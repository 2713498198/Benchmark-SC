import assert from 'node:assert/strict';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {chromium} from 'playwright';

// All fixtures are intercepted in-browser. No accounts or reports are written.
const base=process.env.TEST_URL||'http://127.0.0.1:4173';
const out=resolve(process.env.TEST_ARTIFACT_DIR||'tmp/verification/leaderboard-authority');
await mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const context=await browser.newContext({viewport:{width:1440,height:1000},deviceScaleFactor:1});
const page=await context.newPage(),checks=[],errors=[],layouts=[];
let publicReports=[];
await page.route('**/api/bootstrap',route=>route.fulfill({json:{user:null,reports:[],connections:[],customDatasets:[],publicReports}}));
page.on('pageerror',error=>errors.push(error.message));
const check=(name,ok)=>{assert.ok(ok,name);checks.push(name);};
const rows=()=>page.locator('[data-rank-record]');
const view=name=>page.locator(`[data-rank-view="${name}"]`).click();
const filter=(key,value)=>page.locator(`[data-rank="${key}"]`).selectOption(value);
async function go(route='/leaderboard'){
 await page.goto(base+'/#'+route);await page.reload();await page.waitForLoadState('networkidle');
 await page.waitForFunction(route=>document.querySelector('#main')?.dataset.route===route,route);
}
async function shot(name,fullPage=true){if(fullPage)await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:resolve(out,name+'.png'),fullPage,animations:'disabled'});}
async function download(button){const waiting=page.waitForEvent('download');await button.click();return readFile(await (await waiting).path(),'utf8');}
async function ranks(){return rows().evaluateAll(elements=>Object.fromEntries(elements.map(el=>[el.dataset.rankRecord,el.querySelector('.rank-number').textContent])));}
async function noOverflow(){return page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1);}

try{
 await go();
 check('Default view is example ranking',await page.locator('[data-rank="type"]').inputValue()==='demo'&&await rows().count()===5&&await page.locator('[data-rank-chart]').count()===0);
 for(const width of [1440,1024,768,390,360]){
  const height=width===360?800:width===390?844:1000;
  await page.setViewportSize({width,height});await page.evaluate(()=>scrollTo(0,0));
  const layout=await rows().evaluateAll(elements=>({width:innerWidth,height:innerHeight,visible:elements.filter(el=>el.getBoundingClientRect().bottom<=innerHeight).length,firstTop:elements[0].getBoundingClientRect().top}));
  layouts.push(layout);check(`${width}px no page overflow`,await noOverflow());
  if(width===1440||width<=390)check(`${width}px first screen minimum records`,layout.visible>=(width===1440?5:2));
  await shot('final-ranking-'+width);await shot('viewport-ranking-'+width,false);
  await page.locator('.rank-name').first().click();
  check(`${width}px expanded evidence no overflow`,await noOverflow());
  if(width<=390)check(`${width}px expansion preserves identity column width`,await page.locator('.rank-identity').first().evaluate(el=>el.clientWidth>120));
  await shot('evidence-'+width);
  await page.locator('.rank-name').first().click();
 }
 await page.setViewportSize({width:1440,height:1000});
 const initial=await ranks();await filter('sort','cost');
 check('Cost sorting keeps pass-rate rank',JSON.stringify(Object.entries(await ranks()).sort())===JSON.stringify(Object.entries(initial).sort()));
 check('Cost sort indicator matches actual column',await page.locator('th[aria-sort="ascending"]').innerText()==='费用 USD ↑');
 check('Missing cost stays last',await rows().last().getAttribute('data-rank-record')==='rank-linear-lab');
 check('Sort restores focus',await page.locator('[data-rank="sort"]').evaluate(el=>el===document.activeElement));
 await page.locator('#rank-search').fill('deepseek');
 check('Model search preserves rank and cohort',await rows().count()===1&&await rows().first().locator('.rank-number').innerText()==='03'&&await page.locator('.rank-result-count').innerText()==='1 / 5 项'&&/91.7%/.test(await page.locator('.rank-summary').innerText()));
 check('Search restores focus and caret',await page.locator('#rank-search').evaluate(el=>el===document.activeElement&&el.selectionStart===8));
 const searchCsv=await download(page.locator('[data-export-rank]'));
 check('CSV reflects displayed cohort',searchCsv.includes('DiffHunter')&&!searchCsv.includes('CipherPilot'));
 await shot('search-1440');
 await page.locator('#rank-search').fill('no-such-agent');check('Search empty state',await page.locator('[data-clear-rank-search]').isVisible());
 await page.locator('[data-clear-rank-search]').click();await filter('sort','score');
 await page.locator('.rank-name').first().click();
 const sampleEvidence=await page.locator('.rank-evidence').innerText();
 check('Sample evidence labels missing data',sampleEvidence.includes('内置样例')&&sampleEvidence.includes('未提供')&&!sampleEvidence.includes('demo-v1')&&!/随机种子\s*42/.test(sampleEvidence));
 const sampleJson=JSON.parse(await download(page.locator('[data-download-rank]').first()));
 check('Sample JSON is marked demo without invented configuration',sampleJson.demo===true&&sampleJson.id==='rank-cipher-pilot'&&!('evaluatorVersion' in sampleJson));
 await page.locator('.rank-name').first().click();
 for(let n=0;n<4;n++)await page.locator('[data-compare]').nth(n).check();
 await page.locator('[data-compare]').nth(4).check({trial:true});await page.locator('[data-compare]').nth(4).click();
 check('Comparison maximum four enforced',await page.locator('[data-compare]:checked').count()===4);await page.locator('#toast.show').waitFor({state:'hidden'});
 await view('analysis');await view('ranking');check('View switches retain four selections',await page.locator('[data-compare]:checked').count()===4);
 await page.locator('[data-open-compare]').click();check('Comparison matrix aligns metrics',await page.locator('.rank-comparison thead th').count()===5&&await page.locator('.rank-comparison tbody tr').count()===10);
 await shot('compare-1440',false);
 await page.setViewportSize({width:360,height:800});check('Mobile matrix scrolls within dialog',await noOverflow()&&await page.locator('.rank-comparison').evaluate(el=>el.scrollWidth>el.clientWidth));await shot('compare-360',false);
 await page.keyboard.press('Escape');await page.setViewportSize({width:1440,height:1000});
 await filter('source','local');check('Condition change clears selection',await page.locator('[data-compare]:checked').count()===0&&await page.locator('.compare-bar').count()===0);
 await filter('source','all');await view('analysis');
 check('Default chart axis 0 to 100',await page.locator('.rank-chart-stage').getAttribute('data-y-min')==='0'&&await page.locator('.rank-chart-stage').getAttribute('data-y-max')==='100');
 await shot('analysis-1440');await page.locator('[data-chart-zoom]').check();check('Zoom is visibly labelled',/纵轴已放大/.test(await page.locator('.rank-axis-note').innerText()));await shot('analysis-zoom-1440');
 await page.getByRole('button',{name:/DiffHunter \/ DeepSeek V3，/}).focus();await page.keyboard.press('Tab');
 check('Keyboard enters chart locate action',await page.locator('[data-chart-locate]').evaluate(el=>el===document.activeElement));
 await page.keyboard.press('Enter');check('Chart locates and focuses correct ranking row',await page.locator('[data-rank-record="rank-diff-hunter"]').evaluate(el=>el===document.activeElement)&&await page.locator('.rank-evidence').isVisible());await shot('located-1440');
 await filter('type','public');check('Public records empty state',await rows().count()===0&&await page.locator('[data-show-demo]').isVisible());await shot('public-empty-1440');
 await view('analysis');await page.locator('[data-show-demo]').click();check('Analysis empty action returns examples',await page.locator('[data-chart-point]').count()===4);
 await view('ranking');await filter('budget','extended');await page.locator('[data-reset-rank]').click();check('Ranking reset restores conditions',await rows().count()===5);

 const common={datasetId:'key-recovery',version:'1.0.0',budget:'standard',scope:'full',source:'local',total:100,passed:90,score:90,model:'Research Model',published:true};
 const raw={...common,id:'public-a',agent:'配置 A',score:90.04,cost:2,time:30,demo:false,agentVersion:'a2.1',evaluatorVersion:'v3.4',config:{seed:0,temperature:0.2},createdAt:'2026-10-18T01:00:00Z',results:[{taskId:'t1',passed:true}]};
 publicReports=[raw,{...common,id:'public-b',agent:'配置 B',score:90.01,cost:1,time:20},{...common,id:'public-c',agent:'超长研究配置名称'.repeat(10),score:70,cost:null,time:null}, {...common,id:'public-demo',agent:'公开模拟示例',demo:true,score:100,cost:0,time:1}];
 await go('/leaderboard?dataset=key-recovery&version=1.0.0');
 check('Published demo report joins example cohort',await rows().count()===1&&/公开模拟示例/.test(await rows().innerText()));
 await filter('type','public');
 check('Unverified cohort excludes demo and uses competition ranks',await rows().count()===3&&JSON.stringify(await rows().locator('.rank-number').allTextContents())===JSON.stringify(['01','01','03']));
 check('Overview labels ties and missing fee coverage',/2 项并列最高/.test(await page.locator('.rank-summary').innerText())&&/67%/.test(await page.locator('.rank-summary').innerText()));
 await filter('sort','time');check('Time order keeps tie ranks',JSON.stringify(await rows().locator('.rank-number').allTextContents())===JSON.stringify(['01','01','03']));
 await page.locator('[data-rank-record="public-a"] .rank-name').click();
 const actualEvidence=await page.locator('.rank-evidence').innerText();
 check('Evidence uses actual versions, zero seed and submission time',/a2.1/.test(actualEvidence)&&/v3.4/.test(actualEvidence)&&/随机种子\s*0/.test(actualEvidence)&&/2026-10-18T01:00:00Z/.test(actualEvidence));
 check('Public visibility never claims verification',/未核验/.test(actualEvidence)&&!/已核验|已认证/.test(actualEvidence));
 await page.locator('.rank-evidence-data summary').click();check('Existing configuration and results are preserved',/temperature/.test(await page.locator('.rank-evidence-data pre').innerText())&&/t1/.test(await page.locator('.rank-evidence-data pre').innerText()));
 assert.deepEqual(JSON.parse(await download(page.locator('[data-download-rank]'))),raw);checks.push('Published JSON is byte-shape equivalent to supplied record');
 const publicCsv=await download(page.locator('[data-export-rank]'));
 check('CSV flags match public records instead of hardcoded demo',publicCsv.includes('"false",')&&!publicCsv.includes('公开模拟示例'));
 await shot('public-evidence-1440');await page.locator('[data-rank-record="public-a"] .rank-name').click();await page.locator('[data-rank-record="public-c"] .rank-name').click();
 check('Missing evidence is not fabricated',/未提供/.test(await page.locator('.rank-evidence').innerText())&&!/demo-v1/.test(await page.locator('.rank-evidence').innerText()));
 for(const width of [1440,1024,768,390,360]){
  await page.setViewportSize({width,height:width<=390?844:1000});check(`${width}px long names and missing evidence no overflow`,await noOverflow());
  if(width<=390)check(`${width}px long record keeps usable row height`,await rows().last().evaluate(el=>el.getBoundingClientRect().height<120));
  await shot('long-record-'+width);
 }
 await page.setViewportSize({width:1440,height:1000});await view('analysis');check('Public chart excludes demo and missing fee record',await page.locator('[data-chart-point]').count()===2&&!/公开模拟示例/.test(await page.locator('.rank-chart').innerText()));
 await page.locator('[data-chart-zoom]').uncheck();await page.setViewportSize({width:360,height:800});await shot('public-analysis-360');
 publicReports=[];await go();
 await page.locator('#rank-search').focus();
 await page.locator('#rank-search').evaluate(el=>{el.value='Deep';el.dispatchEvent(new InputEvent('input',{bubbles:true,isComposing:true}));});
 check('Composition keeps input and results stable until committed',await rows().count()===5&&await page.locator('#rank-search').inputValue()==='Deep');
 await page.locator('#rank-search').evaluate(el=>el.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true,data:'Deep'})));
 check('Composition commit applies search',await rows().count()===1);
 await page.locator('#rank-search').fill('');await page.locator('#rank-tab-ranking').focus();await page.keyboard.press('ArrowRight');
 check('Arrow key selects and focuses analysis tab',await page.locator('#rank-tab-analysis').evaluate(el=>el===document.activeElement&&el.getAttribute('aria-selected')==='true'));
 await page.keyboard.press('Home');check('Home key returns to ranking',await rows().count()===5);
 const touch=await browser.newContext({viewport:{width:360,height:800},isMobile:true,hasTouch:true});
 const mobile=await touch.newPage();await mobile.route('**/api/bootstrap',route=>route.fulfill({json:{user:null,reports:[],connections:[],customDatasets:[],publicReports:[]}}));
 await mobile.goto(base+'/#/leaderboard');await mobile.waitForLoadState('networkidle');
 await mobile.locator('.rank-compare-check').first().tap();check('Mobile comparison label is tappable',await mobile.locator('[data-compare]').first().isChecked());
 await mobile.locator('[data-rank-view="analysis"]').tap();
 for(let i=0;i<4;i++){
  await mobile.locator('h1').tap();const point=mobile.locator('[data-chart-point]').nth(i),identity=(await point.getAttribute('aria-label')).split(' / ')[0];
  await point.tap();check(`Touch resolves close point ${identity}`,await mobile.locator('.rank-tooltip-record>strong').innerText()===identity);
 }
 await mobile.locator('[data-chart-locate]').tap();check('Touch chart action locates ranking',await mobile.locator('.rank-evidence').isVisible());
 await mobile.screenshot({path:resolve(out,'touch-located-360.png'),animations:'disabled'});await touch.close();
 check('No browser runtime errors',errors.length===0);
 await writeFile(resolve(out,'authority-browser-results.json'),JSON.stringify({passed:checks.length,checks,layouts,errors},null,2));
 console.log(`PASS ${checks.length} leaderboard authority browser checks. ${JSON.stringify(layouts)}`);
}finally{await browser.close();}

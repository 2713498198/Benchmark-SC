import assert from 'node:assert/strict';
import {runScenarios} from './scenarios.mjs';
import {mkdir,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {pathToFileURL,fileURLToPath} from 'node:url';
let pw;try{pw=await import('playwright');}catch{pw=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE||resolve(dirname(process.execPath),'../node_modules/playwright/index.mjs')));}
const browser=await pw.chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],checks=[];
page.on('pageerror',err=>errors.push(err.message));
const base=process.env.TEST_URL||'http://127.0.0.1:4173';
const artifactDir=process.env.TEST_ARTIFACT_DIR?resolve(process.env.TEST_ARTIFACT_DIR):fileURLToPath(new URL('./artifacts/',import.meta.url));
await mkdir(artifactDir,{recursive:true});
const check=(name,ok)=>{assert.ok(ok,name);checks.push(name);console.log('PASS '+name);};
const go=async r=>{await page.goto(base+'/#'+r);await page.waitForLoadState('networkidle');await page.waitForFunction(expected=>{try{expected=decodeURI(expected);}catch{expected='/404';}return document.querySelector('#main')?.dataset.route===expected;},r);await page.locator('#main h1').first().waitFor();};
const shot=async name=>{await page.evaluate(()=>{document.querySelector('#toast').classList.remove('show');window.scrollTo({top:0,behavior:'instant'});});await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));await page.screenshot({path:resolve(artifactDir,name+'.png'),fullPage:true,animations:'disabled',style:'#toast{display:none!important}'});};
try{
 await go('/');await shot('home-desktop');
 for(const r of ['/datasets','/datasets/mini-spn','/tools','/download','/docs','/leaderboard','/arena','/evaluate','/login','/register','/forgot-password','/workspace','/admin']){await go(r);check('route '+r,await page.locator('h1').innerText());}
 await go('/datasets');await page.locator('#dataset-search').fill('不存在');check('search empty',await page.locator('[data-clear-filters]').isVisible());await page.locator('[data-clear-filters]').click();check('reset filters',await page.locator('.resource-dataset-card').count()===5);
 await go('/leaderboard');await page.locator('[data-expand]').first().click();check('repro config',await page.locator('.leaderboard-details').isVisible());await page.locator('[data-compare]').nth(0).check();await page.locator('[data-compare]').nth(1).check();await page.locator('[data-open-compare]').click();check('compare',await page.locator('#modal').isVisible());await page.locator('[data-close-modal]').click();await shot('leaderboard-desktop');
 await go('/login');await page.locator('[data-demo-user="researcher"]').click();await page.locator('[data-logout]').waitFor();
 await go('/evaluate');await page.locator('[data-check-connection]').click();await page.locator('[data-eval-next]').click();await page.locator('[data-eval-next]').click();await page.locator('[data-eval-next]').click();await page.locator('a[href^="#/report/"]').waitFor();await page.locator('a[href^="#/report/"]').click();await page.locator('[data-report-json]').waitFor();check('report',await page.locator('[data-report-json]').isVisible());await shot('report-desktop');
 const r=await page.evaluate(()=>JSON.parse(localStorage.getItem('cryptobench.v1')).reports[0]);check('private default',!r.synced&&!r.published);check('result accounting',r.categories.reduce((n,c)=>n+c.total,0)===r.total&&r.categories.reduce((n,c)=>n+c.passed,0)===r.passed);
 await page.locator('[data-sync-report]').click();await page.locator('#confirm-sync').click();await page.locator('#modal').waitFor({state:'hidden'});await page.locator('[data-publish-report]').click();await page.locator('#confirm-publish').click();await page.locator('#modal').waitFor({state:'hidden'});await go('/leaderboard');check('publish',await page.locator('tbody').innerText().then(t=>t.includes('我的密码分析 Agent')));
 await go('/arena');await page.locator('[data-start-pk]').click();await page.locator('a[href^="#/report/"]').waitFor();await page.locator('a[href^="#/report/"]').click();await page.locator('.result-title').waitFor();check('pk',await page.locator('.result-title').isVisible());await shot('arena-report');
 await go('/login');await page.locator('[data-demo-user="admin"]').click();await page.locator('[data-template]').waitFor();await page.locator('[data-validate]').click();check('invalid import',await page.locator('#import-error').innerText());await page.locator('[data-template]').click();await page.locator('[data-validate]').click();await page.locator('[data-publish-dataset]').click();await page.locator('[data-publish-dataset]').waitFor({state:'hidden'});check('dataset import',await page.locator('#main').innerText().then(t=>t.includes('custom-spn')));
 await runScenarios({page,check,go,shot});
 await page.setViewportSize({width:390,height:844});for(const r of ['/','/datasets','/leaderboard','/arena','/evaluate','/login','/workspace','/docs?tab=agent','/download','/admin']){await go(r);check('mobile '+r,await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));}
 await go('/');await shot('home-mobile');await page.locator('.menu-toggle').click();check('mobile menu',await page.locator('.nav').isVisible());check('no errors',errors.length===0);check('no dotfile exposure',(await page.request.get(base+'/.env')).status()===403);
 await writeFile(resolve(artifactDir,'results.json'),JSON.stringify({passed:checks.length,checks,errors},null,2));
}finally{await browser.close();}


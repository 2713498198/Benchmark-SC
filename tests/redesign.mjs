import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';

let pw;try{pw=await import('playwright');}catch{pw=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE||resolve(dirname(process.execPath),'../node_modules/playwright/index.mjs')));}

// Use a separate server/database for acceptance tests; this creates test accounts.
const base=process.env.TEST_URL||'http://127.0.0.1:4173';
const out=resolve(process.env.TEST_ARTIFACT_DIR||'tmp/verification/redesign');
await mkdir(out,{recursive:true});
const browser=await pw.chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const errors=[],checks=[],layouts=[],contrasts=[];
const check=(name,ok)=>{assert.ok(ok,name);checks.push(name);};
async function client(){
 const context=await browser.newContext();
 const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
 return page;
}
async function post(page,path,body){const r=await page.request.post(base+path,{data:body});assert.ok(r.ok(),path+' '+r.status());return r.json();}
async function go(page,route){await page.goto(base+'/#'+route);await page.waitForLoadState('networkidle');await page.waitForFunction(expected=>document.querySelector('#main')?.dataset.route===expected,route);await page.locator('#main h1').first().waitFor();}
async function textContrast(page){return page.evaluate(()=>{
 const rgb=value=>value.match(/[\d.]+/g).map(Number);
 const blend=(fg,bg)=>{const a=fg[3]??1;return fg.slice(0,3).map((c,i)=>c*a+bg[i]*(1-a));};
 const luminance=color=>color.slice(0,3).map(c=>{const s=c/255;return s<=.04045?s/12.92:((s+.055)/1.055)**2.4;}).reduce((sum,c,i)=>sum+c*[.2126,.7152,.0722][i],0);
 const failures=[];let checked=0;
 const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
 while(walker.nextNode()){
  const text=walker.currentNode,el=text.parentElement;
  if(!text.textContent.trim()||!el.getClientRects().length||el.closest('svg,script,style,[disabled]'))continue;
  const style=getComputedStyle(el);
  if(style.visibility!=='visible')continue;
  const ancestors=[];let current=el;
  while(current){ancestors.unshift(current);current=current.parentElement;}
  if(ancestors.some(x=>Number(getComputedStyle(x).opacity)<1))continue;
  let bg=[255,255,255];for(const ancestor of ancestors)bg=blend(rgb(getComputedStyle(ancestor).backgroundColor),bg);
  const fg=blend(rgb(style.color),bg),a=luminance(fg),b=luminance(bg);
  const ratio=(Math.max(a,b)+.05)/(Math.min(a,b)+.05);
  const large=parseFloat(style.fontSize)>=24||(parseFloat(style.fontSize)>=18.66&&Number(style.fontWeight)>=700);
  checked++;
  if(ratio<(large?3:4.5))failures.push({text:text.textContent.trim().slice(0,50),ratio:Number(ratio.toFixed(2)),color:style.color,background:bg});
 }
 return {checked,failures};
});}
const guest=await client(),owner=await client(),admin=await client();
const suffix=randomUUID().slice(0,8),reportId='redesign-'+suffix;
try{
 await post(owner,'/api/auth/register',{name:'设计验收',email:`design-${suffix}@example.com`,password:randomUUID()});
 await post(owner,'/api/connections',{name:'密码分析 Agent',url:'http://127.0.0.1:9000',model:'research-model'});
 await post(owner,'/api/reports',{id:reportId,kind:'evaluation',datasetId:'mini-spn',version:'1.0.0',budget:'standard',scope:'full',source:'local',agent:'密码分析 Agent',model:'research-model',total:120,passed:96,score:80,time:42.6,cost:null,date:'2026/10/18',demo:true,categories:[{name:'任务验证',total:120,passed:96}]});
 await post(admin,'/api/auth/demo',{role:'admin'});
 const scenarios=[
  [guest,'home','/'],[guest,'datasets','/datasets'],[guest,'dataset-detail','/datasets/mini-spn'],
  [guest,'suite','/datasets/cryptobench-sc-v4',async page=>{await page.locator('nav [data-suite-tab="overview"]').click();}],
  [guest,'task','/datasets/cryptobench-sc-v4?version=4.0.0&task=127_CBSC-V4-PL-PRESENT64-ZC6-007'],
  [guest,'tools','/tools'],[guest,'evaluate','/evaluate'],[guest,'arena','/arena'],[guest,'leaderboard','/leaderboard'],
  [guest,'login','/login'],[guest,'register','/register'],[guest,'forgot','/forgot-password'],
  [guest,'docs','/docs?tab=agent'],[guest,'download','/download'],
  [owner,'workspace','/workspace'],[owner,'connections','/workspace?tab=connections'],[owner,'sync','/workspace?tab=sync'],
  [owner,'report','/report/'+reportId],
  [admin,'admin','/admin',async page=>{
   await page.locator('[data-template]').click();
   const pack=JSON.parse(await page.locator('#dataset-json').inputValue());pack.id='layout-'+suffix;
   await page.locator('#dataset-json').fill(JSON.stringify(pack,null,2));await page.locator('[data-validate]').click();
   await page.locator('[data-publish-dataset]').waitFor();
  }]
 ];
 for(const width of [1440,1024,768,390,360]){
  for(const [page,name,route,prepare] of scenarios){
   await page.setViewportSize({width,height:width<640?844:1000});
   await go(page,route);if(prepare)await prepare(page);
   const layout=await page.evaluate(()=>({viewport:innerWidth,scroll:document.documentElement.scrollWidth}));
   check(`${name} fits ${width}px`,layout.scroll<=layout.viewport+1);
   check(`${name} has one title at ${width}px`,await page.locator('#main h1').count()===1);
   layouts.push({name,width,...layout});
   if(width===1440){const contrast=await textContrast(page);contrasts.push({name,...contrast});check(`${name} text contrast: ${JSON.stringify(contrast.failures)}`,contrast.failures.length===0);}
   if(width===1440||width===390){
    if(name==='home'){await page.locator('.cipher-viewport').focus();await page.keyboard.press('Home');await page.locator('.cipher-viewport').evaluate(el=>el.blur());}
    await page.evaluate(()=>window.scrollTo({top:0,behavior:'instant'}));
    await page.evaluate(()=>new Promise(done=>requestAnimationFrame(()=>requestAnimationFrame(done))));
    await page.screenshot({path:resolve(out,`${name}-${width}.png`),fullPage:true,animations:'disabled',style:'#toast{display:none!important}'});
   }
  }
  // The remaining wizard steps must also reflow, including their long selects.
  await go(owner,'/evaluate?new=1');
  await owner.locator('[data-check-connection]').click();await owner.locator('[data-eval-next]').click();
  check(`task settings fit ${width}px`,await owner.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await owner.locator('[data-eval-next]').click();
  check(`confirmation fits ${width}px`,await owner.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
 }
 await guest.setViewportSize({width:1440,height:1000});await go(guest,'/');
 check('home has hero and platform sections',await guest.locator('#main > section').count()===2);
 check('home no longer shows the dataset release module',await guest.locator('.release-band').count()===0);
 const homeText=await guest.locator('#main').innerText();
 check('home copy reduced by at least half',homeText.replace(/\s/g,'').length<=700);
 check('geometry contains no text labels',await guest.locator('.cipher-sculpture text').count()===0);
 check('geometry has no separate controls block',await guest.locator('.cipher-controls,[data-cipher-motion]').count()===0);
 const leaderboardLink=guest.getByRole('link',{name:'浏览测评榜单',exact:true});
 check('hero links to leaderboard',await leaderboardLink.getAttribute('href')==='#/leaderboard');
 await leaderboardLink.click();await guest.waitForFunction(()=>document.querySelector('#main').dataset.route==='/leaderboard');
 check('hero opens leaderboard',await guest.locator('#main h1').innerText()==='测评榜单');
 await go(guest,'/');
 const geometry=guest.locator('.cipher-object'),stage=guest.locator('.cipher-viewport');
 const transform=()=>geometry.evaluate(el=>getComputedStyle(el).transform);
 const rotating=await transform();await guest.waitForTimeout(160);
 check('geometry rotates in three dimensions',await transform()!==rotating&&(await transform()).startsWith('matrix3d'));
 await stage.click();const stopped=await transform();await guest.waitForTimeout(120);
 check('pause stops rotation',await transform()===stopped&&await stage.getAttribute('aria-pressed')==='true');
 await stage.focus();await guest.keyboard.press('ArrowRight');
 check('keyboard rotates geometry',await transform()!==stopped);
 const beforeDrag=await transform(),bounds=await stage.boundingBox();
 await guest.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);
 await guest.mouse.down();await guest.mouse.move(bounds.x+bounds.width/2+80,bounds.y+bounds.height/2-20,{steps:5});await guest.mouse.up();
 check('drag rotates geometry',await transform()!==beforeDrag);
 await stage.press('Space');
 // The shorter homepage needs a compact viewport to scroll the full scene out of view.
 await guest.setViewportSize({width:390,height:600});
 await guest.evaluate(()=>window.scrollTo({top:document.body.scrollHeight,behavior:'instant'}));
 await guest.waitForFunction(()=>document.querySelector('.cipher-viewport').getBoundingClientRect().bottom<0);
 await guest.waitForTimeout(100);const offscreen=await transform();await guest.waitForTimeout(120);
 check('offscreen geometry stops animating',await transform()===offscreen);
 await guest.setViewportSize({width:1440,height:1000});
 // Start a new document so Chromium does not restore the last focused control.
 await guest.goto('about:blank');await go(guest,'/');
 await guest.keyboard.press('Tab');
 check('keyboard skip link is reachable',await guest.locator('.skip-link').evaluate(el=>document.activeElement===el&&el.getBoundingClientRect().top>=0));
 await guest.keyboard.press('Enter');
 check('skip link focuses main',await guest.locator('#main').evaluate(el=>document.activeElement===el));
 await guest.emulateMedia({reducedMotion:'reduce'});
 check('reduced motion disables smooth scrolling',await guest.evaluate(()=>getComputedStyle(document.documentElement).scrollBehavior==='auto'));
 check('reduced motion disables button transitions',await guest.locator('.hero-actions .btn').first().evaluate(el=>getComputedStyle(el).transitionDuration==='0s'));
 await guest.waitForTimeout(50);const reduced=await transform();await guest.waitForTimeout(120);
 check('reduced motion stops geometry',await transform()===reduced&&await stage.getAttribute('aria-pressed')==='true');
 await guest.setViewportSize({width:390,height:844});await go(guest,'/');
 await guest.locator('.menu-toggle').click();
 check('mobile menu announces expanded state',await guest.locator('.menu-toggle').getAttribute('aria-expanded')==='true');
 await guest.locator('.nav a[href="#/datasets"]').click();await guest.locator('#dataset-search').waitFor();
 check('mobile navigation closes after selecting a page',await guest.locator('.menu-toggle').getAttribute('aria-expanded')==='false');
 await go(guest,'/docs?tab=agent');
 const disclosure=guest.locator('.code-disclosure').first();
 check('code starts collapsed',await disclosure.getAttribute('open')===null);
 await disclosure.locator('summary').click();
 check('complete example is readable',await disclosure.locator('pre').isVisible()&&(await disclosure.locator('pre').innerText()).includes('task_id'));
 await disclosure.locator('[data-copy-example]').click();
 await guest.waitForFunction(()=>document.querySelector('#toast').textContent.includes('复制'));
 check('copy action responds',await guest.locator('#toast').innerText().then(x=>x.includes('复制')));
 await go(owner,'/report/'+reportId);
 check('private report status remains explicit',await owner.locator('#main').innerText().then(x=>x.includes('私有')));
 const config=owner.locator('.report-config');
 check('reproduction config starts collapsed',await config.getAttribute('open')===null);
 await config.locator('summary').click();
 check('complete reproduction config is available',await config.innerText().then(x=>x.includes('research-model')&&x.includes('1.0.0')));
 await go(guest,'/download');await guest.locator('[data-download-tab="deploy"]').click();
 check('deployment commands retained',await guest.locator('.copy-line').innerText().then(x=>x.includes('docker compose up -d')));
 check('no browser errors',errors.length===0);
 await writeFile(resolve(out,'results.json'),JSON.stringify({passed:checks.length,checks,layouts,contrasts,errors,homeCharacters:homeText.replace(/\s/g,'').length},null,2));
 console.log(`PASS ${checks.length} redesign checks; ${layouts.length} page/viewport combinations. Screenshots: ${out}`);
}finally{await browser.close();}

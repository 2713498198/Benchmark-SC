import assert from 'node:assert/strict';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {chromium} from 'playwright';

// Repeatable, read-only captures: identical guest state and data in both passes.
const phase=process.argv[2]||'after';
assert.ok(['before','after'].includes(phase));
const base=process.env.TEST_URL||'http://127.0.0.1:4173';
const root=resolve('output/design-comparison');
const out=resolve(root,phase);
await mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const errors=[],checks=[],geometry={};
try{
 for(const [width,height] of [[1440,900],[390,844],[360,800]]){
  const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:1});
  page.on('pageerror',error=>errors.push(error.message));
  page.on('console',message=>{if(message.type()==='error')errors.push(message.text());});
  await page.clock.setFixedTime(new Date('2026-10-23T10:00:00Z'));
  await page.route('**/api/bootstrap',route=>route.fulfill({json:{user:null,reports:[],connections:[],customDatasets:[],publicReports:[]}}));
  await page.route('**/api/reports/public',route=>route.fulfill({json:[]}));
  async function go(route){await page.goto(base+'/#'+route);await page.waitForLoadState('networkidle');await page.evaluate(()=>document.fonts.ready);await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));}
  async function shot(name,locator){await (locator||page).screenshot({path:resolve(out,`${name}-${width}.png`),animations:'disabled',...(locator?{style:'.topbar{visibility:hidden}'}:{})});}
  async function noOverflow(){assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`${width}: no page overflow`);}
  await go('/');
  geometry[width]=await page.evaluate(()=>{
   const hero=document.querySelector('.home-hero').getBoundingClientRect(),chart=document.querySelector('[data-home-cost-chart]').getBoundingClientRect();
   return {heroTop:hero.top,heroHeight:hero.height,chartTop:chart.top};
  });
  await noOverflow();await shot('home-hero');await shot('home-chart',page.locator('[data-home-cost-chart]'));
  if(phase==='after'){
   assert.equal(await page.locator('h1').innerText(),'CryptoBench');
   assert.equal(await page.locator('.hero-title-inverse[aria-hidden="true"]').count(),1);
   assert.equal(await page.locator('.home-frontier li').count(),3);
   assert.equal(await page.locator('#home-cost-title').innerText(),'测评榜单');
   assert.ok(geometry[width].chartTop>=height-1);
   checks.push(`${width}: simplified hero, frontier list and chart below first screen`);
  }
  await go('/leaderboard');await noOverflow();await shot('leaderboard');
  if(phase==='after'){
   const filter=page.locator('.rank-filter-disclosure');
   if(width<=640){
    assert.equal(await page.locator('[data-rank="budget"]').isVisible(),false);
    await filter.locator('summary').click();
    assert.equal(await page.locator('[data-rank="budget"]').isVisible(),true);
    await page.locator('[data-rank="budget"]').selectOption('extended');
    assert.ok((await page.locator('.rank-context-main').innerText()).includes('扩展预算'));
    await page.locator('[data-rank="budget"]').selectOption('standard');
    await filter.locator('summary').click();
   }else assert.equal(await page.locator('[data-rank="budget"]').isVisible(),true);
   await page.locator('#rank-search').fill('DeepSeek');
   assert.equal(await page.locator('[data-compare]').count(),1);
   await page.locator('#rank-search').fill('');
   await page.locator('[data-rank="sort"]').selectOption('cost');
   await page.locator('[data-rank="sort"]').selectOption('score');
   await page.locator('[data-compare]').nth(0).check();
   await page.locator('[data-compare]').nth(1).check();
   assert.ok(await page.locator('.compare-bar').isVisible());
   await page.locator('[data-compare]').nth(0).uncheck();
   await page.locator('[data-compare]').nth(1).uncheck();
   checks.push(`${width}: filters, search, sort, selection work`);
  }
  await page.locator('[data-rank-view="analysis"]').click();
  await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));await noOverflow();await shot('analysis');
  await shot('analysis-full',page.locator('.leaderboard-page'));
  if(phase==='after'){
   await page.locator('[data-chart-point]').first().focus();
   assert.ok(await page.locator('.rank-chart-tooltip').isVisible());
   await page.keyboard.press('Escape');
   assert.ok(await page.locator('.rank-chart-tooltip').isHidden());
   assert.equal(await page.locator('.rank-chart').evaluate(el=>getComputedStyle(el).borderTopWidth),'0px');
   checks.push(`${width}: keyboard details and borderless shared chart`);
  }
  await page.close();
 }
 if(phase==='after'){
  const before=JSON.parse(await readFile(resolve(root,'before/geometry.json'),'utf8'));
  assert.deepEqual(geometry,before,'First-screen heights and chart start positions stay unchanged');
  checks.push('All viewport hero heights and chart start positions match baseline');
 }
 assert.deepEqual(errors,[]);
 await writeFile(resolve(out,'geometry.json'),JSON.stringify(geometry,null,2));
 await writeFile(resolve(out,'results.json'),JSON.stringify({phase,checks,errors},null,2));
 if(phase==='after'){
  const pages=[['home-hero','首页首屏'],['home-chart','首页榜单区'],['leaderboard','排行榜'],['analysis-full','性能分析']];
  const panels=[];
  for(const width of [1440,390,360])for(const [key,label] of pages){
   const filename=`${key}-${width}.png`,png=await readFile(resolve(root,'before',filename));
   const pixels=png.readUInt32BE(16),id=`${key}-${width}`;
   panels.push({id,pixels,html:`<article id="${id}" data-size="${width}" style="--pixels:${pixels}px"><header><h2>${label}</h2><span>${width}px · 相同数据与筛选条件</span></header><div class="pair">${['before','after'].map((phase,i)=>`<figure><figcaption>${i?'改版后':'改版前'}</figcaption><a href="${phase}/${filename}" target="_blank"><img src="${phase}/${filename}" alt="${label} · ${i?'改版后':'改版前'}" width="${pixels}"></a></figure>`).join('')}</div></article>`});
  }
  await writeFile(resolve(root,'index.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CryptoBench · 改版对比</title><style>
  *{box-sizing:border-box}body{margin:0;background:#f5f5f7;color:#1d1d1f;font:14px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif}main{max-width:1600px;margin:auto;padding:32px 24px}h1,h2,p{margin:0}h1{font-size:28px;letter-spacing:-.8px}h2{font-size:20px}p,header>span{color:#6e6e73}nav{display:flex;gap:12px;margin:20px 0 32px}button{border:0;border-radius:20px;background:#fff;padding:10px 18px;font:inherit;cursor:pointer}button[aria-pressed=true]{background:#1d1d1f;color:#fff}article{background:#fff;padding:24px;margin-bottom:32px;border-radius:16px}article>header{display:flex;justify-content:space-between;align-items:baseline;gap:20px;margin-bottom:20px}.pair{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24px}figure{margin:0;min-width:0}figcaption{font-size:13px;color:#6e6e73;padding:0 0 16px}img{display:block;max-width:100%;height:auto;width:100%}a{display:block;color:inherit}body.capture main{max-width:none;padding:0}body.capture .intro,body.capture nav{display:none}body.capture article{width:calc(var(--pixels) * 2 + 72px);margin:0;border-radius:0}body.capture article:not(.active){display:none}article[hidden]{display:none}@media(max-width:640px){main{padding:20px 12px}article{padding:16px}.pair{gap:12px}article>header{display:block}h2{font-size:18px}}
  </style><main><div class="intro"><h1>CryptoBench · 改版对比</h1><p>真实浏览器截图。左侧改版前，右侧改版后；点击图片查看原图。</p></div><nav aria-label="屏幕尺寸"><button data-width="1440" aria-pressed="true">桌面</button><button data-width="390" aria-pressed="false">手机</button><button data-width="360" aria-pressed="false">窄屏</button></nav>${panels.map(p=>p.html).join('')}</main><script>const choose=width=>{document.querySelectorAll('article').forEach(el=>el.hidden=el.dataset.size!==width);document.querySelectorAll('button').forEach(el=>el.setAttribute('aria-pressed',String(el.dataset.width===width)))};document.querySelectorAll('button').forEach(el=>el.onclick=()=>choose(el.dataset.width));choose('1440');</script></html>`);
  const comparison=await browser.newPage({deviceScaleFactor:1});
  await comparison.goto(pathToFileURL(resolve(root,'index.html')).href);
  for(const {id,pixels} of panels){
   await comparison.setViewportSize({width:pixels*2+72,height:1000});
   await comparison.evaluate(id=>{document.body.classList.add('capture');document.querySelectorAll('article').forEach(el=>{el.classList.toggle('active',el.id===id);el.hidden=el.id!==id});},id);
   await comparison.locator('#'+id).screenshot({path:resolve(root,`compare-${id}.png`),animations:'disabled'});
  }
  await comparison.close();
 }
 console.log(JSON.stringify({phase,captures:out,checks:checks.length,errors}));
}finally{await browser.close();}

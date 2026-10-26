import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdir,writeFile} from 'node:fs/promises';
import {chromium} from 'playwright';

// Uses real APIs and writes test accounts/reports: run against an isolated database.
const base=process.env.TEST_URL||'http://127.0.0.1:4173';
const output='tmp/verification/spring-boot';
await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
const page=await context.newPage();
const errors=[];
page.on('pageerror',error=>errors.push(error.message));
page.on('response',response=>{
  const expectedAuthBoundary=response.status()===401&&response.url().endsWith('/api/agent/runtime');
  if(response.status()>=400&&!expectedAuthBoundary)errors.push(`${response.status()} ${response.url()}`);
});
async function go(route){
  await page.goto(base+'/#'+route);
  await page.waitForLoadState('networkidle');
  await page.locator('#main h1').first().waitFor();
}
try{
  await go('/');
  assert.match(await page.locator('h1').innerText(),/CryptoBench/);
  await page.screenshot({path:output+'/home-desktop.png',fullPage:true});
  for(const route of ['/datasets','/tools','/leaderboard','/arena','/evaluate','/download','/docs'])await go(route);
  await go('/download');
  await page.locator('[data-download-tab="deploy"]').click();
  assert.match(await page.locator('#main').innerText(),/Spring Boot/);
  const downloadEvent=page.waitForEvent('download');
  await page.locator('[data-download="compose"]').click();
  await (await downloadEvent).saveAs(output+'/compose.yaml');

  await go('/register');
  const email=`browser-${randomUUID()}@example.com`;
  await page.locator('#auth-form [name="name"]').fill('Browser Researcher');
  await page.locator('#auth-form [name="email"]').fill(email);
  await page.locator('#auth-form [name="password"]').fill('browser-test-password');
  await page.locator('#auth-form [type="submit"]').click();
  await page.locator('[data-logout]').waitFor();
  await go('/workspace?tab=connections');
  await page.locator('[data-add-connection]').click();
  await page.locator('#connection-form [name="name"]').fill('Browser Agent');
  await page.locator('#connection-form [name="url"]').fill('http://127.0.0.1:9000');
  await page.locator('#connection-form [name="model"]').fill('test-model');
  await page.locator('#connection-form [type="submit"]').click();
  await page.locator('.connection-card').waitFor();
  await page.reload();
  await page.locator('.connection-card').waitFor();

  await go('/evaluate?dataset=mini-spn&version=1.0.0&new=1');
  await page.locator('[data-check-connection]').click();
  const runCreated=page.waitForResponse(response=>response.url().endsWith('/api/runs')&&response.request().method()==='POST');
  for(let step=0;step<3;step++)await page.locator('[data-eval-next]').click();
  assert.equal((await runCreated).status(),202);
  await page.locator('a[href^="#/report/"]').click();
  await page.locator('[data-report-json]').waitFor();
  assert.match(await page.locator('.report-usage').innerText(),/真实模型用量/);
  const reportId=page.url().split('/report/')[1];
  await page.reload();
  await page.locator('[data-report-json]').waitFor();
  await page.locator('[data-sync-report]').click();
  await page.locator('#confirm-sync').click();
  await page.locator('#modal').waitFor({state:'hidden'});
  await page.locator('[data-publish-report]').click();
  await page.locator('#confirm-publish').click();
  await page.locator('#modal').waitFor({state:'hidden'});
  await page.reload();
  await page.locator('[data-publish-report]:disabled').waitFor();
  const published=await context.request.get(base+'/api/reports/'+reportId);
  assert.equal((await published.json()).published,true);

  // Preserve identifiers for an optional service restart/persistence check.
  await writeFile(output+'/fixture.json',JSON.stringify({email,reportId}));
  await go('/workspace');
  await page.locator('[data-logout]').click();
  await page.waitForURL('**/#/');
  const anonymous=await browser.newContext();
  assert.equal((await anonymous.request.get(base+'/api/reports/'+reportId)).status(),200);
  assert.equal((await anonymous.request.get(base+'/api/connections')).status(),401);
  await anonymous.close();
  await page.setViewportSize({width:390,height:844});
  for(const route of ['/','/datasets','/leaderboard','/arena','/evaluate','/login','/docs','/download']){
    await go(route);
    const width=await page.evaluate(()=>({viewport:innerWidth,scroll:document.documentElement.scrollWidth}));
    assert.ok(width.scroll<=width.viewport+1,`${route}: ${width.scroll}px > ${width.viewport}px`);
  }
  await go('/');
  await page.screenshot({path:output+'/home-mobile.png',fullPage:true});
  assert.deepEqual(errors,[]);
  console.log('PASS Spring Boot browser integration: routes, deployment download, registration, CSRF, persisted connections/reports, publication, logout and mobile layouts');
}finally{await browser.close();}

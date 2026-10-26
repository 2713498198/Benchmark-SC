import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {chromium} from 'playwright';

const base=(process.env.TEST_URL||'http://127.0.0.1:4173').replace(/\/$/,'');
const executablePath=process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe';
const browser=await chromium.launch({headless:true,executablePath});
const context=await browser.newContext({viewport:{width:1440,height:900}});
const page=await context.newPage();
const errors=[];
page.on('pageerror',error=>errors.push(error.message));
try{
  await page.goto(base+'/#/login');
  await page.waitForLoadState('networkidle');
  await page.goto(base+'/#/register');
  const email=`docker-ui-${randomUUID()}@example.com`;
  await page.locator('#auth-form [name="name"]').fill('Docker UI Researcher');
  await page.locator('#auth-form [name="email"]').fill(email);
  await page.locator('#auth-form [name="password"]').fill('docker-ui-test-password');
  await page.locator('#auth-form [type="submit"]').click();
  await page.locator('[data-logout]').waitFor();
  const runtimeResponse=page.waitForResponse(response=>response.url().endsWith('/api/agent/runtime'));
  await page.goto(base+'/#/evaluate?new=1');
  await page.waitForLoadState('networkidle');
  await page.locator('#execution-source').selectOption('docker');
  const runtime=await (await runtimeResponse).json();
  await page.locator('#docker-image').waitFor();
  assert.equal(await page.locator('[aria-label="Agent 服务地址"]').count(),0);
  assert.match(await page.locator('.callout').first().innerText(),/Docker/);
  const selectedImage=await page.locator('#docker-image').inputValue();
  const image=runtime.allowedImages?.find(item=>item.name===selectedImage);
  const expectedDisabled=!(runtime.dockerAvailable===true&&image?.available===true);
  assert.equal(await page.locator('[data-check-connection]').isDisabled(),expectedDisabled);
  await page.goto(base+'/#/');
  await page.waitForLoadState('networkidle');
  assert.deepEqual(errors,[]);
  console.log(`PASS Docker UI source selection (${runtime.message})`);
}finally{await browser.close();}

import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {existsSync} from 'node:fs';
import {chromium} from 'playwright';

assert.equal(process.env.TEST_ISOLATED,'1','Start a separate test database, then set TEST_ISOLATED=1');
assert.ok(process.env.TEST_URL,'TEST_URL is required');
const base=process.env.TEST_URL.replace(/\/$/,'');

const installed='C:/Program Files/Google/Chrome/Application/chrome.exe';
const browser=await chromium.launch({headless:true,...(
  process.env.CHROME_PATH||existsSync(installed)?{executablePath:process.env.CHROME_PATH||installed}:{}
)});
const page=await browser.newPage({viewport:{width:1440,height:1000}});
const errors=[];
page.on('pageerror',error=>errors.push(error.message));
const suffix=randomUUID().slice(0,8);
const account={name:'集成测试用户',email:`workflow-${suffix}@example.com`,password:'workflow-test-password'};

async function go(route){
  await page.goto(base+'/#'+route);
  await page.waitForLoadState('networkidle');
  await page.locator('#main h1').first().waitFor();
}

try{
  await go('/register');
  await page.locator('[name="name"]').fill(account.name);
  await page.locator('[name="email"]').fill(account.email);
  await page.locator('[name="password"]').fill(account.password);
  await page.locator('#auth-form [type="submit"]').click();
  await page.waitForURL(/#\/workspace$/);
  await page.locator('[data-logout]').waitFor();
  assert.match(await page.locator('#main h1').innerText(),new RegExp(account.name));

  await page.reload();
  await page.locator('[data-logout]').waitFor();
  assert.equal(await page.locator('.nav-end a').innerText(),'工作台');

  await page.locator('[data-logout]').click();
  await page.waitForURL(/#\/$/);
  await go('/login');
  assert.equal(await page.locator('[name="token"]').count(),0,'legacy token reset form is removed');
  assert.equal(await page.getByRole('link',{name:'管理员邀请',exact:true}).count(),0);
  await page.locator('[name="email"]').fill(account.email);
  await page.locator('[name="password"]').fill(account.password);
  await page.locator('#auth-form [type="submit"]').click();
  await page.waitForURL(/#\/workspace$/);
  await page.locator('[data-logout]').waitFor();

  await go('/admin');
  assert.equal(await page.locator('#main h1').innerText(),'无权限访问');
  await go('/forgot-password');
  await page.locator('[data-password-recovery]').waitFor();
  await page.locator('#recovery-email').waitFor();
  assert.equal(await page.locator('#recovery-email').count(),1);
  assert.equal(await page.locator('#recovery-code').count(),0);
  assert.deepEqual(errors,[]);
  console.log('PASS isolated email/password registration, session persistence, logout/login, forgotten-password entry and removed account administration UI.');
}finally{
  await browser.close();
}

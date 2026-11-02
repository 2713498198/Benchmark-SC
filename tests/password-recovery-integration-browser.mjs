import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {existsSync} from 'node:fs';
import {readFile,mkdir} from 'node:fs/promises';
import {chromium} from 'playwright';

assert.equal(process.env.TEST_ISOLATED,'1','Use the dedicated runner with its own database');
assert.ok(process.env.TEST_URL&&process.env.TEST_MAIL_OUTBOX&&process.env.TEST_SERVER_LOG,'An isolated URL, local test outbox and server log are required');
const base=process.env.TEST_URL.replace(/\/$/,''),url=new URL(base);
assert.ok(['127.0.0.1','localhost','[::1]'].includes(url.hostname),'Recovery integration requires a loopback test server');
assert.notEqual(url.port,'4173','Do not run against the everyday application');
const email='recovery-'+randomUUID()+'@example.org',oldPassword='old-fixture-2026',newPassword='new-fixture-2026';
const installed='C:/Program Files/Google/Chrome/Application/chrome.exe';
let browser,page;const errors=[],requestUrls=[];
async function post(context,path,body){
 const csrf=await(await context.request.get(base+'/api/auth/csrf')).json();
 return context.request.post(base+path,{data:body,headers:{[csrf.headerName]:csrf.token}});
}
async function waitCode(){
 const deadline=Date.now()+15000;
 while(Date.now()<deadline){
  let entries=[];try{entries=(await readFile(process.env.TEST_MAIL_OUTBOX,'utf8')).trim().split(/\r?\n/).filter(Boolean).map(line=>JSON.parse(line));}catch(error){if(error.code!=='ENOENT'&&!(error instanceof SyntaxError))throw error;}
  const message=entries.filter(entry=>entry.type==='password_reset_code'&&entry.email===email).at(-1);
  if(message){assert.match(message.code,/^\d{6}$/);return message.code;}
  await new Promise(resolve=>setTimeout(resolve,100));
 }
 throw Error('No password recovery message appeared in the isolated outbox');
}
try{
 browser=await chromium.launch({headless:true,...((process.env.CHROME_PATH||existsSync(installed))?{executablePath:process.env.CHROME_PATH||installed}:{})});
 const oldSession=await browser.newContext(),oldPage=await oldSession.newPage();oldPage.on('pageerror',error=>errors.push(error.message));
 await oldPage.goto(base+'/#/register');await oldPage.locator('[name="name"]').fill('Recovery browser fixture');await oldPage.locator('[name="email"]').fill(email);await oldPage.locator('[name="password"]').fill(oldPassword);
 await oldPage.locator('#auth-form [type="submit"]').click();await oldPage.waitForURL(/#\/workspace$/);
 assert.equal((await(await oldSession.request.get(base+'/api/auth/me')).json()).user.email,email,'non-QQ registration succeeds');
 const otherSession=await browser.newContext();assert.equal((await post(otherSession,'/api/auth/login',{email,password:oldPassword})).status(),200);
 const recovery=await browser.newContext({viewport:{width:1280,height:1000}});page=await recovery.newPage();page.on('pageerror',error=>errors.push(error.message));page.on('request',request=>requestUrls.push(request.url()));
 await page.goto(base+'/#/forgot-password');await page.locator('#recovery-email').fill(email);
 const requested=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/auth/forgot');await page.locator('[data-recovery-send]').click();const response=await requested;assert.equal(response.status(),200);
 const requestedBody=await response.json();assert.equal(requestedBody.ok,true);assert.equal(requestedBody.expiresInSeconds,600);assert.equal(requestedBody.retryAfterSeconds,60);assert.equal('code' in requestedBody,false);
 await page.locator('#recovery-code').waitFor();assert.match(await page.locator('[data-recovery-notice]').innerText(),/如果/);
 const code=await waitCode();await page.locator('#recovery-code').fill(code);await page.getByRole('button',{name:'下一步',exact:true}).click();await page.locator('#recovery-password').waitFor();
 const cookie=(await recovery.cookies(base+'/api/auth/reset/status')).find(item=>item.name==='cb_reset_grant');
 assert.ok(cookie?.httpOnly,'reset authorization uses an HttpOnly cookie');assert.equal(cookie.path,'/api/auth/reset');
 assert.equal((await page.evaluate(()=>document.cookie)).includes('cb_reset_grant'),false);
 assert.equal((await(await recovery.request.get(base+'/api/auth/me')).json()).user,null,'code verification is not login');
 await page.locator('#recovery-password').fill('draft-not-persisted');await page.reload();await page.locator('#recovery-password').waitFor();
 assert.equal(await page.locator('#recovery-password').inputValue(),'');assert.equal(await page.locator('#recovery-confirm').inputValue(),'');
 const status=await(await recovery.request.get(base+'/api/auth/reset/status')).json();assert.equal(status.canReset,true);assert.equal(status.challengeId,requestedBody.challengeId);
 assert.notEqual(status.maskedEmail,email);assert.ok(status.maskedEmail.includes('@'));
 await mkdir('tmp/verification/password-recovery-live',{recursive:true});await page.screenshot({path:'tmp/verification/password-recovery-live/verified-after-refresh.png',fullPage:true});
 await page.locator('#recovery-password').fill(newPassword);await page.locator('#recovery-confirm').fill(newPassword);
 const completed=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/auth/reset/complete');await page.getByRole('button',{name:'确认重置密码',exact:true}).click();assert.equal((await completed).status(),200);await page.waitForURL(/#\/login$/);
 assert.equal((await(await recovery.request.get(base+'/api/auth/me')).json()).user,null,'successful reset requires manual login');
 assert.equal((await(await recovery.request.get(base+'/api/auth/reset/status')).json()).canReset,false);
 assert.equal((await(await oldSession.request.get(base+'/api/auth/me')).json()).user,null,'original browser session revoked');
 assert.equal((await(await otherSession.request.get(base+'/api/auth/me')).json()).user,null,'all other sessions revoked');
 assert.equal((await post(otherSession,'/api/auth/login',{email,password:oldPassword})).status(),401,'old password no longer works');
 await page.locator('[name="email"]').fill(email);await page.locator('[name="password"]').fill(newPassword);await page.locator('#auth-form [type="submit"]').click();await page.waitForURL(/#\/workspace$/);
 assert.equal((await(await recovery.request.get(base+'/api/auth/me')).json()).user.email,email);
 const stored=await page.evaluate(()=>Object.values(localStorage).join('')+Object.values(sessionStorage).join(''));
 for(const secret of [newPassword,oldPassword,code,'draft-not-persisted'])assert.ok(!stored.includes(secret),'recovery secrets stay out of browser storage');
 for(const secret of [code,newPassword])assert.ok(!requestUrls.some(value=>value.includes(secret)),'recovery secrets stay out of request URLs');
 // The server's stdout pipe stays open during this check. Complete a later request,
 // then allow the parent runner's write stream to flush all preceding debug logs.
 assert.equal((await recovery.request.get(base+'/actuator/health')).status(),200);
 await new Promise(resolve=>setTimeout(resolve,250));
 const serverLog=await readFile(process.env.TEST_SERVER_LOG,'utf8');
 assert.ok(serverLog.length>0,'isolated server diagnostics must be available for secret checks');
 for(const [label,secret] of [['verification code',code],['old password',oldPassword],['new password',newPassword],['reset grant',cookie.value]]){
  assert.ok(secret,'fixture '+label+' must exist');
  assert.ok(!serverLog.includes(secret),'server logs must not contain the '+label);
 }
 assert.deepEqual(errors,[]);
 console.log('PASS real isolated recovery: non-QQ registration, local outbox code, HttpOnly grant, refresh step 2, new password login, old password rejection, all-session revocation and secret-free server logs.');
}catch(error){console.error({pageErrors:errors,url:page?.url()});throw error;}
finally{await browser?.close();}

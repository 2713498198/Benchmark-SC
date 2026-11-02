import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import path from 'node:path';
import {chromium} from 'playwright';

// Static assets and intercepted APIs only: no running application, database, or real email.
const root=path.resolve('src/main/resources/static');
const server=createServer(async(req,res)=>{try{const pathname=new URL(req.url,'http://localhost').pathname,file=path.resolve(root,'.'+(pathname==='/'?'/index.html':decodeURIComponent(pathname)));if(!file.startsWith(root+path.sep))throw Error();const data=await readFile(file);res.writeHead(200,{'Content-Type':({'.js':'text/javascript','.css':'text/css','.html':'text/html','.json':'application/json','.svg':'image/svg+xml'})[path.extname(file)]||'application/octet-stream'});res.end(data);}catch{res.writeHead(404);res.end();}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base='http://127.0.0.1:'+server.address().port,installed='C:/Program Files/Google/Chrome/Application/chrome.exe';
let browser,page;const errors=[],requests=[];let user=null,grant=null,sequence=0,challengeId='',rateLimited=false,slowEmail='',releaseSlow,slowStarted;
const grantFor=id=>({canReset:true,challengeId:id,maskedEmail:'r***@example.org',expiresAt:new Date(Date.now()+3600000).toISOString()});
try{
 browser=await chromium.launch({headless:true,...((process.env.CHROME_PATH||existsSync(installed))?{executablePath:process.env.CHROME_PATH||installed}:{})});
 const context=await browser.newContext({viewport:{width:1280,height:1000}});page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.clock.install();
 await page.route('**/api/**',async route=>{
  const request=route.request(),p=new URL(request.url()).pathname,method=request.method(),body=request.postDataJSON();requests.push({p,method,body,headers:request.headers()});
  const json=(value,status=200)=>route.fulfill({status,json:value});
  if(p==='/api/auth/csrf')return json({headerName:'X-CSRF-TOKEN',token:'recovery-fixture'});
  if(p==='/api/bootstrap')return json({user,reports:[],connections:[],customDatasets:[],publicReports:[],demoEnabled:false});
  if(p==='/api/auth/reset/status')return json(grant||{canReset:false,challengeId:null,maskedEmail:null,expiresAt:null});
  if(p==='/api/auth/forgot'){
   if(rateLimited)return json({error:'请求太频繁',code:'reset_rate_limited',retryAfterSeconds:37},429);
   const id='challenge-'+(++sequence);challengeId=id;
   if(body.email===slowEmail){slowStarted?.();await new Promise(resolve=>releaseSlow=resolve);}
   try{return await json({ok:true,challengeId:id,expiresInSeconds:600,retryAfterSeconds:60});}catch{return;}
  }
  if(p==='/api/auth/reset/verify'){
   if(body.code!=='123456')return json({error:'验证码不正确',code:'reset_code_invalid'},400);
   grant=grantFor(body.challengeId);return json({ok:true});
  }
  if(p==='/api/auth/reset/complete'){grant=null;user=null;return json({ok:true});}
  if(p==='/api/auth/reset')return json({ok:true});
  if(p==='/api/reports/public'||p==='/api/runs')return json([]);
  return json({error:'Unexpected fixture request '+p},404);
 });
 const go=async route=>{await page.goto(base+'/#'+route);await page.locator('#main h1').waitFor();};
 const send=async(email='researcher@example.org')=>{await page.locator('#recovery-email').fill(email);await page.locator('[data-recovery-send]').click();await page.locator('#recovery-code').waitFor();};
 const verify=async()=>{await page.locator('#recovery-code').fill('123456');await page.getByRole('button',{name:'下一步',exact:true}).click();await page.locator('#recovery-password').waitFor();};
 await go('/forgot-password');await send();
 assert.deepEqual(requests.find(r=>r.p==='/api/auth/forgot').body,{email:'researcher@example.org'});
 assert.match(await page.locator('[data-recovery-notice]').innerText(),/如果该邮箱/);
 assert.match(await page.locator('[data-recovery-send]').innerText(),/60 秒/);assert.equal(await page.locator('[data-recovery-send]').isDisabled(),true);
 assert.equal(await page.locator('#recovery-code').getAttribute('autocomplete'),'one-time-code');assert.equal(await page.locator('#recovery-code').getAttribute('inputmode'),'numeric');
 await page.locator('#recovery-code').fill('000000');await page.getByRole('button',{name:'下一步',exact:true}).click();await page.locator('[data-recovery-error]').filter({hasText:'验证码不正确'}).waitFor();
 await page.clock.fastForward(60000);assert.equal(await page.locator('[data-recovery-send]').isEnabled(),true);
 await page.locator('[data-recovery-send]').click();await page.waitForFunction(()=>document.querySelector('[data-recovery-send]')?.innerText.includes('60 秒'));
 assert.equal(await page.locator('#recovery-code').inputValue(),'');
 await verify();assert.match(await page.locator('[data-recovery-password]').innerText(),/r\*\*\*@example.org/);
 await page.locator('#recovery-password').fill('new-secret-123');await page.locator('#recovery-confirm').fill('new-secret-456');
 await page.getByRole('button',{name:'显示新密码',exact:true}).click();assert.equal(await page.locator('#recovery-password').getAttribute('type'),'text');
 await page.getByRole('button',{name:'隐藏新密码',exact:true}).click();assert.equal(await page.locator('#recovery-password').getAttribute('type'),'password');
 await page.getByRole('button',{name:'确认重置密码',exact:true}).click();await page.locator('[data-recovery-error]').filter({hasText:'两次输入的密码不一致'}).waitFor();
 assert.equal(requests.filter(r=>r.p.endsWith('/complete')).length,0);
 await page.reload();await page.locator('#recovery-password').waitFor();assert.equal(await page.locator('#recovery-password').inputValue(),'');assert.equal(await page.locator('#recovery-confirm').inputValue(),'');
 await mkdir('tmp/verification/password-recovery',{recursive:true});await page.screenshot({path:'tmp/verification/password-recovery/password-desktop.png',fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:'tmp/verification/password-recovery/password-mobile.png',fullPage:true});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true);
 await page.locator('#recovery-password').fill('new-secret-123');await page.locator('#recovery-confirm').fill('new-secret-123');
 await page.getByRole('button',{name:'确认重置密码',exact:true}).click();await page.waitForURL(/#\/login$/);
 assert.deepEqual(requests.find(r=>r.p.endsWith('/complete')).body,{challengeId,password:'new-secret-123',confirmPassword:'new-secret-123'});
 assert.equal(requests.filter(r=>r.p==='/api/auth/login').length,0,'reset does not log in automatically');
 assert.ok(!(await page.evaluate(()=>Object.values(localStorage).join('')+Object.values(sessionStorage).join(''))).includes('new-secret-123'));
 await go('/forgot-password');await send('person@proton.me');await page.reload();await page.locator('#recovery-email').waitFor();assert.equal(await page.locator('#recovery-email').inputValue(),'');assert.equal(await page.locator('#recovery-code').count(),0);
 rateLimited=true;await page.locator('#recovery-email').fill('rate@example.net');await page.locator('[data-recovery-send]').click();await page.locator('[data-recovery-error]').waitFor();assert.match(await page.locator('[data-password-recovery]').innerText(),/37 秒后重试/);
 await page.clock.fastForward(37000);rateLimited=false;assert.equal(await page.locator('[data-recovery-send]').isEnabled(),true);
 await page.screenshot({path:'tmp/verification/password-recovery/verify-mobile.png',fullPage:true});
 await go('/forgot-password');slowEmail='slow@example.org';const started=new Promise(resolve=>slowStarted=resolve);
 await page.locator('#recovery-email').fill(slowEmail);await page.locator('[data-recovery-send]').click();await started;
 await page.locator('#recovery-email').fill('fresh@example.org');await page.locator('[data-recovery-send]').click();await page.locator('#recovery-code').waitFor();releaseSlow();
 await verify();assert.equal(grant.challengeId,challengeId,'changed email keeps its own challenge');
 await page.locator('#recovery-password').fill('leave-route-secret');await page.locator('a[href="#/login"]').last().click();await page.waitForURL(/#\/login$/);
 await page.locator('a[href="#/forgot-password"]').click();await page.locator('#recovery-password').waitFor();assert.equal(await page.locator('#recovery-password').inputValue(),'');
 grant=null;await go('/reset-password?token='+'a'.repeat(64));assert.equal(await page.locator('[name="token"]').inputValue(),'a'.repeat(64));
 await page.locator('[name="password"]').fill('legacy-secret-123');await page.locator('#auth-form [type="submit"]').click();await page.waitForURL(/#\/login$/);assert.ok(requests.some(r=>r.p==='/api/auth/reset'&&r.body.token==='a'.repeat(64)));
 await go('/register');await page.locator('[name="email"]').fill('person@proton.me');assert.equal(await page.locator('[name="email"]').evaluate(el=>el.checkValidity()),true);
 user={id:'signed-in-fixture',name:'Signed in fixture',email:'researcher@example.org',role:'researcher'};grant=grantFor('signed-in-reset');
 await go('/forgot-password');await page.locator('#recovery-password').waitFor();assert.equal(await page.locator('.nav-end a').innerText(),'工作台');
 await page.locator('#recovery-password').fill('logged-in-new-secret');await page.locator('#recovery-confirm').fill('logged-in-new-secret');
 const beforeRefresh=requests.filter(r=>r.p==='/api/bootstrap').length;
 await page.getByRole('button',{name:'确认重置密码',exact:true}).click();await page.waitForURL(/#\/login$/);await page.locator('.nav-end a').filter({hasText:'登录'}).waitFor();
 assert.ok(requests.filter(r=>r.p==='/api/bootstrap').length>beforeRefresh,'successful reset refreshes authenticated state');
 assert.equal(requests.filter(r=>r.p==='/api/auth/logout').length,0,'reset must not revoke an unrelated session via logout');
 for(const r of requests.filter(r=>r.method==='POST'))assert.equal(r.headers['x-csrf-token'],'recovery-fixture');
 assert.deepEqual(errors,[]);
 console.log('PASS recovery browser: six-digit entry, neutral request, countdown, refresh, password visibility/mismatch, mobile layout, CSRF, races, legacy link and generic email.');
}catch(error){console.error({errors,url:page?.url(),screen:await page?.locator('#main').innerText()});throw error;}
finally{releaseSlow?.();await browser?.close();await new Promise(resolve=>server.close(resolve));}

import assert from 'node:assert/strict';
import {createPasswordRecovery} from '../src/main/resources/static/js/password-recovery.js';

const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
const failure=(code,status=400,details={})=>Object.assign(Error(code),{code,status,details:{code,...details}});
function fixture(handler){
 const state={},requests=[],timers=new Map();let time=1_800_000_000_000,finished=0,sequence=0;
 const api=async(path,options={})=>{requests.push({path,...options});return handler(path,options);};
 const workflow=createPasswordRecovery({state,api,onComplete:()=>finished++,now:()=>time,
  setIntervalFn:fn=>{const id=++sequence;timers.set(id,fn);return id;},clearIntervalFn:id=>timers.delete(id)});
 return {state,requests,workflow,timers,get finished(){return finished;},get time(){return time;},advance(seconds){time+=seconds*1000;for(const fn of timers.values())fn();}};
}
const challenge=id=>({ok:true,challengeId:id,expiresInSeconds:600,retryAfterSeconds:60});
const grant=(id,time)=>({canReset:true,challengeId:id,maskedEmail:'r***@example.org',expiresAt:new Date(time+600000).toISOString()});

{
 let verified=false;
 const f=fixture(path=>path.endsWith('/status')?(verified?grant('c-1',f.time):{canReset:false}):path.endsWith('/forgot')?challenge('c-1'):path.endsWith('/verify')?(verified=true,{ok:true}):{ok:true});
 await f.workflow.restore();assert.equal(f.state.step,'verify');
 f.workflow.editEmail('researcher@example.org');await f.workflow.sendCode();
 assert.equal(f.state.challengeId,'c-1');assert.equal(f.state.resendSeconds,60);assert.match(f.state.notice,/如果/);assert.ok(!f.state.notice.includes('已发送'));
 await f.workflow.sendCode();assert.equal(f.requests.filter(r=>r.path.endsWith('/forgot')).length,1);
 f.advance(59);assert.equal(f.state.resendSeconds,1);f.advance(1);assert.equal(f.state.resendSeconds,0);
 f.workflow.inputCode('12a34567');assert.equal(f.state.code,'123456');await f.workflow.verifyCode();
 assert.equal(f.state.step,'password');assert.equal(f.state.code,'');assert.equal(f.state.email,'');assert.equal(f.state.maskedEmail,'r***@example.org');
 f.state.password='1234567';f.state.confirmPassword='1234567';await f.workflow.complete();assert.match(f.state.error,/8 至 24/);
 f.state.password='12345678';f.state.confirmPassword='12345679';await f.workflow.complete();assert.match(f.state.error,/不一致/);
 f.state.password='x'.repeat(25);f.state.confirmPassword=f.state.password;await f.workflow.complete();assert.match(f.state.error,/8 至 24/);
 f.state.password='12345678';f.state.confirmPassword='12345678';f.state.showPassword=true;await f.workflow.complete();
 assert.equal(f.finished,1);assert.equal(f.state.password,'');assert.equal(f.state.confirmPassword,'');assert.equal(f.state.challengeId,'');assert.equal(f.state.showPassword,false);
 assert.deepEqual(f.requests.find(r=>r.path.endsWith('/complete')).body,{challengeId:'c-1',password:'12345678',confirmPassword:'12345678'});
 assert.ok(f.requests.every(r=>r.signal instanceof AbortSignal));assert.ok(!f.requests.some(r=>r.path.endsWith('/login')));
 f.workflow.dispose();assert.equal(f.timers.size,0);
}
{
 const f=fixture(()=>grant('restored',f.time));await f.workflow.restore();
 assert.equal(f.state.step,'password');assert.equal(f.state.challengeId,'restored');assert.equal(f.state.password,'');
 f.state.password='in-memory-only';f.state.confirmPassword='in-memory-only';f.state.showConfirmation=true;f.advance(601);
 assert.equal(f.state.step,'verify');assert.equal(f.state.password,'');assert.equal(f.state.showConfirmation,false);assert.match(f.state.error,/时间已过/);
 f.workflow.dispose();
}
{
 const f=fixture(()=>({canReset:false}));await f.workflow.restore();
 assert.equal(f.state.challengeId,'');assert.equal(f.state.email,'');assert.equal(f.state.code,'');assert.equal(f.requests.length,1,'refresh without grant requires a new request');f.workflow.dispose();
}
{
 const slow=deferred();let count=0;
 const f=fixture(()=>++count===1?slow.promise:challenge('new-email'));
 f.workflow.editEmail('old@example.org');const old=f.workflow.sendCode();const oldRequest=f.requests[0];
 f.workflow.editEmail('new@example.org');assert.equal(oldRequest.signal.aborted,true);await f.workflow.sendCode();
 slow.resolve(challenge('old-email'));await old;
 assert.equal(f.state.email,'new@example.org');assert.equal(f.state.challengeId,'new-email','late responses cannot replace a changed email');f.workflow.dispose();
}
for(const action of ['reset','dispose']){
 const slow=deferred(),f=fixture(()=>slow.promise);f.workflow.editEmail('a@example.org');const pending=f.workflow.sendCode();
 f.workflow[action]();assert.equal(f.requests[0].signal.aborted,true);slow.resolve(challenge('stale'));await pending;
 assert.equal(f.state.email,'');assert.equal(f.state.challengeId,'');assert.equal(f.state.busy,false);f.workflow.dispose();
}
{
 const slow=deferred();let statusCalls=0;
 const f=fixture(path=>path.endsWith('/forgot')?challenge('verify-race'):path.endsWith('/verify')?slow.promise:(statusCalls++,grant('verify-race',f.time)));
 f.workflow.editEmail('old@example.org');await f.workflow.sendCode();f.workflow.inputCode('123456');const pending=f.workflow.verifyCode();
 f.workflow.editEmail('new@example.org');slow.resolve({ok:true});await pending;
 assert.equal(f.state.step,'verify');assert.equal(f.state.challengeId,'');assert.equal(statusCalls,0,'stale verification never restores another email grant');f.workflow.dispose();
}
{
 const slow=deferred(),f=fixture(path=>path.endsWith('/status')?grant('completion-race',f.time):slow.promise);
 await f.workflow.restore();f.state.password=f.state.confirmPassword='secret-123';const pending=f.workflow.complete();f.workflow.reset();slow.resolve({ok:true});await pending;
 assert.equal(f.finished,0,'old account response cannot navigate the new account');assert.equal(f.state.password,'');f.workflow.dispose();
}
{
 const f=fixture(()=>{throw failure('reset_rate_limited',429,{retryAfterSeconds:37});});
 f.workflow.editEmail('rate@example.org');await f.workflow.sendCode();assert.equal(f.state.resendSeconds,37);assert.equal(f.state.blockedSeconds,37);
 f.workflow.editEmail('another@example.net');await f.workflow.sendCode();assert.equal(f.requests.length,1,'changing email cannot bypass the displayed cooldown');
 f.advance(37);assert.equal(f.state.blockedSeconds,0);f.workflow.dispose();
}
for(const code of ['reset_challenge_expired','reset_challenge_locked']){
 const f=fixture(path=>path.endsWith('/forgot')?challenge('expired'):(()=>{throw failure(code);})());
 f.workflow.editEmail('expire@example.org');await f.workflow.sendCode();f.workflow.inputCode('123456');await f.workflow.verifyCode();
 assert.equal(f.state.challengeId,'');assert.equal(f.state.code,'');assert.equal(f.state.step,'verify');f.workflow.dispose();
}
{
 const f=fixture(path=>path.endsWith('/status')?grant('expired-grant',f.time):(()=>{throw failure('reset_grant_expired');})());
 await f.workflow.restore();f.state.password=f.state.confirmPassword='secret-123';await f.workflow.complete();
 assert.equal(f.state.password,'');assert.equal(f.state.challengeId,'');assert.equal(f.state.step,'verify');f.workflow.dispose();
}
{
 let offline=true;
 const f=fixture(path=>path.endsWith('/forgot')?challenge('status-retry'):path.endsWith('/verify')?{ok:true}:offline?Promise.reject(Error('network offline')):grant('status-retry',f.time));
 f.workflow.editEmail('retry@example.org');await f.workflow.sendCode();f.workflow.inputCode('123456');await f.workflow.verifyCode();
 assert.equal(f.state.restoreFailed,true);assert.equal(f.state.code,'');offline=false;await f.workflow.restore();assert.equal(f.state.step,'password');f.workflow.dispose();
}
for (const status of [undefined, 503]) {
 const f=fixture(path=>path.endsWith('/forgot')?challenge('lost-verification'):path.endsWith('/verify')?
  Promise.reject(Object.assign(Error('response lost'),{status})):grant('lost-verification',f.time));
 f.workflow.editEmail('retry@example.org');await f.workflow.sendCode();f.workflow.inputCode('123456');await f.workflow.verifyCode();
 assert.equal(f.state.restoreFailed,true);assert.equal(f.state.code,'');assert.match(f.state.error,/重新检查/);
 await f.workflow.restore();assert.equal(f.state.step,'password');assert.equal(f.state.restoreFailed,false);
 assert.equal(f.requests.filter(r=>r.path.endsWith('/forgot')).length,1,'recover a server-accepted code without sending another email');f.workflow.dispose();
}
{
 let valid=true;
 const f=fixture(()=>valid?grant('revoked-in-another-tab',f.time):{canReset:false});await f.workflow.restore();
 f.state.password=f.state.confirmPassword='unsent-secret';valid=false;await f.workflow.restore();
 assert.equal(f.state.step,'verify');assert.equal(f.state.challengeId,'');assert.equal(f.state.password,'');assert.equal(f.state.confirmPassword,'');
 assert.match(f.state.error,/验证已失效/);f.workflow.dispose();
}
console.log('PASS password recovery: two steps, generic email, exact code, 8–24 password, refresh, cooldown/429, expiry, stale response isolation, lost verification response recovery and secret cleanup.');

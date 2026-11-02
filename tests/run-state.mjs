import assert from 'node:assert/strict';
import {createRunState} from '../src/main/resources/static/js/run-state.js';

const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
const run=(id,seed=42,extra={})=>({runId:id,kind:'evaluation',status:'running',seed,config:{seed,datasetId:'mini-spn'},progress:{percent:0,completed:0,total:8},...extra});
function setup(api){
 const state={user:{id:'alice'},activeRun:null,runLogs:[],runNetworkError:''},reports=[],timers=new Map();let visible=true,next=0;
 const calls=[];
 const store=createRunState({state,api:async(path)=>{calls.push(path);return api(path);},onReport:report=>reports.push({user:state.user.id,report}),isVisible:()=>visible,
  setIntervalFn:fn=>{const id=++next;timers.set(id,fn);return id;},clearIntervalFn:id=>timers.delete(id)});
 return {state,reports,timers,calls,store,setVisible:value=>{visible=value;},tick:()=>Promise.all([...timers.values()].map(fn=>fn()))};
}

for(const seed of [987,0]){
 let current=run('seed-'+seed,seed),logs=[{sequence:2,message:'second'},{sequence:1,message:'first'},{sequence:2,message:'duplicate'}];
 const test=setup(path=>path.includes('/logs?')?logs:path.startsWith('/api/reports/')?{id:'report-seed'}:structuredClone(current));
 await test.store.attach();await test.store.restore(current.runId);
 assert.equal(test.state.activeRun.seed,seed);assert.equal(test.state.activeRun.config.seed,seed);assert.equal(test.timers.size,1);
 assert.deepEqual(test.state.runLogs.map(l=>l.sequence),[1,2],'duplicates within one batch are removed');
 logs=[{sequence:2},{sequence:3},{sequence:3}];await test.tick();assert.deepEqual(test.state.runLogs.map(l=>l.sequence),[1,2,3]);
 assert.ok(test.calls.some(path=>path.endsWith('/logs?after=2')));
 current={...current,status:'completed',reportId:'report-seed'};await test.tick();assert.equal(test.timers.size,0);assert.equal(test.reports.length,1);
 await test.store.refresh();assert.equal(test.reports.length,1,'one report delivery per tracked run');test.store.detach();
}

{
 const slow=deferred();let first=true;
 const test=setup(path=>{if(path==='/api/runs/a'&&first){first=false;return slow.promise;}if(path.includes('/logs?'))return [];return run('b',731);});
 test.store.track(run('a'));const pending=test.store.attach();
 await test.store.restore('b');slow.resolve(run('a',19));await pending;
 assert.equal(test.state.activeRun.runId,'b');assert.equal(test.state.activeRun.seed,731,'late previous-run response cannot overwrite restored run');test.store.detach();
}

{
 const pendingRestore=deferred();
 const test=setup(path=>path==='/api/runs/a'?pendingRestore.promise:path.includes('/logs?')?[]:run('b',0));
 const first=test.store.restore('a');await test.store.restore('b');pendingRestore.resolve(run('a',66));await first;
 assert.equal(test.state.activeRun.runId,'b','older restore request cannot overwrite a newer route');
}

{
 const delayedReport=deferred(),reportStarted=deferred();
 const test=setup(path=>{if(path.startsWith('/api/reports/')){reportStarted.resolve();return delayedReport.promise;}return path.includes('/logs?')?[]:run('alice-run',7,{status:'completed',reportId:'alice-report'});});
 test.store.track(run('alice-run'));const pending=test.store.refresh();await reportStarted.promise;
 test.state.user={id:'bob'};test.store.reset();delayedReport.resolve({id:'alice-report'});await pending;
 assert.equal(test.state.activeRun,null);assert.deepEqual(test.state.runLogs,[]);assert.equal(test.reports.length,0,'old account report never enters new account workspace');
 const count=test.calls.length;await test.store.refresh();assert.equal(test.calls.length,count);
}

{
 const test=setup(path=>path.includes('/logs?')?[]:run('visible'));
 test.store.track(run('visible'));await test.store.attach();assert.equal(test.timers.size,1);
 const requests=test.calls.length;test.setVisible(false);test.store.visibility();assert.equal(test.timers.size,0);await test.store.refresh();assert.equal(test.calls.length,requests);
 test.setVisible(true);await test.store.visibility();assert.equal(test.timers.size,1);assert.ok(test.calls.length>requests);
 test.store.detach();assert.equal(test.timers.size,0);
 await test.store.attach();assert.equal(test.timers.size,1,'returning to a view restarts polling');test.store.detach();
}

{
 let offline=false,current=run('network',0),reportOffline=true;
 const test=setup(path=>{if(offline)throw Error('offline');if(path.startsWith('/api/reports/')){if(reportOffline)throw Error('report offline');return {id:'network-report'};}return path.includes('/logs?')?[{sequence:1,message:'retained'}]:structuredClone(current);});
 test.store.track(current);await test.store.attach();offline=true;await test.tick();
 assert.equal(test.state.activeRun.seed,0);assert.equal(test.state.runLogs.length,1);assert.equal(test.state.runNetworkError,'offline');assert.equal(test.timers.size,1);
 await test.store.restore('network');assert.equal(test.state.runLogs.length,1,'failed restore retains cached progress and logs');
 offline=false;current={...current,progress:{percent:50}};await test.tick();assert.equal(test.state.activeRun.progressPercent,50);assert.equal(test.state.runNetworkError,'');
 current={...current,status:'completed',reportId:'network-report'};await test.tick();assert.equal(test.state.runNetworkError,'report offline');assert.equal(test.reports.length,0);
 test.setVisible(false);test.store.visibility();test.setVisible(true);reportOffline=false;await test.store.visibility();
 assert.equal(test.reports.length,1,'completed report retries after visibility and connectivity recover');assert.equal(test.timers.size,0);test.store.detach();
}

{
 const test=setup(()=>{throw Object.assign(Error('session expired'),{status:401});});
 test.store.track(run('expired'));await test.store.attach();assert.equal(test.timers.size,0);
 const count=test.calls.length;await test.store.visibility();assert.equal(test.calls.length,count,'expired sessions do not silently resume polling');
 test.state.user={id:'bob'};await test.store.attach();assert.equal(test.state.activeRun,null,'identity mismatch clears tracking even without an external reset');
 test.store.detach();test.store.detach();
}

console.log('Run state: exact seeds, stale responses, identity isolation, visible polling, offline recovery, log deduplication and report delivery passed.');

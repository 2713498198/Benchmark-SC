// Shared, identity-scoped run tracking. This module never stores credentials.
export function createRunState({state,api,onReport=()=>{},isVisible=()=>!globalThis.document?.hidden,intervalMs=1000,setIntervalFn=setInterval,clearIntervalFn=clearInterval}){
 let epoch=0,timer=null,views=0,busy=false,restoring=false,cursor=0,trackedId=null,trackedUser=state.user?.id,reportedId=null,authStopped=false;
 const idOf=run=>run?.runId||run?.id||null;
 const active=run=>run&&['queued','running'].includes(run.status);
 const current=(stamp,user,id)=>stamp===epoch&&user===state.user?.id&&(id===undefined||id===trackedId);
 const pendingReport=()=>state.activeRun?.status==='completed'&&state.activeRun.reportId&&reportedId!==state.activeRun.reportId;
 const shouldPoll=()=>active(state.activeRun)||pendingReport();
 const stop=()=>{if(timer!==null)clearIntervalFn(timer);timer=null;};
 function decorate(run){return {...run,progressPercent:run.progress?.percent??0,progressStats:run.progress||{}};}
 function track(run){
  stop();epoch++;trackedUser=state.user?.id;trackedId=idOf(run);cursor=0;reportedId=null;authStopped=false;restoring=false;
  state.runLogs=[];state.runNetworkError='';state.activeRun=run?decorate(run):null;busy=false;start();
 }
 function reset(){track(null);stop();}
 function identity(){if(trackedUser!==state.user?.id){reset();return false;}return true;}
 function start(){stop();if(views&&state.user&&trackedUser===state.user.id&&!restoring&&!authStopped&&shouldPoll()&&isVisible())timer=setIntervalFn(refresh,intervalMs);}
 function appendLogs(logs){
  if(!Array.isArray(logs))return;
  state.runLogs??=[];
  const seen=new Set(state.runLogs.map(log=>log.sequence));
  for(const log of logs){
   if(!Number.isSafeInteger(log?.sequence)||log.sequence<0)continue;
   cursor=Math.max(cursor,log.sequence);
   if(!seen.has(log.sequence)){seen.add(log.sequence);state.runLogs.push(log);}
  }
  state.runLogs.sort((a,b)=>a.sequence-b.sequence);
 }
 async function refresh(){
  if(!identity()||busy||restoring||authStopped||!state.user||!state.activeRun||!isVisible())return;
  busy=true;const stamp=epoch,user=state.user.id,id=trackedId;
  try{
   const run=await api('/api/runs/'+encodeURIComponent(id));if(!current(stamp,user,id))return;
   if(idOf(run)!==id)throw Error('运行响应与当前任务不一致');
   state.activeRun=decorate(run);state.runNetworkError='';
   const logs=await api('/api/runs/'+encodeURIComponent(id)+'/logs?after='+cursor);if(!current(stamp,user,id))return;
   appendLogs(logs);
   if(pendingReport()){
    const report=await api('/api/reports/'+encodeURIComponent(run.reportId));if(!current(stamp,user,id))return;
    if(report?.id!==run.reportId)throw Error('报告响应与当前运行不一致');
    onReport(report);reportedId=run.reportId;
   }
   if(!shouldPoll())stop();
  }catch(error){
   if(current(stamp,user,id)){state.runNetworkError=error.message||'无法更新运行状态';if([401,403,404].includes(error.status)){authStopped=true;stop();}}
  }finally{if(current(stamp,user,id))busy=false;}
 }
 async function restore(id){
  identity();if(!state.user){reset();return;}
  stop();const stamp=++epoch,user=state.user.id;busy=false;restoring=true;authStopped=false;
  let restored=false;
  try{
   let run;
   if(id)run=await api('/api/runs/'+encodeURIComponent(id));
   else{const found=await api('/api/runs?status=queued,running,paused&page=0&size=1');run=found[0]||null;}
   if(!current(stamp,user))return;
   if(id&&idOf(run)!==id)throw Error('运行响应与当前任务不一致');
   if(idOf(run)!==trackedId){cursor=0;state.runLogs=[];trackedId=idOf(run);reportedId=null;}
   state.activeRun=run?decorate(run):null;state.runNetworkError='';restored=true;
  }catch(error){
   if(current(stamp,user)){state.runNetworkError=error.message||'无法恢复运行';if([401,403,404].includes(error.status))authStopped=true;}
  }finally{
   if(current(stamp,user)){restoring=false;start();if(restored&&state.activeRun)await refresh();}
  }
 }
 function attach(){views++;identity();start();if(state.activeRun)return refresh();}
 function detach(){views=Math.max(0,views-1);if(!views)stop();}
 function visibility(){identity();if(isVisible()){start();if(views)return refresh();}else stop();}
 return {track,reset,restore,refresh,attach,detach,visibility};
}
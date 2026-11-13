// Use the Node 20 built-in fetch so the Runner has no third-party runtime dependency.
export class RunnerClient{
 constructor(config){this.base=String(config.serverUrl).replace(/\/$/,'');this.token=config.runnerToken;}
 async request(path,options={}){
  const controller=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),options.timeoutMs||35000);
  try{
   const headers={'Accept':'application/json',...(options.body?{'Content-Type':'application/json'}:{}),...(options.headers||{})};
   if(this.token)headers.Authorization='Bearer '+this.token;
   const response=await fetch(this.base+path,{...options,headers,signal:controller.signal});
   const text=await response.text();let body=null;try{body=text?JSON.parse(text):null;}catch{body={error:text.slice(0,500)};}
   if(!response.ok)throw new Error(body?.error||`Runner API 返回 HTTP ${response.status}`);
   return body;
  }finally{clearTimeout(timeout);}
 }
 async heartbeat(config){return this.request('/api/runner/heartbeat',{method:'POST',body:JSON.stringify({runnerVersion:config.runnerVersion,agents:publicAgents(config),acceptingTasks:true})});}
 async next(){return this.request('/api/runner/jobs/next');}
 async result(jobId,payload){return this.request('/api/runner/jobs/'+encodeURIComponent(jobId)+'/result',{method:'POST',body:JSON.stringify(payload)});}
}

// Advertise only public metadata; endpoint and credentials stay on the local machine.
export function publicAgents(config){
 const profiles=Array.isArray(config.agents)&&config.agents.length?config.agents:[config.agent||{}];
 return {profiles:profiles.map(item=>({name:item.name||'Local Agent',model:item.model||'unknown',version:item.version||'unknown',mode:item.mode||item.adapter||'local',adapter:item.adapter||'http'}))};
}

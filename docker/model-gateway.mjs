import http from 'node:http';
import {randomBytes,timingSafeEqual} from 'node:crypto';
import {pathToFileURL} from 'node:url';

const json=(res,status,value)=>{if(!res.headersSent)res.writeHead(status,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(value));};
const fail=(res,status,code)=>json(res,status,{error:{code,message:code}});
const equals=(a,b)=>typeof a==='string'&&typeof b==='string'&&Buffer.byteLength(a)===Buffer.byteLength(b)&&timingSafeEqual(Buffer.from(a),Buffer.from(b));
async function body(stream,limit=2_000_000){let size=0;const chunks=[];for await(const chunk of stream){size+=chunk.length;if(size>limit)throw Error('body_too_large');chunks.push(chunk);}return JSON.parse(Buffer.concat(chunks).toString('utf8'));}
function usageOf(value){const u=value?.usage;if(!u)return null;const input=u.prompt_tokens??u.input_tokens??u.inputTokens,output=u.completion_tokens??u.output_tokens??u.outputTokens;return [input,output].every(n=>Number.isSafeInteger(n)&&n>=0)?{inputTokens:input,outputTokens:output}:null;}
function drain(res,signal){return new Promise((resolve,reject)=>{const cleanup=()=>{res.off('drain',done);signal.removeEventListener('abort',aborted);};const done=()=>{cleanup();resolve();};const aborted=()=>{cleanup();reject(Error('aborted'));};res.once('drain',done);signal.addEventListener('abort',aborted,{once:true});if(signal.aborted)aborted();});}

/** This is an application-level model API, never an arbitrary URL/CONNECT proxy. */
export function createGateway(config){
 const {provider,controlToken}=config;
 if(!provider||!Array.isArray(provider.models)||!provider.models.length||!controlToken||!provider.apiKey)throw Error('invalid_config');
 const base=new URL(provider.baseUrl);
 if(!['http:','https:'].includes(base.protocol)||base.username||base.password||base.search||base.hash)throw Error('invalid_provider_url');
 if(base.protocol==='http:'&&!['127.0.0.1','localhost','[::1]'].includes(base.hostname)&&!config.allowInsecureTestProvider)throw Error('provider_requires_https');
 const endpoint=provider.baseUrl.replace(/\/$/,'')+'/chat/completions',tasks=new Map();let closed=false;
 const auth=req=>String(req.headers.authorization||'').replace(/^Bearer /,'');
 const summarize=task=>{const records=[...task.calls.values()],complete=records.every(r=>r.finished&&r.usage);const result={calls:records.length,usageComplete:complete,providerReported:complete&&records.length>0,failedCalls:records.filter(r=>r.failed).length};if(complete){result.inputTokens=records.reduce((n,r)=>n+r.usage.inputTokens,0);result.outputTokens=records.reduce((n,r)=>n+r.usage.outputTokens,0);}return result;};
 const stop=task=>{task.closed=true;task.token='';for(const call of task.calls.values())call.abort?.abort();};
 const control=http.createServer(async(req,res)=>{
  if(!equals(auth(req),controlToken))return fail(res,401,'unauthorized');
  try{
   if(req.method==='GET'&&req.url==='/control/health')return json(res,200,{ready:!closed});
   if(req.method!=='POST')return fail(res,404,'not_found');
   const input=await body(req,32_000);
   if(req.url==='/control/begin'){
    if(closed||typeof input.taskId!=='string'||!input.taskId||input.taskId.length>256||![20,40].includes(input.maxCalls)||!provider.models.includes(input.model))return fail(res,400,'invalid_task');
    const deadline=Date.parse(input.deadline);
    if(!Number.isFinite(deadline)||deadline<=Date.now()||deadline>Date.now()+610_000)return fail(res,400,'invalid_deadline');
    const previous=tasks.get(input.taskId);if(previous&&!previous.closed)return fail(res,409,'task_already_active');
    if(tasks.size>10000)return fail(res,429,'task_limit');
    const token=randomBytes(32).toString('hex');const task={token,model:input.model,maxCalls:input.maxCalls,deadline,calls:new Map(),closed:false};tasks.set(input.taskId,task);
    return json(res,200,{baseUrl:'http://model-gateway:8010/v1',token,provider:provider.id,model:input.model,priceModelId:provider.priceModelIds?.[input.model]||input.model,deadline:input.deadline,maxAgentCalls:input.maxCalls});
   }
   if(req.url==='/control/finish'){
    const task=tasks.get(input.taskId);if(!task)return fail(res,404,'task_not_found');stop(task);return json(res,200,summarize(task));
   }
   if(req.url==='/control/close'){closed=true;for(const task of tasks.values())stop(task);return json(res,200,{closed:true});}
   return fail(res,404,'not_found');
  }catch{return fail(res,400,'invalid_request');}
 });
 const agent=http.createServer(async(req,res)=>{
  const bearer=auth(req);const task=[...tasks.values()].find(t=>!t.closed&&equals(t.token,bearer));
  if(closed||!task)return fail(res,401,'invalid_task_token');
  if(Date.now()>=task.deadline){stop(task);return fail(res,408,'task_deadline');}
  if(req.method==='GET'&&req.url==='/v1/models')return json(res,200,{object:'list',data:[{id:task.model,object:'model',owned_by:provider.id}]});
  if(req.method!=='POST'||req.url!=='/v1/chat/completions')return fail(res,404,'not_found');
  let input;try{input=await body(req);}catch{return fail(res,400,'invalid_request');}
  if(input.model!==task.model||!provider.models.includes(input.model)||!Array.isArray(input.messages))return fail(res,400,'model_or_messages_not_allowed');
  if(task.closed||Date.now()>=task.deadline)return fail(res,408,'task_deadline');
  if(task.calls.size>=task.maxCalls)return fail(res,429,'call_budget_exhausted');
  if([...task.calls.values()].filter(c=>!c.finished).length>=2)return fail(res,429,'too_many_concurrent_calls');
  if(input.stream!==undefined&&typeof input.stream!=='boolean')return fail(res,400,'invalid_stream');
  if(input.stream)input.stream_options={...input.stream_options,include_usage:true};
  const id=randomBytes(16).toString('hex'),abort=new AbortController(),call={abort,usage:null,finished:false,failed:false};task.calls.set(id,call);
  const timer=setTimeout(()=>abort.abort(),Math.max(1,task.deadline-Date.now()));timer.unref();
  const disconnected=()=>{if(!res.writableEnded)abort.abort();};res.once('close',disconnected);
  try{
   const upstream=await fetch(endpoint,{method:'POST',redirect:'manual',headers:{'content-type':'application/json',authorization:'Bearer '+provider.apiKey},body:JSON.stringify(input),signal:abort.signal});
   if(!upstream.ok){call.failed=true;await upstream.body?.cancel();return fail(res,upstream.status===429?429:502,'provider_request_failed');}
   if(input.stream){
    if(!upstream.headers.get('content-type')?.includes('text/event-stream'))throw Error('invalid_provider_stream');
    res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-store','x-accel-buffering':'no'});
    let buffer='',event=[],total=0;const decoder=new TextDecoder();
    const line=line=>{if(line===''){if(event.length){const value=event.join('\n');if(value!=='[DONE]'){try{const reported=usageOf(JSON.parse(value));if(reported)call.usage=reported;}catch{}}event=[];}}else if(line.startsWith('data:'))event.push(line.slice(5).trimStart());};
    for await(const chunk of upstream.body){total+=chunk.length;if(total>8_000_000)throw Error('provider_response_too_large');buffer+=decoder.decode(chunk,{stream:true});let index;while((index=buffer.indexOf('\n'))>=0){line(buffer.slice(0,index).replace(/\r$/,''));buffer=buffer.slice(index+1);}if(buffer.length>2_000_000)throw Error('provider_event_too_large');if(!res.write(chunk))await drain(res,abort.signal);}
    buffer+=decoder.decode();if(buffer)line(buffer.replace(/\r$/,''));line('');res.end();
   }else{const data=await body(upstream.body,8_000_000);call.usage=usageOf(data);json(res,200,data);}
  }catch{call.failed=true;call.usage=null;if(!res.headersSent)fail(res,abort.signal.aborted?408:502,abort.signal.aborted?'task_cancelled_or_deadline':'provider_unavailable');else res.destroy();}
  finally{call.finished=true;call.abort=null;clearTimeout(timer);res.off('close',disconnected);}
 });
 return {agent,control,async close(){closed=true;for(const task of tasks.values())stop(task);provider.apiKey='';await Promise.all([agent,control].map(server=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();})));}};
}

if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 let bytes='';let configured=false;
 process.stdin.setEncoding('utf8');
 process.stdin.on('data',chunk=>{if(configured)return;bytes+=chunk;if(bytes.length>128000)process.exit(2);const end=bytes.indexOf('\n');if(end<0)return;configured=true;try{const config=JSON.parse(bytes.slice(0,end));bytes='';const gateway=createGateway(config);gateway.agent.listen(8010,'0.0.0.0');gateway.control.listen(8011,'0.0.0.0');const close=()=>gateway.close().finally(()=>process.exit(0));process.on('SIGTERM',close);process.on('SIGINT',close);}catch{process.stderr.write('Gateway configuration rejected\n');process.exit(2);}});
 process.stdin.on('end',()=>{if(!configured)process.exit(2);});
}

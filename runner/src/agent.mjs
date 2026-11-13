// Call the user's local Agent HTTP protocol from the Runner process.
import {runCommand} from './command-adapter.mjs';

export async function analyze(profile,request){
 if(profile?.adapter==='command'||profile?.mode==='command')return runCommand(profile,request);
 const endpoint=String(profile.endpoint||'').replace(/\/$/,'');
 if(!/^https?:\/\//i.test(endpoint))throw new Error('Agent endpoint 必须使用 http 或 https');
 const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),(request.budget?.timeoutSeconds||300)*1000);
 try{
  const headers={'Content-Type':'application/json','Accept':'application/json'};
  if(profile.token)headers.Authorization='Bearer '+profile.token;
  const response=await fetch(endpoint+'/analyze',{method:'POST',headers,body:JSON.stringify(request),signal:controller.signal});
  const text=await response.text();let body;try{body=JSON.parse(text);}catch{throw new Error('Agent 返回的不是 JSON');}
  if(!response.ok)throw new Error(`Agent 返回 HTTP ${response.status}`);
  if(!body||typeof body!=='object'||Array.isArray(body))throw new Error('Agent 返回的 JSON 必须是对象');
  return redact(body,[profile.token]);
 }catch(error){if(error.name==='AbortError')throw Object.assign(new Error('Agent 任务超时'),{code:'agent_timeout'});throw error;}
 finally{clearTimeout(timeout);}
}

// Remove local bearer values before the response enters the website database or logs.
function redact(value,secrets){
 if(typeof value==='string')return secrets.filter(Boolean).reduce((text,secret)=>text.split(secret).join('[redacted]'),value);
 if(Array.isArray(value))return value.map(item=>redact(item,secrets));
 if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([key,item])=>[key,['token','apiKey','api_key','authorization','accessToken'].includes(key)?'[redacted]':redact(item,secrets)]));
 return value;
}

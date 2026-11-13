import {loadConfig,saveConfig,configPath} from './config.mjs';
import {RunnerClient,publicAgents} from './client.mjs';
import {analyze} from './agent.mjs';
import {startSandbox,stopSandbox} from './docker.mjs';
import {loadAgentProfiles,normalizeProfile,validateCommandProfile} from './command-adapter.mjs';
import {readFile} from 'node:fs/promises';

// Parse the small command line surface without adding a dependency to the Runner package.
function argumentsMap(values){
 const result={};
 for(let index=0;index<values.length;index++){
  const value=values[index];
  if(!value.startsWith('--'))continue;
  const key=value.slice(2);
  if(key==='arg'){
   if(values[index+1]===undefined)throw new Error('--arg 缺少参数值');
   (result.arg||=[]).push(values[++index]);
   continue;
  }
  result[key]=values[index+1]===undefined||values[index+1].startsWith('--')?true:values[++index];
 }
 return result;
}

const [command='run',...rest]=process.argv.slice(2);const args=argumentsMap(rest);
if(command==='pair')await pair(args);else if(command==='run')await run(args);else if(command==='status')await status();else if(command==='doctor')await doctor();else{console.error('用法：pair | run | status | doctor');process.exitCode=2;}

// Exchange the browser-generated one-time code for a long-lived local token.
async function pair(args){
 const serverUrl=String(args.url||'').replace(/\/$/,'');const pairingCode=String(args.token||'');
 if(!serverUrl||!pairingCode)throw new Error('pair 需要 --url <网站地址> 和 --token <配对码>');
 const profiles=await pairingProfiles(args);
 const config={serverUrl,runnerVersion:'1.1.0',agent:profiles[0],agents:profiles,sandbox:{enabled:Boolean(args['docker-image']),image:String(args['docker-image']||'')}};
 const response=await rawRequest(serverUrl+'/api/runner/register',{method:'POST',body:JSON.stringify({pairingCode,displayName:String(args.name||'我的本地 Runner'),runnerVersion:config.runnerVersion,agents:publicAgents(config)})});
 config.runnerId=response.id;config.runnerToken=response.runnerToken;delete response.runnerToken;await saveConfig(config);
 console.log(`Runner 配对成功：${config.runnerId}`);console.log(`已配置 Agent：${profiles.map(profile=>`${profile.name} (${profile.model})`).join(', ')}`);console.log(`配置文件：${configPath}`);console.log('长 Token 和 Agent 命令配置只保存在本机配置文件中。');
}

// Build local profiles from a JSON manifest or the concise command-line form.
async function pairingProfiles(args){
 if(args['agent-config']){
  const profiles=await loadAgentProfiles(String(args['agent-config']));
  return Promise.all(profiles.map(async profile=>{
   const adapter=String(profile.adapter||'command').toLowerCase();
   if(adapter==='command')return {...await validateCommandProfile({...profile,adapter:'command'}),adapter:'command'};
   if(adapter!=='http')throw new Error(`不支持的 Agent adapter：${adapter}`);
   if(!profile.endpoint)throw new Error(`HTTP Agent ${profile.name||''} 缺少 endpoint`);
   return {...profile,adapter:'http',mode:profile.mode||'local',model:String(profile.model||'unknown')};
  }));
 }
 const adapter=String(args.adapter||'http').toLowerCase();
 if(adapter==='command'){
  if(!args.command)throw new Error('command Adapter 需要 --command <可执行文件>');
  const profile=await validateCommandProfile(normalizeProfile({
   name:String(args['agent-name']||'我的本地 Agent'),
   model:String(args.model||'command-agent'),
   version:String(args.version||'unknown'),
   adapter:'command',
   command:String(args.command),
   args:Array.isArray(args.arg)?args.arg:[],
   stdin:String(args.stdin||'prompt'),
   output:String(args.output||'auto'),
   cwd:args.cwd?String(args.cwd):null,
   timeoutSeconds:Number(args['timeout-seconds']||300),
   promptTemplate:args['prompt-file']?await readFile(String(args['prompt-file']),'utf8'):undefined
  }));
  return [{...profile,adapter:'command'}];
 }
 if(adapter!=='http')throw new Error('--adapter 只能是 http 或 command');
 return [{
  name:String(args['agent-name']||'我的本地 Agent'),
  endpoint:String(args['agent-url']||'http://127.0.0.1:8000'),
  model:String(args.model||'mock-model'),
  token:String(args['agent-token']||''),
  version:String(args.version||'unknown'),
  mode:'local',
  adapter:'http'
 }];
}

// Poll the website, execute each task locally, and upload only the redacted Agent result.
async function run(args){
 const config=await loadConfig();const client=new RunnerClient(config);let running=true;
 process.on('SIGINT',()=>{running=false;});process.on('SIGTERM',()=>{running=false;});
 console.log(`Runner 已启动：${config.runnerId}`);await client.heartbeat(config);
 let lastHeartbeat=Date.now();
 while(running){
  if(Date.now()-lastHeartbeat>15000){await client.heartbeat(config);lastHeartbeat=Date.now();}
  const envelope=await client.next();const job=envelope?.jobId?envelope:null;
  if(!job){await sleep(1000);continue;}
  await executeJob(client,config,job);
  if(args.once)return;
 }
 console.log('Runner 已停止。');
}

// Choose a local Agent profile by name/model and optionally run it inside Docker.
async function executeJob(client,config,job){
 const request=job.request||{};const profile=selectProfile(config,request);const started=Date.now();let sandbox=null;
 const taskLabel=request.taskId||job.jobId;
 console.log(`任务开始：${taskLabel} · ${profile.name||profile.model||'Local Agent'}`);
 try{
  let endpoint=profile.endpoint;
  const commandAdapter=profile.adapter==='command'||profile.mode==='command';
  if(!commandAdapter&&(profile.mode==='docker'||config.sandbox?.enabled)){sandbox=await startSandbox(profile.image||config.sandbox.image);endpoint=sandbox.endpoint;}
  const response=await analyze({...profile,endpoint},request);
  await client.result(job.jobId,{status:'completed',response,elapsedMs:Date.now()-started});
  console.log(`任务完成：${taskLabel} · ${Math.round((Date.now()-started)/1000)} 秒`);
 }catch(error){
  const status=error.code==='agent_timeout'?'timeout':'failed';
  try{await client.result(job.jobId,{status,errorCode:error.code||'runner_error',errorMessage:String(error.message||error).slice(0,500),elapsedMs:Date.now()-started});}catch(uploadError){console.error('任务结果上传失败：',uploadError.message);}
  console.error(`任务失败：${taskLabel} · ${error.code||status} · ${error.message||error}`);
 }finally{await stopSandbox(sandbox);}
}

// Keep profile selection deterministic so a run cannot silently switch Agent implementations.
function selectProfile(config,request){
 const profiles=Array.isArray(config.agents)&&config.agents.length?config.agents:[config.agent];
 const requested=String(request.model||'');const named=String(request.agentName||'');
 return profiles.find(item=>item&&(item.model===requested||item.name===named))||profiles[0]||{};
}

async function status(){const config=await loadConfig();console.log(JSON.stringify({configPath,runnerId:config.runnerId,serverUrl:config.serverUrl,agents:publicAgents(config)},null,2));}

// Validate every local command profile without contacting the website or running a benchmark task.
async function doctor(){
 const config=await loadConfig();
 const profiles=Array.isArray(config.agents)&&config.agents.length?config.agents:[config.agent||{}];
 for(const profile of profiles){
  if(profile.adapter==='command'||profile.mode==='command'){
   const checked=await validateCommandProfile({...profile,adapter:'command'});
   console.log(`OK  ${checked.name||'Local Agent'} · ${checked.model} · ${checked.command} · ${checked.cwd||'每个任务使用独立临时目录'}`);
  }else{
   console.log(`OK  ${profile.name||'Local Agent'} · ${profile.model||'unknown'} · ${profile.endpoint||'(docker 或旧版配置)'}`);
  }
 }
}

async function rawRequest(url,options){
 const response=await fetch(url,{...options,headers:{'Content-Type':'application/json','Accept':'application/json'}});const text=await response.text();let body;try{body=text?JSON.parse(text):null;}catch{body={error:text};}if(!response.ok)throw new Error(body?.error||`网站返回 HTTP ${response.status}`);return body;
}
function sleep(milliseconds){return new Promise(resolve=>setTimeout(resolve,milliseconds));}

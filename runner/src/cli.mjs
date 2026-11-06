import {loadConfig,saveConfig,configPath} from './config.mjs';
import {RunnerClient,publicAgents} from './client.mjs';
import {analyze} from './agent.mjs';
import {startSandbox,stopSandbox} from './docker.mjs';

// Parse the small command line surface without adding a dependency to the Runner package.
function argumentsMap(values){
 const result={};for(let index=0;index<values.length;index++){const value=values[index];if(!value.startsWith('--'))continue;const key=value.slice(2);result[key]=values[index+1]?.startsWith('--')||values[index+1]===undefined?true:values[++index];}return result;
}

const [command='run',...rest]=process.argv.slice(2);const args=argumentsMap(rest);
if(command==='pair')await pair(args);else if(command==='run')await run(args);else if(command==='status')await status();else{console.error('用法：pair | run | status');process.exitCode=2;}

// Exchange the browser-generated one-time code for a long-lived local token.
async function pair(args){
 const serverUrl=String(args.url||'').replace(/\/$/,'');const pairingCode=String(args.token||'');
 if(!serverUrl||!pairingCode)throw new Error('pair 需要 --url <网站地址> 和 --token <配对码>');
 const config={serverUrl,runnerVersion:'1.0.0',agent:{name:String(args['agent-name']||'我的本地 Agent'),endpoint:String(args['agent-url']||'http://127.0.0.1:8000'),model:String(args.model||'mock-model'),token:args['agent-token']||'',mode:'local'},sandbox:{enabled:Boolean(args['docker-image']),image:String(args['docker-image']||'')},agents:[]};
 const response=await rawRequest(serverUrl+'/api/runner/register',{method:'POST',body:JSON.stringify({pairingCode,displayName:String(args.name||'我的本地 Runner'),runnerVersion:config.runnerVersion,agents:publicAgents(config)})});
 config.runnerId=response.id;config.runnerToken=response.runnerToken;delete response.runnerToken;await saveConfig(config);
 console.log(`Runner 配对成功：${config.runnerId}`);console.log(`配置文件：${configPath}`);console.log('长 Token 只保存在本机配置文件中。');
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
 try{
  let endpoint=profile.endpoint;
  if(profile.mode==='docker'||config.sandbox?.enabled){sandbox=await startSandbox(profile.image||config.sandbox.image);endpoint=sandbox.endpoint;}
  const response=await analyze({...profile,endpoint},request);
  await client.result(job.jobId,{status:'completed',response,elapsedMs:Date.now()-started});
 }catch(error){
  const status=error.code==='agent_timeout'?'timeout':'failed';
  try{await client.result(job.jobId,{status,errorCode:error.code||'runner_error',errorMessage:String(error.message||error).slice(0,500),elapsedMs:Date.now()-started});}catch(uploadError){console.error('任务结果上传失败：',uploadError.message);}
 }finally{await stopSandbox(sandbox);}
}

// Keep profile selection deterministic so a run cannot silently switch Agent implementations.
function selectProfile(config,request){
 const profiles=Array.isArray(config.agents)&&config.agents.length?config.agents:[config.agent];
 const requested=String(request.model||'');const named=String(request.agentName||'');
 return profiles.find(item=>item&&(item.model===requested||item.name===named))||profiles[0]||{};
}

async function status(){const config=await loadConfig();console.log(JSON.stringify({configPath,runnerId:config.runnerId,serverUrl:config.serverUrl,agents:publicAgents(config)},null,2));}

async function rawRequest(url,options){
 const response=await fetch(url,{...options,headers:{'Content-Type':'application/json','Accept':'application/json'}});const text=await response.text();let body;try{body=text?JSON.parse(text):null;}catch{body={error:text};}if(!response.ok)throw new Error(body?.error||`网站返回 HTTP ${response.status}`);return body;
}
function sleep(milliseconds){return new Promise(resolve=>setTimeout(resolve,milliseconds));}

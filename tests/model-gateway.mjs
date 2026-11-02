import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createGateway} from '../docker/model-gateway.mjs';

const received=[];
const provider=http.createServer(async(req,res)=>{
 const chunks=[];for await(const c of req)chunks.push(c);const payload=JSON.parse(Buffer.concat(chunks));
 received.push({path:req.url,authorization:req.headers.authorization,payload});
 const mode=payload.messages[0]?.content;
 if(mode==='failure'){res.writeHead(503);res.end('failure with secret details');return;}
 if(mode==='slow'){const timer=setTimeout(()=>res.end('{}'),3000);res.on('close',()=>clearTimeout(timer));return;}
 if(payload.stream){
  res.writeHead(200,{'content-type':'text/event-stream'});
  res.write('data: {"choices":[{"delta":{"content":"ok"}}]}\n\n');
  const usage='data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":7}}\n\n';
  res.write(usage.slice(0,18));res.write(usage.slice(18));res.write(usage);res.end('data: [DONE]\n\n');return;
 }
 res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({choices:[{message:{content:'ok'}}],...(mode==='missing'?{}:{usage:{prompt_tokens:10,completion_tokens:5}})}));
});
provider.listen(0,'127.0.0.1');await once(provider,'listening');
const config={controlToken:'test-control-secret',provider:{id:'fake',baseUrl:'http://127.0.0.1:'+provider.address().port+'/v1',models:['allowed-model','other-model'],priceModelIds:{'allowed-model':'priced-model'},apiKey:'fake-user-key'}};
const gateway=createGateway(config);
gateway.agent.listen(0,'127.0.0.1');gateway.control.listen(0,'127.0.0.1');await Promise.all([once(gateway.agent,'listening'),once(gateway.control,'listening')]);
const agent='http://127.0.0.1:'+gateway.agent.address().port,control='http://127.0.0.1:'+gateway.control.address().port;
async function admin(path,data){const response=await fetch(control+path,{method:'POST',headers:{authorization:'Bearer test-control-secret','content-type':'application/json'},body:JSON.stringify(data)});return {status:response.status,...await response.json()};}
const begin=(taskId,extra={})=>admin('/control/begin',{taskId,model:'allowed-model',maxCalls:20,deadline:new Date(Date.now()+10000).toISOString(),...extra});
const finish=taskId=>admin('/control/finish',{taskId});
const chat=(token,content='normal',extra={})=>fetch(agent+'/v1/chat/completions',{method:'POST',headers:{authorization:'Bearer '+token,'content-type':'application/json'},body:JSON.stringify({model:'allowed-model',messages:[{role:'user',content}],...extra})});
try{
 let task=await begin('standard');assert.equal(task.status,200);assert.equal(task.priceModelId,'priced-model');
 const models=await fetch(agent+'/v1/models',{headers:{authorization:'Bearer '+task.token}});assert.deepEqual((await models.json()).data.map(m=>m.id),['allowed-model']);
 assert.equal((await chat(task.token,'normal',{model:'other-model'})).status,400);
 assert.equal((await chat('invalid')).status,401);
 assert.equal((await fetch(agent+'/control/begin',{method:'POST',headers:{authorization:'Bearer '+task.token}})).status,404);
 let response=await chat(task.token);assert.equal(response.status,200);await response.json();
 response=await chat(task.token,'stream',{stream:true});assert.equal(response.status,200);assert.match(await response.text(),/\[DONE\]/);
 const usage=await finish('standard');assert.equal(usage.inputTokens,13);assert.equal(usage.outputTokens,12);assert.equal(usage.calls,2);assert.equal(usage.usageComplete,true);
 assert.equal(received[0].authorization,'Bearer fake-user-key');assert.equal(received[0].path,'/v1/chat/completions');assert.equal(received[1].payload.stream_options.include_usage,true);
 assert.equal((await chat(task.token)).status,401,'finished tokens are revoked');
 task=await begin('budget');const start=received.length;
 for(let i=0;i<20;i++){response=await chat(task.token);assert.equal(response.status,200);await response.json();}
 assert.equal((await chat(task.token)).status,429);assert.equal(received.length-start,20,'no automatic retries or extra provider calls');
 assert.equal((await finish('budget')).calls,20);
 task=await begin('missing');response=await chat(task.token,'missing');await response.json();const missing=await finish('missing');assert.equal(missing.usageComplete,false);assert.equal('inputTokens' in missing,false);
 task=await begin('failure');const failureStart=received.length;response=await chat(task.token,'failure');assert.equal(response.status,502);assert.equal((await response.text()).includes('secret details'),false);assert.equal(received.length-failureStart,1);assert.equal((await finish('failure')).failedCalls,1);
 task=await begin('expired',{deadline:new Date(Date.now()+120).toISOString()});await new Promise(r=>setTimeout(r,150));assert.equal((await chat(task.token)).status,408);
 task=await begin('cancel');const pending=chat(task.token,'slow');await new Promise(r=>setTimeout(r,30));assert.equal((await finish('cancel')).usageComplete,false);assert.equal((await pending).status,408);assert.equal((await chat(task.token)).status,401);
 task=await begin('parallel');const parallel=[chat(task.token,'slow'),chat(task.token,'slow')];await new Promise(r=>setTimeout(r,30));assert.equal((await chat(task.token)).status,429);await finish('parallel');assert.deepEqual((await Promise.all(parallel)).map(r=>r.status),[408,408]);
 task=await begin('deadline-live',{deadline:new Date(Date.now()+150).toISOString()});assert.equal((await chat(task.token,'slow')).status,408);assert.equal((await finish('deadline-live')).usageComplete,false);
 assert.equal((await begin('extended',{maxCalls:40})).status,200);const unused=await finish('extended');assert.equal(unused.inputTokens,0);assert.equal(unused.providerReported,false);
 assert.equal((await begin('invalid-budget',{maxCalls:21})).status,400);
 assert.equal((await begin('forbidden',{model:'not-allowed'})).status,400);
 assert.equal((await fetch(control+'/control/health')).status,401);
 console.log('Model gateway: scoped tokens, model allowlist, JSON/SSE, exact usage accounting, budget, timeout, cancellation and provider errors passed.');
}finally{await gateway.close();await new Promise(resolve=>{provider.close(resolve);provider.closeAllConnections();});}

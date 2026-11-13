import {ref,computed,onMounted,onUnmounted} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp,SvgIcon} from './shared.js';

// Pairing and Runner status page. Tokens are shown only in the one-time registration response.
export default {components:{SvgIcon},setup(){
 const app=useApp(),runners=ref([]),pairing=ref(null),loading=ref(false),error=ref('');
 const agentMode=ref('http'),agentUrl=ref('http://127.0.0.1:8000'),model=ref('mock-model'),dockerImage=ref(''),agentConfig=ref('runner/examples/codex.json');
 const shellToken=value=>/[\s"]/.test(value)?`'${value.replaceAll("'","''")}'`:value;
 const command=computed(()=>{
  if(!pairing.value)return '';
  const parts=['node runner/src/cli.mjs','pair','--url',location.origin,'--token',pairing.value.pairingCode];
  if(agentMode.value==='command')parts.push('--agent-config',shellToken(agentConfig.value.trim()||'runner/examples/codex.json'));
  else{
   parts.push('--agent-url',shellToken(agentUrl.value.trim()||'http://127.0.0.1:8000'),'--model',shellToken(model.value.trim()||'mock-model'));
   if(dockerImage.value.trim())parts.push('--docker-image',shellToken(dockerImage.value.trim()));
  }
  return parts.join(' ');
 });
 async function load(){loading.value=true;error.value='';try{runners.value=await app.api('/api/runners');}catch(err){error.value=err.message;}finally{loading.value=false;}}
 async function createPairing(){try{pairing.value=await app.api('/api/runners/pair',{method:'POST'});}catch(err){error.value=err.message;}}
 async function revoke(id){try{await app.api('/api/runners/'+encodeURIComponent(id),{method:'DELETE'});await load();}catch(err){error.value=err.message;}}
 async function copy(value){try{await navigator.clipboard.writeText(value);app.toast('命令已复制');}catch{app.toast('浏览器未授权剪贴板，请手动复制');}}
 let timer;onMounted(()=>{load();timer=setInterval(load,15000);});onUnmounted(()=>clearInterval(timer));
 return {runners,pairing,loading,error,command,agentMode,agentConfig,agentUrl,model,dockerImage,load,createPairing,revoke,copy};
},template:`
<div class="container page">
 <div class="page-header">
  <div><h1>本地 Runner</h1><p>让网站通过主动轮询调度你电脑上的 Agent 和 Docker。</p></div>
  <a class="btn secondary" href="#/evaluate">开始测评</a>
 </div>
 <section class="card card-pad stack">
  <div class="row between"><div><h2>配对本机</h2><p class="inline-note">配对码十分钟有效，Runner Token 只在本机保存。</p></div><button class="btn primary" @click="createPairing">生成配对码</button></div>
  <div class="field">
   <label for="runner-adapter">Agent 接入方式</label>
   <select id="runner-adapter" v-model="agentMode"><option value="http">本地 HTTP Agent</option><option value="command">本机 CLI 命令 Adapter</option></select>
  </div>
  <template v-if="agentMode==='http'">
   <div class="two-col">
    <div class="field"><label for="runner-agent-url">本地 Agent 地址</label><input id="runner-agent-url" type="url" v-model="agentUrl" placeholder="http://127.0.0.1:8000"></div>
    <div class="field"><label for="runner-model">绑定模型</label><input id="runner-model" v-model="model" placeholder="mock-model"></div>
   </div>
   <div class="field"><label for="runner-docker-image">Docker 镜像（可选）</label><input id="runner-docker-image" v-model="dockerImage" placeholder="cryptobench-agent:local"><p class="inline-note">填写后，Runner 会按任务启动本地 Docker Agent；留空则直接调用 Agent 地址。</p></div>
  </template>
  <div v-else class="field">
   <label for="runner-agent-config">本机 Agent CLI 配置</label>
   <input id="runner-agent-config" v-model="agentConfig" placeholder="runner/examples/codex.json">
   <p class="inline-note">填写本机存在的 JSON 配置路径。可配置 Codex、DeepSeek Harness 或其他支持非交互调用的 CLI，Runner 不会通过 Shell 拼接命令。</p>
  </div>
  <div v-if="pairing" class="callout"><strong>{{pairing.pairingCode}}</strong><span>有效期 {{Math.ceil((pairing.expiresAt-Date.now())/60000)}} 分钟</span><button class="btn small" @click="copy(command)">复制启动命令</button><code class="code-block">{{command}}</code></div>
  <ol><li>在项目根目录运行上方命令完成配对。</li><li>配对后执行 <code>node runner/src/cli.mjs run</code>。</li><li>回到测评页选择“本地 Runner”。</li></ol>
 </section>
 <section class="card card-pad stack">
  <div class="row between"><h2>已配对 Runner</h2><button class="btn small" @click="load" :disabled="loading"><SvgIcon name="refresh" :size="15"/>刷新</button></div>
  <p v-if="error" class="callout" role="alert">{{error}}</p>
  <div v-if="!runners.length&&!loading" class="empty">尚未配对 Runner。</div>
  <div v-for="runner in runners" :key="runner.id" class="row between" style="border-top:1px solid var(--line);padding:14px 0">
   <div><strong>{{runner.displayName}}</strong><div class="inline-note">{{runner.online?'在线':'离线'}} · {{runner.runnerVersion||'unknown'}} · {{runner.id}}</div><div v-if="runner.agents?.profiles?.length" class="inline-note">Agent：{{runner.agents.profiles.map(item=>item.name+' / '+item.model).join('，')}}</div></div>
   <button class="btn danger small" @click="revoke(runner.id)">撤销</button>
  </div>
 </section>
</div>`};

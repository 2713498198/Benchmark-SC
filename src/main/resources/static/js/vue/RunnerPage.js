import {ref,computed,onMounted,onUnmounted} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp,SvgIcon} from './shared.js';

// Pairing and Runner status page. Tokens are shown only in the one-time registration response.
export default {components:{SvgIcon},setup(){
 const app=useApp(),runners=ref([]),pairing=ref(null),loading=ref(false),error=ref('');
 const packageInfo=ref(null),packageLoading=ref(true),packageError=ref('');
 const agentMode=ref('http'),agentUrl=ref('http://127.0.0.1:8000'),model=ref('mock-model'),dockerImage=ref(''),agentConfig=ref('.\\examples\\codex.json');
 const configPreset=ref('codex');
 const configPresets=[
  {id:'codex',label:'Codex CLI',path:'.\\examples\\codex.json'},
  {id:'codex-deepseek',label:'Codex + DeepSeek',path:'.\\examples\\codex-deepseek.json'},
  {id:'generic',label:'通用命令 Agent',path:'.\\examples\\generic-cli-agent.json'},
  {id:'multi',label:'多 Agent 配置',path:'.\\examples\\multi-agent.json'},
  {id:'custom',label:'自定义配置文件',path:''},
 ];
 const configExample=JSON.stringify({
  name:'My CLI Agent',
  model:'my-agent',
  version:'local',
  adapter:'command',
  command:'C:\\Tools\\my-agent.exe',
  args:['--input','{prompt}'],
  stdin:'none',
  output:'json',
  cwd:'C:\\research',
  timeoutSeconds:300
 },null,2);
 const shellToken=value=>/[\s"]/.test(value)?`'${value.replaceAll("'","''")}'`:value;
 const normalizedConfigPath=computed(()=>{
  let raw=agentConfig.value.trim().replace(/^["']|["']$/g,'');
  raw=raw.replaceAll('/','\\');
  if(/^runner\\examples\\/i.test(raw))raw='.\\examples\\'+raw.slice('runner\\examples\\'.length);
  if(raw&&!raw.startsWith('.\\')&&!raw.startsWith('\\\\')&&!raw.startsWith('..\\')&&!/^[A-Za-z]:\\/.test(raw)&&!raw.includes('\\'))raw='.\\'+raw;
  return raw;
 });
 const configPath=(standalone)=>{
  const raw=normalizedConfigPath.value;
  if(standalone){
   if(!raw)return '.\\examples\\codex.json';
   return raw;
  }
  if(!raw)return 'runner/examples/codex.json';
  if(raw.startsWith('.\\examples\\'))return 'runner/examples/'+raw.slice('.\\examples\\'.length);
  return raw;
 };
 const configPathError=computed(()=>{
  if(agentMode.value!=='command')return '';
  if(!normalizedConfigPath.value)return '请选择配置模板或填写 JSON 配置文件路径。';
  if(!normalizedConfigPath.value.toLowerCase().endsWith('.json'))return '配置路径必须以 .json 结尾。';
  return '';
 });
 function chooseConfigPreset(){
  const selected=configPresets.find(item=>item.id===configPreset.value);
  if(selected&&selected.path)agentConfig.value=selected.path;
 }
 function editConfigPath(){configPreset.value='custom';}
 const packageCommand=computed(()=>{
  if(!pairing.value||!packageInfo.value)return '';
  const parts=['.\\cryptobench-runner.cmd','pair','--url',location.origin,'--token',pairing.value.pairingCode];
  if(agentMode.value==='command')parts.push('--agent-config',shellToken(configPath(true)));
  else{
   parts.push('--agent-url',shellToken(agentUrl.value.trim()||'http://127.0.0.1:8000'),'--model',shellToken(model.value.trim()||'mock-model'));
   if(dockerImage.value.trim())parts.push('--docker-image',shellToken(dockerImage.value.trim()));
  }
  return parts.join(' ');
 });
 const command=computed(()=>{
  return packageCommand.value;
 });
 const runCommand=computed(()=>'.\\cryptobench-runner.cmd run');
 const agentConfigPlaceholder=computed(()=>packageInfo.value?'.\\examples\\codex.json':'runner/examples/codex.json');
 async function loadPackage(){packageLoading.value=true;packageError.value='';try{packageInfo.value=await app.api('/api/runners/package');}catch(err){packageInfo.value=null;packageError.value=err.message;}finally{packageLoading.value=false;}}
 async function load(){loading.value=true;error.value='';try{runners.value=await app.api('/api/runners');}catch(err){error.value=err.message;}finally{loading.value=false;}}
 async function createPairing(){try{pairing.value=await app.api('/api/runners/pair',{method:'POST'});}catch(err){error.value=err.message;}}
 async function revoke(id){try{await app.api('/api/runners/'+encodeURIComponent(id),{method:'DELETE'});runners.value=runners.value.filter(item=>item.id!==id);await load();}catch(err){error.value=err.message;}}
 async function copy(value){try{await navigator.clipboard.writeText(value);app.toast('命令已复制');}catch{app.toast('浏览器未授权剪贴板，请手动复制');}}
 async function copyConfigExample(){await copy(configExample);}
 const sizeLabel=value=>{
  const bytes=Number(value);if(!Number.isFinite(bytes)||bytes<1)return '未知大小';
  return bytes>=1024*1024?`${(bytes/1024/1024).toFixed(1)} MB`:`${Math.ceil(bytes/1024)} KB`;
 };
 let timer;onMounted(()=>{loadPackage();load();timer=setInterval(load,15000);});onUnmounted(()=>clearInterval(timer));
 return {runners,pairing,loading,error,command,runCommand,packageInfo,packageLoading,packageError,sizeLabel,agentMode,agentConfig,agentConfigPlaceholder,configPreset,configPresets,configExample,configPath,configPathError,agentUrl,model,dockerImage,load,loadPackage,chooseConfigPreset,editConfigPath,copyConfigExample,createPairing,revoke,copy};
},template:`
<div class="container page">
 <div class="page-header">
  <div><h1>本地 Runner</h1><p>让网站通过主动轮询调度你电脑上的 Agent 和 Docker。</p></div>
  <a class="btn secondary" href="#/evaluate">开始测评</a>
 </div>
 <section class="card card-pad stack">
  <div><h2>1. 下载并解压 Runner</h2><p class="inline-note">Windows x64 便携包已经包含 Node.js，不需要下载项目源码或安装 Node.js。</p></div>
  <p v-if="packageLoading" role="status">正在检查 Runner 安装包…</p>
  <template v-else-if="packageInfo">
   <div class="row between">
    <div><strong>Windows x64 · Runner {{packageInfo.runnerVersion}}</strong><p class="inline-note">Node {{packageInfo.nodeVersion}} · {{sizeLabel(packageInfo.bytes)}} · 源码 {{String(packageInfo.sourceCommit||'unknown').slice(0,12)}}</p></div>
    <a class="btn primary" :href="packageInfo.downloadPath||'/api/runners/download'" download><SvgIcon name="download" :size="16"/> 下载 Runner ZIP</a>
   </div>
   <details><summary>查看包完整性信息</summary><p class="inline-note">SHA-256</p><code class="code-block">{{packageInfo.sha256}}</code></details>
   <ol><li>下载 ZIP 并解压到固定目录。</li><li>在解压后的 <code>CryptoBench-Runner</code> 目录打开 PowerShell。</li><li>启动或配置你的本地 Agent。</li><li>完成下方配对，再执行 <code>{{runCommand}}</code>。</li></ol>
  </template>
  <p v-else class="callout" role="status">{{packageError||'当前部署未包含 Runner 安装包，请联系管理员。'}}</p>
 </section>
 <section class="card card-pad stack">
  <div class="row between"><div><h2>2. 配对本机</h2><p class="inline-note">请先下载并解压 Runner，再生成十分钟有效的配对码。Runner Token 只在本机保存。</p></div><button class="btn primary" :disabled="!packageInfo" @click="createPairing">生成配对码</button></div>
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
  <div v-else class="stack">
   <div class="two-col">
    <div class="field">
     <label for="runner-config-preset">CLI 配置模板</label>
     <select id="runner-config-preset" v-model="configPreset" @change="chooseConfigPreset">
      <option v-for="item in configPresets" :key="item.id" :value="item.id">{{item.label}}</option>
     </select>
    </div>
    <div class="field">
     <label for="runner-agent-config">JSON 配置文件路径</label>
     <input id="runner-agent-config" v-model="agentConfig" :placeholder="agentConfigPlaceholder" @input="editConfigPath">
    </div>
   </div>
   <p class="inline-note">相对路径以解压后的 Runner 目录为基准；便携包已包含 examples 目录。Windows 路径请填写为 <code>.\examples\codex.json</code> 或绝对路径。</p>
   <p v-if="configPathError" class="callout" role="alert">{{configPathError}}</p>
   <details>
    <summary>查看自定义配置格式</summary>
    <p class="inline-note">以下字段对应一个可执行的本地 CLI。args 必须是字符串数组，output 可选 jsonl、json、text 或 auto。</p>
    <button class="btn small secondary" type="button" @click="copyConfigExample">复制 JSON 模板</button>
    <pre class="resource-code"><code>{{configExample}}</code></pre>
   </details>
  </div>
  <div v-if="pairing" class="callout"><strong>{{pairing.pairingCode}}</strong><span>有效期 {{Math.ceil((pairing.expiresAt-Date.now())/60000)}} 分钟</span><button class="btn small" :disabled="!!configPathError" @click="copy(command)">复制启动命令</button><code class="code-block">{{command}}</code></div>
  <ol><li>{{packageInfo?'在解压后的 CryptoBench-Runner 目录':'在项目根目录'}}运行上方命令完成配对。</li><li>配对后执行 <code>{{runCommand}}</code> 并保持窗口开启。</li><li>回到测评页选择“本地 Runner”。</li></ol>
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

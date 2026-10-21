import {createApp, reactive, ref, computed, provide, watch, nextTick} from '/node_modules/vue/dist/vue.esm-browser.prod.js';
import {datasets} from './data.js';
import {appKey,SvgIcon} from './vue/shared.js';
import HomePage from './vue/HomePage.js';
import ResourcesPage from './vue/ResourcesPage.js';
import ArenaPage from './vue/ArenaPage.js';
import CommunityPage from './vue/CommunityPage.js';
import LeaderboardPage from './vue/LeaderboardPage.js';

let saved={};try{saved=JSON.parse(localStorage.getItem('cryptobench.v1')||'{}');}catch{}
const state=reactive({user:null,reports:[],connections:[],customDatasets:[],publicReports:[],environment:['cloud','local'].includes(saved.environment)?saved.environment:'cloud',activeRun:null});
const labels={'/':'首页','/datasets':'测评集','/tools':'测评工具','/leaderboard':'测评榜单','/arena':'PK 竞技'};
function parseRoute(){try{const route=decodeURI(location.hash.slice(1)||'/');return route.startsWith('/')?route:'/'+route;}catch{return '/404';}}
async function api(path,options={}){const init={credentials:'same-origin',...options,headers:{...(options.body?{'Content-Type':'application/json'}:{}),...(options.headers||{})}};if(init.body&&typeof init.body!=='string')init.body=JSON.stringify(init.body);const response=await fetch(path,init);let body;try{body=await response.json();}catch{}if(!response.ok)throw Error(body?.error||'请求失败');return body;}
function save(){try{localStorage.setItem('cryptobench.v1',JSON.stringify({reports:state.reports,connections:state.connections,customDatasets:state.customDatasets,environment:state.environment}));}catch{}}
function applyBootstrap(boot){state.user=boot.user||null;state.reports=(boot.reports||[]).sort((a,b)=>Date.parse(b.createdAt||0)-Date.parse(a.createdAt||0));state.connections=boot.connections||[];state.customDatasets=boot.customDatasets||[];state.publicReports=boot.publicReports||[];save();}
async function hydrate(){try{applyBootstrap(await api('/api/bootstrap'));}catch{applyBootstrap({});}}
function navigate(path){location.hash=path;}

const App={components:{SvgIcon,HomePage,ResourcesPage,ArenaPage,CommunityPage,LeaderboardPage},setup(){
 const route=ref(parseRoute()),toastMessage=ref(''),menuOpen=ref(false),dialog=ref(null),modal=reactive({kind:'',title:'',message:'',action:null,entries:[]}),connection=reactive({name:'',url:'',model:''});let toastTimer;
 const page=computed(()=>{const r=route.value;if(r==='/')return HomePage;if(/^\/(datasets|tools|download|docs)(\/|\?|$)/.test(r))return ResourcesPage;if(/^\/(evaluate|arena|report)(\/|\?|$)/.test(r))return ArenaPage;if(r.startsWith('/leaderboard'))return LeaderboardPage;if(/^\/(login|register|forgot-password|workspace|admin)(\/|\?|$)/.test(r))return CommunityPage;return null;});
 const active=computed(()=>route.value.split('?')[0]);
 function toast(message){toastMessage.value=message;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toastMessage.value='',3700);}
 function closeModal(){modal.kind='';modal.action=null;if(dialog.value?.open)dialog.value.close();}
 function confirm(title,message,kind,action){if(kind==='connection')Object.assign(connection,{name:'',url:'',model:''});Object.assign(modal,{kind,title,message,action});}
 watch(()=>modal.kind,async kind=>{await nextTick();if(kind&&!dialog.value?.open)dialog.value?.showModal();else if(!kind&&dialog.value?.open)dialog.value.close();});
 const app={state,route,modal,api,save,hydrate,applyBootstrap,navigate,toast,confirm,closeModal,getDatasets:()=>[...datasets,...state.customDatasets.filter(d=>d.published)]};provide(appKey,app);
 window.addEventListener('hashchange',()=>{route.value=parseRoute();menuOpen.value=false;window.scrollTo({top:0,behavior:'instant'});});hydrate();
 function showEnvironment(){confirm('切换演示环境','选择要预览的运行环境，测评规则保持一致。','environment');}
 async function saveConnection(){if(!/^https?:\/\//i.test(connection.url)){toast('服务地址必须使用 http 或 https');return;}try{const c=await api('/api/connections',{method:'POST',body:connection});state.connections.push(c);save();closeModal();}catch(err){toast(err.message);}}
 async function runConfirm(){const action=modal.action;if(action)await action();closeModal();}
 function skip(event){event.preventDefault();const main=document.querySelector('#main');main.focus({preventScroll:true});main.scrollIntoView({behavior:'instant'});}
 return {state,route,page,active,labels,toastMessage,menuOpen,dialog,modal,connection,closeModal,showEnvironment,saveConnection,runConfirm,skip,save};
},template:`
<div class="app-shell"><a class="skip-link" href="#main" @click="skip">跳转到主要内容</a>
 <header id="header" class="topbar"><div class="topbar-inner"><a class="brand" href="#/"><img src="assets/logo.svg" alt=""><span>CryptoBench</span></a><nav class="nav" :class="{open:menuOpen}" aria-label="主导航"><a v-for="(label,path) in labels" :key="path" :href="'#'+path" :class="{active:path==='/'?active==='/':active.startsWith(path)}" :aria-current="(path==='/'?active==='/':active.startsWith(path))?'page':null">{{label}}</a></nav><div class="nav-end"><button class="environment" data-environment title="切换演示运行环境" aria-label="切换演示运行环境" @click="showEnvironment"><SvgIcon :name="state.environment==='cloud'?'cloud':'terminal'" :size="16"/><span>{{state.environment==='cloud'?'云端':'本地'}}</span></button><a class="btn small" :class="{primary:!state.user}" :href="state.user?'#/workspace':'#/login'"><SvgIcon name="user" :size="14"/> {{state.user?'工作台':'登录'}}</a><button class="icon-button menu-toggle" aria-label="打开导航菜单" :aria-expanded="String(menuOpen)" @click="menuOpen=!menuOpen"><SvgIcon name="menu" :size="18"/></button></div></div></header>
 <main id="main" tabindex="-1" :data-route="route"><component v-if="page" :is="page" :key="route.split('?')[0]"/><div v-else class="container page"><div class="card empty"><SvgIcon name="search" :size="32"/><h1>页面未找到</h1><p>这个地址暂时不存在。</p><a class="btn primary" href="#/">返回首页</a></div></div></main>
 <footer id="footer"><div class="footer"><div class="container"><div class="footer-top"><div class="brand"><img src="assets/logo.svg" alt=""><span>CryptoBench</span></div><div class="footer-links"><a href="#/docs">接入文档</a><a href="#/download">本地部署</a><a href="#/docs?tab=methodology">测评方法</a><a href="#/docs?tab=about">关于平台</a></div></div><div class="footer-bottom"><span>© 2026 CryptoBench</span></div></div></div></footer>
 <div id="toast" role="status" aria-live="polite" :class="{show:!!toastMessage}">{{toastMessage}}</div>
 <dialog id="modal" ref="dialog" @close="closeModal" @click.self="closeModal"><div class="dialog-head"><h2>{{modal.title}}</h2><button class="icon-button" data-close-modal aria-label="关闭" @click="closeModal"><SvgIcon name="close" :size="18"/></button></div>
  <template v-if="modal.kind==='environment'"><p>{{modal.message}}</p><div class="dialog-actions"><button class="btn" data-env="local" @click="state.environment='local';save();closeModal()">预览本地环境</button><button class="btn primary" data-env="cloud" @click="state.environment='cloud';save();closeModal()">预览云端平台</button></div></template>
  <form v-else-if="modal.kind==='connection'" id="connection-form" class="stack" @submit.prevent="saveConnection"><div class="field"><label>连接名称</label><input name="name" v-model="connection.name" required maxlength="50" placeholder="我的密码分析 Agent"></div><div class="field"><label>服务地址</label><input name="url" v-model="connection.url" type="url" required placeholder="http://127.0.0.1:8000"></div><div class="field"><label>模型标识</label><input name="model" v-model="connection.model" required placeholder="your-model-id"></div><p class="inline-note">凭证不会保存；这里只保存连接元信息。</p><button class="btn primary" type="submit">保存连接</button></form>
  <template v-else-if="modal.kind==='compare'"><p class="inline-note">同一测评集、版本、预算与成绩类型；不同配置可能影响结果。</p><div class="table-scroll rank-comparison" tabindex="0" role="region" aria-label="测评记录对比矩阵"><table><thead><tr><th>比较指标</th><th v-for="item in modal.entries" :key="item.id">{{item.agent}}</th></tr></thead><tbody><tr v-for="field in [['通过率','score'],['通过 / 总数','passed'],['基础模型','model'],['平均耗时','time'],['费用 USD','cost'],['执行来源','source'],['成绩性质','demo'],['Agent 版本','agentVersion'],['验证器版本','evaluatorVersion'],['随机种子','seed']]" :key="field[1]"><th>{{field[0]}}</th><td v-for="item in modal.entries" :key="item.id">{{item[field[1]]??'未提供'}}</td></tr></tbody></table></div></template>
  <template v-else><p>{{modal.message}}</p><div class="dialog-actions"><button class="btn primary" :id="modal.kind==='sync'?'confirm-sync':modal.kind==='publish'?'confirm-publish':null" @click="runConfirm">{{modal.kind==='sync'?'确认同步':'确认公开'}}</button></div></template>
 </dialog></div>`};
createApp(App).mount('#app');

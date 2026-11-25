import {ref,computed,watch,onMounted,onUnmounted} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp,SvgIcon} from './shared.js';
import {datasets} from '../data.js';
import CustomDatasetAdmin from './CustomDatasetAdmin.js';
import PricingAdmin from './PricingAdmin.js';
import PasswordRecovery from './PasswordRecovery.js';

export default {
  components:{SvgIcon,CustomDatasetAdmin,PricingAdmin,PasswordRecovery},
  setup(){
    const app=useApp(),{state,route}=app;
    const path=computed(()=>route.value.split('?')[0]);
    const tab=computed(()=>new URLSearchParams(route.value.split('?')[1]).get('tab')||'runs');
    const isRecovery=computed(()=>path.value==='/forgot-password');
    const auth=ref({name:'',email:'',password:''});
    const adminTab=ref('datasets');
    const allData=computed(()=>[...datasets,...state.customDatasets.filter(d=>d.published)]);
    const datasetName=id=>allData.value.find(d=>d.id===id)?.name||id;
    let attached=false;

    async function finishAuth(boot,target,message){
      auth.value.password='';
      app.applyBootstrap(boot);
      app.navigate(target);
      app.toast(message);
    }

    async function submit(){
      try{
        const register=path.value==='/register';
        await app.api(register?'/api/auth/register':'/api/auth/login',{method:'POST',body:auth.value});
        const boot=await app.api('/api/bootstrap');
        await finishAuth(boot,boot.user?.role==='admin'?'/admin':'/workspace',register?'账号已创建':'登录成功');
      }catch(error){
        app.toast(error.message);
      }
    }

    async function logout(){
      try{
        await app.api('/api/auth/logout',{method:'POST'});
        app.applyBootstrap({});
        app.navigate('/');
      }catch(error){
        app.toast(error.message);
      }
    }

    function addConnection(){app.confirm('添加 Agent 连接','','connection');}
    function editConnection(connection){
      Object.assign(app.connection,{id:connection.id,name:connection.name,url:connection.url,model:connection.model});
      app.confirm('编辑 Agent 连接','','connection-edit');
    }
    async function checkSavedConnection(connection){
      try{
        const result=await app.api('/api/connections/'+encodeURIComponent(connection.id)+'/check',{method:'POST'});
        app.toast('已连接 · '+(result.health?.version||'Agent'));
      }catch(error){
        app.toast(error.message||'Agent 连接失败');
      }
    }
    async function removeConnection(id){
      try{
        await app.api('/api/connections/'+encodeURIComponent(id),{method:'DELETE'});
        state.connections=state.connections.filter(connection=>connection.id!==id);
        app.save();
      }catch(error){
        app.toast(error.message);
      }
    }

    async function restoreActiveRun(){
      if(path.value==='/workspace'&&state.user&&!state.activeRun)await app.runState.restore();
    }

    watch([path,()=>state.user?.id],restoreActiveRun,{immediate:true});
    watch(()=>state.user?.id,()=>{auth.value.password='';},{flush:'sync'});
    onMounted(()=>{
      if(path.value==='/workspace'){
        app.runState.attach();
        attached=true;
      }
    });
    onUnmounted(()=>{
      auth.value.password='';
      if(attached)app.runState.detach();
    });

    return {
      state,route,path,tab,isRecovery,auth,datasetName,submit,logout,
      addConnection,editConnection,checkSavedConnection,removeConnection,adminTab
    };
  },
  template:`
<div v-if="isRecovery" class="container page auth-page">
  <div class="login-layout"><div class="login-form"><PasswordRecovery/></div></div>
</div>

<div v-else-if="path==='/login'||path==='/register'" class="container page auth-page">
  <div class="login-layout"><div class="login-form">
    <h1>{{path==='/register'?'创建账号':'欢迎回来'}}</h1>
    <p>{{path==='/register'?'创建账号，保存你的实验记录。':'登录，继续你的实验。'}}</p>
    <form id="auth-form" class="stack" @submit.prevent="submit">
      <div v-if="path==='/register'" class="field">
        <label>研究者昵称</label>
        <input name="name" aria-label="研究者昵称" v-model="auth.name" required maxlength="30" placeholder="你的名字">
      </div>
      <div class="field">
        <label>电子邮箱</label>
        <input name="email" aria-label="电子邮箱" type="email" v-model="auth.email" required placeholder="researcher@example.com" autocomplete="email">
      </div>
      <div class="field">
        <label>密码</label>
        <input name="password" aria-label="密码" type="password" v-model="auth.password" required minlength="8" maxlength="24" placeholder="8 至 24 位" :autocomplete="path==='/register'?'new-password':'current-password'">
      </div>
      <button class="btn primary wide" type="submit">{{path==='/register'?'注册并进入':'登录'}}</button>
    </form>
    <div class="login-links">
      <a class="text-link" :href="path==='/login'?'#/register':'#/login'">{{path==='/login'?'创建账号':'返回登录'}}</a>
      <a href="#/forgot-password">忘记密码？</a>
    </div>
  </div></div>
</div>

<div v-else-if="path==='/workspace'&&!state.user" class="container page">
  <div class="card empty">
    <SvgIcon name="user" :size="32"/>
    <h1>你的研究工作台</h1>
    <p>登录后查看实验记录与 Agent 连接。</p>
    <a class="btn primary" href="#/login">进入工作台</a>
  </div>
</div>

<div v-else-if="path==='/workspace'" class="container page">
  <div class="page-header">
    <div><h1>你好，{{state.user.name}}</h1><p>你的实验记录与 Agent 连接。</p></div>
    <div class="header-actions"><button class="btn small" data-logout @click="logout">退出登录</button></div>
  </div>
  <div class="workspace-layout">
    <aside class="card workspace-nav">
      <a v-for="item in [['runs','file','测评记录'],['connections','server','Agent 连接'],['sync','refresh','报告同步']]" :key="item[0]" :class="{active:tab===item[0]}" :href="'#/workspace?tab='+item[0]">
        <SvgIcon :name="item[1]" :size="17"/>{{item[2]}}
      </a>
      <template v-if="state.user.role==='admin'">
        <div class="nav-label">管理</div>
        <a href="#/admin"><SvgIcon name="layers" :size="17"/>平台管理</a>
      </template>
    </aside>
    <section style="min-width:0">
      <div v-if="state.activeRun&&['queued','running','paused'].includes(state.activeRun.status)" class="callout" style="margin-bottom:20px">
        <SvgIcon name="activity" :size="18"/>有一个测评正在执行（{{state.activeRun.runId||state.activeRun.id}}），
        <a class="text-link" :href="'#'+(state.activeRun.kind==='pk'?'/arena':'/evaluate')+'?run='+encodeURIComponent(state.activeRun.runId)">继续查看进度</a>
      </div>
      <template v-if="tab==='connections'">
        <div class="content-top">
          <h2>Agent 连接</h2>
          <button class="btn primary small" data-add-connection @click="addConnection"><SvgIcon name="plus" :size="15"/> 添加连接</button>
        </div>
        <div class="callout"><SvgIcon name="shield" :size="18"/>这里只保存连接名称、端点和模型。凭证不写入浏览器存储。</div>
        <div class="stack" style="margin-top:20px">
          <div v-for="connection in state.connections" :key="connection.id" class="card card-pad row between connection-card">
            <div>
              <h3>{{connection.name}}</h3>
              <p class="mono">{{connection.url}}</p>
              <span class="inline-note">{{connection.model}} · 连通性需检查</span>
            </div>
            <div class="row">
              <button class="btn small" data-check-connection @click="checkSavedConnection(connection)">检查</button>
              <button class="btn small" data-edit-connection @click="editConnection(connection)">编辑</button>
              <button class="btn small danger" data-remove-connection @click="removeConnection(connection.id)">移除</button>
            </div>
          </div>
          <div v-if="!state.connections.length" class="card empty"><h3>还没有连接</h3><p>添加自己的 Agent 服务，准备下一次测评。</p></div>
        </div>
      </template>
      <template v-else>
        <div class="content-top">
          <h2>{{tab==='sync'?'报告同步':'测评记录'}}</h2>
          <a class="btn primary small" href="#/evaluate?new=1"><SvgIcon name="plus" :size="15"/> 新建测评</a>
        </div>
        <div v-if="tab==='sync'" class="callout"><SvgIcon name="refresh" :size="18"/>同步后仍为私有。公开报告需在详情页单独确认。</div>
        <div class="card table-scroll">
          <table>
            <thead><tr><th>实验 / 模型</th><th>测评集</th><th>通过率</th><th>来源</th><th>状态</th><th>报告</th></tr></thead>
            <tbody>
              <tr v-for="report in state.reports" :key="report.id">
                <td><strong class="workspace-record-name">{{report.agent}}</strong><small class="muted workspace-record-meta">{{report.model}} · {{report.date}}</small></td>
                <td>{{datasetName(report.datasetId)}}</td>
                <td class="score">{{Number(report.score||0).toFixed(1)}}%</td>
                <td><span class="badge" :class="report.source==='local'?'violet':'blue'">{{report.source==='local'?'本地':'云端'}}</span></td>
                <td><span class="badge" :class="report.published?'green':'gray'">{{report.published?'已公开':report.synced?'已同步 · 私有':'未同步 · 私有'}}</span></td>
                <td><a class="text-link" :href="'#/report/'+report.id">查看 <SvgIcon name="arrow" :size="14"/></a></td>
              </tr>
            </tbody>
          </table>
          <div v-if="!state.reports.length" class="empty">
            <SvgIcon name="file" :size="32"/><h3>等待你的第一次实验</h3><p>完成测评或 PK 后，报告会出现在这里。</p>
            <a class="btn primary" href="#/evaluate?new=1">开始测评</a>
          </div>
        </div>
      </template>
    </section>
  </div>
</div>

<div v-else-if="path==='/admin'&&state.user?.role!=='admin'" class="container page">
  <div class="card empty">
    <SvgIcon name="lock" :size="32"/><h1>无权限访问</h1><p>此页面仅对管理员开放。</p><a class="btn primary" href="#/login">返回登录</a>
  </div>
</div>

<div v-else class="container page">
  <div class="page-header">
    <div><h1>平台管理</h1><p>管理测评集和模型价格。</p></div>
    <div class="header-actions"><a class="btn" href="#/workspace">返回工作台</a></div>
  </div>
  <nav class="pill-switch admin-tabs" aria-label="平台管理功能">
    <button type="button" :class="{active:adminTab==='datasets'}" @click="adminTab='datasets'">测评集管理</button>
    <button type="button" :class="{active:adminTab==='pricing'}" @click="adminTab='pricing'">模型价格</button>
  </nav>
  <template v-if="adminTab==='datasets'"><CustomDatasetAdmin/></template>
  <template v-else><PricingAdmin/></template>
</div>`
};

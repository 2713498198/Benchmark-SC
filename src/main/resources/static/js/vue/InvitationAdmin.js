import {ref,onMounted,onBeforeUnmount,watch} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp} from './shared.js';

const inviteNames={pending:'待接受',accepted:'已接受',revoked:'已撤销',expired:'已过期'};
const deliveryNames={queued:'等待发送',dispatching:'正在发送',retry_wait:'等待自动重试',provider_accepted:'邮件服务已接收',local_outbox_written:'已写入本地测试邮箱',failed:'发送失败',cancelled:'已取消',expired:'已过期',legacy_unknown:'旧邀请，无投递记录'};
export default {setup(){
 const app=useApp(),rows=ref({items:[],total:0,page:0,size:20}),status=ref(''),deliveryFilter=ref(''),mail=ref(null),form=ref({email:'',name:''}),busy=ref(false),error=ref('');
 let alive=true,timer=null,request=0;
 async function load(){const own=++request;try{const query=new URLSearchParams({page:rows.value.page,size:rows.value.size,status:status.value,deliveryStatus:deliveryFilter.value});const [list,ready]=await Promise.all([app.api('/api/admin/invitations?'+query),app.api('/api/admin/mail/status')]);if(alive&&own===request){rows.value=list;mail.value=ready;error.value='';}}catch(e){if(alive&&own===request)error.value=e.message;}}
 async function invite(){busy.value=true;error.value='';try{await app.api('/api/admin/invitations',{method:'POST',body:form.value});form.value={email:'',name:''};rows.value.page=0;app.toast('邀请已加入发送队列');await load();}catch(e){error.value=e.message;}finally{busy.value=false;}}
 function action(item,kind){const resend=kind==='resend';app.confirm(resend?'重新发送邀请':'撤销邀请',resend?'将生成新链接，之前的邀请链接立即失效。':'撤销后，此邀请链接将无法创建管理员账号。','admin-action',async()=>{busy.value=true;try{await app.api('/api/admin/invitations/'+encodeURIComponent(item.id)+'/'+kind,{method:'POST'});app.toast(resend?'新邀请已加入发送队列':'邀请已撤销');await load();}catch(e){error.value=e.message;}finally{busy.value=false;}});}
 function changePage(delta){rows.value.page+=delta;load();}
 watch([status,deliveryFilter],()=>{rows.value.page=0;load();});
 onMounted(()=>{load();timer=setInterval(load,10000);});
 onBeforeUnmount(()=>{alive=false;++request;clearInterval(timer);});
 return {rows,status,deliveryFilter,mail,form,busy,error,load,invite,action,changePage,inviteNames,deliveryNames};
},template:`<section class="stack" aria-label="管理员邀请">
 <div class="content-top"><div><h2>管理员邀请</h2><p class="inline-note">查看邀请与发送进度，处理重发和撤销。</p></div><button class="btn small" @click="load" :disabled="busy">刷新</button></div>
 <div class="callout" v-if="mail&&!mail.ready">邮件功能尚未就绪（{{mail.errorCode}}），请检查部署的邮件通道和密钥配置。</div>
 <p class="inline-note">“邮件服务已接收”表示服务接受了发送请求，收件人仍需检查收件箱和垃圾邮件。本地测试邮箱不会向真实邮箱发信。</p>
 <form class="card card-pad two-col" @submit.prevent="invite"><div class="field"><label for="invitation-email">邮箱</label><input id="invitation-email" type="email" required maxlength="254" v-model="form.email" autocomplete="off"></div><div class="field"><label for="invitation-name">显示名称</label><input id="invitation-name" required maxlength="30" v-model="form.name"></div><div><button class="btn primary" type="submit" :disabled="busy||!mail?.ready">发送邀请</button></div></form>
 <p v-if="error" class="error-text" role="alert">{{error}}</p>
 <div class="row"><div class="field"><label for="invitation-state">邀请状态</label><select id="invitation-state" v-model="status"><option value="">全部状态</option><option v-for="(name,key) in inviteNames" :key="key" :value="key">{{name}}</option></select></div><div class="field"><label for="invitation-delivery">发送状态</label><select id="invitation-delivery" v-model="deliveryFilter"><option value="">全部状态</option><option v-for="(name,key) in deliveryNames" :key="key" :value="key">{{name}}</option></select></div></div>
 <div class="card table-scroll"><table><thead><tr><th>受邀者</th><th>邀请状态</th><th>发送进度</th><th>有效期至</th><th>操作</th></tr></thead><tbody><tr v-for="item in rows.items" :key="item.id"><td>{{item.name}}<br><small>{{item.email}}</small></td><td>{{inviteNames[item.status]||item.status}}</td><td>{{deliveryNames[item.deliveryStatus.state]||item.deliveryStatus.state}}<small v-if="item.deliveryStatus.attempts"> · 已尝试 {{item.deliveryStatus.attempts}} 次</small><br v-if="item.deliveryStatus.nextAttemptAt"><small v-if="item.deliveryStatus.nextAttemptAt">下次尝试：{{item.deliveryStatus.nextAttemptAt}}</small><br v-if="item.deliveryStatus.errorCode"><small v-if="item.deliveryStatus.errorCode">{{item.deliveryStatus.errorCode}}</small></td><td>{{item.expiresAt}}</td><td><div class="row"><button v-if="['pending','expired'].includes(item.status)" class="btn small" :disabled="busy||!mail?.ready" @click="action(item,'resend')">重发</button><button v-if="['pending','expired'].includes(item.status)" class="btn small danger" :disabled="busy" @click="action(item,'revoke')">撤销</button></div></td></tr></tbody></table><div class="empty" v-if="!rows.items.length">暂无匹配的邀请</div></div>
 <div class="row between"><span class="inline-note">共 {{rows.total}} 条邀请</span><div class="row"><button class="btn small" :disabled="rows.page<=0||busy" @click="changePage(-1)">上一页</button><button class="btn small" :disabled="(rows.page+1)*rows.size>=rows.total||busy" @click="changePage(1)">下一页</button></div></div>
</section>`};

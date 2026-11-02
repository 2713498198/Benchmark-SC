import {ref,computed,onMounted} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp} from './shared.js';

const emptyRow=()=>({model:'',inputPerMillionTokens:'',outputPerMillionTokens:'',currency:'USD'});
const normalized=model=>model.trim().toLowerCase().replace(/[-_]/g,' ').replace(/\s+/g,' ');
const decimal=value=>/^\d{1,12}(\.\d{1,8})?$/.test(String(value).trim());

export default {setup(){
 const app=useApp(),tables=ref([]),active=ref(null),loading=ref(false),busy=ref(false),error=ref('');
 const version=ref(''),rows=ref([emptyRow()]),preview=ref(null),confirmRemoved=ref(false),notice=ref('');
 const removed=computed(()=>preview.value?.changes.filter(c=>c.kind==='removed').length||0);
 const changed=computed(()=>preview.value?.changes.filter(c=>c.kind!=='unchanged')||[]);
 async function load(){loading.value=true;try{const [list,current]=await Promise.all([app.api('/api/admin/pricing'),app.api('/api/pricing/active')]);tables.value=list;active.value=current;}catch(err){error.value=err.message;}finally{loading.value=false;}}
 function reset(){version.value='';rows.value=[emptyRow()];notice.value='';error.value='';}
 function populate(table){
  version.value='';
  rows.value=table.prices.map(p=>({...p,inputPerMillionTokens:p.currency==='USD'?String(p.inputPerMillionTokens):'',outputPerMillionTokens:p.currency==='USD'?String(p.outputPerMillionTokens):'',currency:'USD'}));
  notice.value=table.prices.some(p=>p.currency!=='USD')?'原版本含非 USD 价格，已清空相应金额；请填写确认过的 USD 价格。':'已复制全部模型，请填写新版本号后保存。';error.value='';preview.value=null;
 }
 function validate(){
  if(!/^[a-zA-Z0-9][a-zA-Z0-9._-]{2,119}$/.test(version.value.trim()))throw Error('版本号需为 3 至 120 位字母、数字、点、下划线或连字符');
  if(!rows.value.length||rows.value.length>500)throw Error('价格表需要 1 至 500 个模型');
  const names=new Set();
  return rows.value.map((p,i)=>{const model=normalized(p.model);if(!model||model.length>160||names.has(model))throw Error('第 '+(i+1)+' 行模型名称为空、过长或重复');names.add(model);if(!decimal(p.inputPerMillionTokens)||!decimal(p.outputPerMillionTokens))throw Error('第 '+(i+1)+' 行价格需为非负数，最多 12 位整数和 8 位小数');return {model,inputPerMillionTokens:String(p.inputPerMillionTokens).trim(),outputPerMillionTokens:String(p.outputPerMillionTokens).trim(),currency:'USD'};});
 }
 async function save(){if(busy.value)return;error.value='';try{const prices=validate();busy.value=true;const table=await app.api('/api/admin/pricing',{method:'POST',body:{version:version.value.trim(),prices}});reset();notice.value='版本 '+table.version+' 已保存且不可修改。请查看启用差异后启用。';await load();app.toast('价格表版本已保存');}catch(err){error.value=err.message;}finally{busy.value=false;}}
 async function inspect(table){if(busy.value)return;busy.value=true;error.value='';try{preview.value=await app.api('/api/admin/pricing/'+encodeURIComponent(table.version)+'/diff');confirmRemoved.value=false;}catch(err){error.value=err.message;}finally{busy.value=false;}}
 async function activate(){if(busy.value||!preview.value||removed.value&&!confirmRemoved.value)return;const p=preview.value;busy.value=true;error.value='';try{await app.api('/api/admin/pricing/'+encodeURIComponent(p.targetVersion)+'/activate',{method:'POST',body:{expectedRevision:p.expectedRevision,expectedTableRevision:p.expectedTableRevision}});preview.value=null;await load();app.toast('价格表已启用；已创建运行保留原价格快照');}catch(err){error.value=err.message;preview.value=null;await load();}finally{busy.value=false;}}
 const label=kind=>({added:'新增',removed:'移除',changed:'调整',unchanged:'不变'})[kind]||kind;
 const price=p=>p?p.inputPerMillionTokens+' / '+p.outputPerMillionTokens+' '+p.currency:'—';
 onMounted(load);
 return {tables,active,loading,busy,error,version,rows,preview,confirmRemoved,notice,removed,changed,load,reset,populate,save,inspect,activate,label,price,emptyRow};
},template:`<section data-pricing-admin>
 <div class="content-top"><div><h2>模型价格</h2><p class="inline-note">每个版本包含完整模型价格。启用后仅新建运行采用新价格；历史报告保持原值。</p></div><button class="btn small" :disabled="loading||busy" @click="load">刷新</button></div>
 <p v-if="error" class="error-text" role="alert">{{error}}</p>
 <p v-if="loading" role="status">正在加载价格表…</p>
 <div v-if="active" class="callout"><span>当前版本：{{active.version}} · {{active.prices.length}} 个模型 · 每百万 Token 单价</span><button class="btn small" :disabled="busy" @click="populate(active)">复制当前完整价格表</button></div>
 <form class="card card-pad stack" @submit.prevent="save">
  <div class="row between"><h3>创建价格表新版本</h3><button class="btn small" type="button" :disabled="busy" @click="reset">新建空白草稿</button></div>
  <p v-if="notice" class="inline-note" role="status">{{notice}}</p>
  <div class="field"><label for="pricing-version">价格表版本</label><input id="pricing-version" v-model="version" :disabled="busy" required maxlength="120" placeholder="prices-2026-11-01"></div>
  <div class="table-scroll"><table><thead><tr><th>模型标识</th><th>输入 USD / 百万 Token</th><th>输出 USD / 百万 Token</th><th>操作</th></tr></thead><tbody><tr v-for="(row,i) in rows" :key="i"><td><input :aria-label="'第 '+(i+1)+' 行模型'" v-model="row.model" :disabled="busy" required maxlength="160"></td><td><input :aria-label="'第 '+(i+1)+' 行输入价格'" v-model="row.inputPerMillionTokens" :disabled="busy" inputmode="decimal" required placeholder="0.00000000"></td><td><input :aria-label="'第 '+(i+1)+' 行输出价格'" v-model="row.outputPerMillionTokens" :disabled="busy" inputmode="decimal" required placeholder="0.00000000"></td><td><button class="btn small danger" type="button" :disabled="busy" :aria-label="'移除第 '+(i+1)+' 行'" @click="rows.splice(i,1)">移除</button></td></tr></tbody></table></div>
  <div class="row"><button class="btn small" type="button" :disabled="busy||rows.length>=500" @click="rows.push(emptyRow())">添加模型</button><button class="btn primary" type="submit" :disabled="busy">{{busy?'处理中…':'保存完整版本'}}</button></div>
 </form>
 <section v-if="preview" class="card card-pad stack" data-pricing-diff>
  <h3>启用 {{preview.targetVersion}}</h3><p>将替换当前完整价格表 {{preview.currentVersion}}。下列模型发生变化：</p>
  <div class="table-scroll"><table><thead><tr><th>模型</th><th>变化</th><th>原输入 / 输出</th><th>新输入 / 输出</th></tr></thead><tbody><tr v-for="item in changed" :key="item.model"><td>{{item.model}}</td><td>{{label(item.kind)}}</td><td>{{price(item.before)}}</td><td>{{price(item.after)}}</td></tr></tbody></table></div><p v-if="!changed.length">价格与当前版本相同。</p>
  <label v-if="removed" class="row"><input type="checkbox" v-model="confirmRemoved" :disabled="busy">确认移除 {{removed}} 个模型的价格；它们在新的运行中将显示费用未知。</label>
  <p v-if="!preview.usdOnly" class="error-text">历史版本含非 USD 价格，请复制并填写 USD 价格后再启用。</p>
  <div class="row"><button class="btn primary" :disabled="busy||!preview.usdOnly||(removed&&!confirmRemoved)" @click="activate">确认启用此完整版本</button><button class="btn" :disabled="busy" @click="preview=null">取消</button></div>
 </section>
 <div class="card table-scroll"><table><thead><tr><th>版本</th><th>状态</th><th>模型与价格（输入 / 输出）</th><th>操作</th></tr></thead><tbody><tr v-for="table in tables" :key="table.version"><td>{{table.version}}</td><td>{{table.status==='active'?'当前启用':table.status==='draft'?'未启用':'历史版本'}}</td><td><div v-for="p in table.prices" :key="p.model">{{p.model}} · {{price(p)}}</div></td><td><div class="row"><button class="btn small" :disabled="busy" @click="populate(table)">复制为新版本</button><button v-if="table.status!=='active'" class="btn small" :disabled="busy" @click="inspect(table)">查看启用差异</button></div></td></tr></tbody></table><p v-if="!tables.length&&!loading" class="empty">尚无数据库价格表。可复制当前配置价格创建第一个完整版本。</p></div>
</section>`};

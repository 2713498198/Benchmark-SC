import {ref,computed,onUnmounted} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp} from './shared.js';
import {downloadFile} from '../core.js';

export const customDatasetTemplate={
 schema:'cryptobench-custom-dataset-v1',id:'custom-spn',name:'自定义 SPN 任务',type:'分组密码',algorithm:'教学 SPN',version:'1.0.0',description:'验证公开输入与结构化答案的任务样例。',difficulty:'入门',
 tasks:[
  {id:'task-001',question:'返回公开输入中的轮数和结构名称。',input:{rounds:4,structure:'SPN'},category:'结构识别',verification:{type:'exact-json-v1',expected:{rounds:4,structure:'SPN'}}},
  {id:'task-002',question:'计算公开样本中事件发生的比例。',input:{hits:1,trials:4},category:'数值计算',verification:{type:'numeric-v1',expected:0.25,absoluteTolerance:0.0001,relativeTolerance:0}}
 ]
};

export default {setup(){
 const app=useApp(),raw=ref(''),preview=ref(null),issues=ref([]),error=ref(''),busy=ref(false),validatedText=ref('');
 const canPublish=computed(()=>!!preview.value&&raw.value===validatedText.value&&!busy.value);
 const published=computed(()=>app.state.customDatasets||[]);
 function invalidate(){preview.value=null;validatedText.value='';issues.value=[];error.value='';}
 function fillTemplate(){raw.value=JSON.stringify(customDatasetTemplate,null,2);invalidate();}
 function templateDownload(){downloadFile('custom-dataset-template.json',JSON.stringify(customDatasetTemplate,null,2));}
 async function fileSelected(event){const file=event.target.files?.[0];if(!file)return;invalidate();if(file.size>2_000_000){error.value='任务包不得超过 2,000,000 字节';return;}raw.value=await file.text();}
 function parsed(){if(new TextEncoder().encode(raw.value).byteLength>2_000_000)throw Error('任务包不得超过 2,000,000 字节');const value=JSON.parse(raw.value);if(!value||Array.isArray(value)||typeof value!=='object')throw Error('任务包必须是 JSON 对象');return value;}
 async function validate(){if(busy.value)return;invalidate();busy.value=true;const submitted=raw.value;try{parsed();const result=await app.api('/api/datasets/custom/validate',{method:'POST',body:submitted});if(raw.value!==submitted)return;issues.value=result.errors||[];if(result.valid){preview.value=result.preview;validatedText.value=submitted;}}catch(err){error.value=err.message||'校验失败';}finally{busy.value=false;}}
 async function publish(){if(!canPublish.value)return;busy.value=true;const submitted=validatedText.value;try{const result=await app.api('/api/datasets/custom',{method:'POST',body:submitted});app.state.customDatasets=[...app.state.customDatasets.filter(d=>!(d.id===result.id&&d.version===result.version)),result];app.save();invalidate();raw.value='';app.toast('题库固定版本已发布，可以开始测评');}catch(err){error.value=err.message||'发布失败';}finally{busy.value=false;}}
 onUnmounted(()=>{raw.value='';validatedText.value='';preview.value=null;});
 return {raw,preview,issues,error,busy,canPublish,published,invalidate,fillTemplate,templateDownload,fileSelected,validate,publish};
},template:`<section data-custom-dataset-admin><div class="content-top"><div><h2>测评集管理</h2><p class="inline-note">上传 JSON 任务包，校验公开题目与私有评分规则，再发布不可覆盖的固定版本。</p></div><button class="btn small" @click="templateDownload">下载任务包模板</button></div><div class="card card-pad"><div class="row"><button class="btn small" data-template @click="fillTemplate" :disabled="busy">填入模板</button><label>选择 JSON 文件 <input aria-label="选择任务包文件" type="file" accept="application/json,.json" @change="fileSelected" :disabled="busy"></label></div><label for="dataset-json">任务包内容（最多 2,000,000 字节）</label><textarea id="dataset-json" v-model="raw" @input="invalidate" :disabled="busy" rows="16" spellcheck="false" style="width:100%"></textarea><p class="inline-note">公开输入使用 task.input。标准答案放在 verification.expected 中，只供服务端验证。当前支持 JSON 精确匹配与数值容差；附件包与脚本评分器请勿放入任务包。</p><button class="btn primary" data-validate @click="validate" :disabled="busy||!raw.trim()">{{busy?'处理中…':'校验并预览'}}</button><p v-if="error" id="import-error" role="alert">{{error}}</p><div v-if="issues.length" id="import-error" role="alert"><p>请修正以下字段：</p><ul><li v-for="(issue,index) in issues" :key="index"><code>{{issue.path}}</code>：{{issue.message}}</li></ul></div></div><section v-if="preview" class="card card-pad admin-preview"><h3>公开预览：{{preview.dataset.name}}</h3><p>{{preview.dataset.id}} · {{preview.dataset.version}} · {{preview.tasks.length}} 道任务</p><p>{{preview.dataset.description}}</p><p>版本发布后立即可用于测评；更改题干或评分规则须发布新版本。</p><details><summary>查看公开内容</summary><pre style="white-space:pre-wrap">{{JSON.stringify(preview,null,2)}}</pre></details><button class="btn primary" data-publish-dataset :disabled="!canPublish" @click="publish">发布固定版本</button></section><div class="card table-scroll"><table><thead><tr><th>测评集</th><th>版本</th><th>任务</th><th>状态</th></tr></thead><tbody><tr v-for="dataset in published" :key="dataset.id+'@'+dataset.version"><td><a :href="'#/datasets/'+encodeURIComponent(dataset.id)+'?version='+encodeURIComponent(dataset.version)">{{dataset.name||dataset.id}}</a></td><td>{{dataset.version}}</td><td>{{dataset.tasks}}</td><td>{{dataset.executionReady?'可执行':'旧版目录 · 需发布新版评分规则'}}</td></tr></tbody></table><p v-if="!published.length" class="empty">尚未发布自定义题库。</p></div></section>`};

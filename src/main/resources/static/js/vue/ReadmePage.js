import {ref,computed,watch} from '/vendor/vue/vue.esm-browser.prod.js';
import {useApp,SvgIcon} from './shared.js';

export default {components:{SvgIcon},setup(){
 const {route}=useApp();
 const filePath=computed(()=>{
  const value=new URLSearchParams(route.value.split('?').slice(1).join('?')).get('file')||'README.md';
  return /^(?:README\.md|downloads\/offline\/README\.md)$/.test(value)?value:'README.md';
 });
 const title=computed(()=>filePath.value.split('/').at(-1));
 const content=ref(''),error=ref(''),loading=ref(true);
 async function load(){
  loading.value=true;error.value='';
  try{const response=await fetch(filePath.value,{cache:'no-store'});if(!response.ok)throw Error(`无法加载 ${title.value}`);content.value=await response.text();}
  catch(err){error.value=err.message||'无法加载说明文档';}
  finally{loading.value=false;}
 }
 watch(filePath,load,{immediate:true});
 return {content,error,loading,load,filePath,title};
},template:`<div class="container page readme-page">
 <div class="page-header"><div><h1>{{title}}</h1><p>用户智能体接入与本地测评说明</p></div><div class="header-actions"><a class="btn secondary" :href="filePath" download><SvgIcon name="download" :size="16"/>下载 Markdown</a></div></div>
 <section class="card card-pad readme-viewer" aria-live="polite">
  <p v-if="loading" role="status">正在加载说明文档…</p>
  <div v-else-if="error" class="empty" role="alert"><p>{{error}}</p><button class="btn" @click="load">重新加载</button></div>
  <pre v-else>{{content}}</pre>
 </section>
</div>`};

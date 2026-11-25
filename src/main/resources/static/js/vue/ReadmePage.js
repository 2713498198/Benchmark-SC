import {ref,onMounted} from '/vendor/vue/vue.esm-browser.prod.js';
import {SvgIcon} from './shared.js';

export default {components:{SvgIcon},setup(){
 const content=ref(''),error=ref(''),loading=ref(true);
 async function load(){
  loading.value=true;error.value='';
  try{const response=await fetch('README.md',{cache:'no-store'});if(!response.ok)throw Error('无法加载 README.md');content.value=await response.text();}
  catch(err){error.value=err.message||'无法加载说明文档';}
  finally{loading.value=false;}
 }
 onMounted(load);
 return {content,error,loading,load};
},template:`<div class="container page readme-page">
 <div class="page-header"><div><h1>README.md</h1><p>用户智能体接入与本地测评说明</p></div><div class="header-actions"><a class="btn secondary" href="README.md" download><SvgIcon name="download" :size="16"/>下载 Markdown</a></div></div>
 <section class="card card-pad readme-viewer" aria-live="polite">
  <p v-if="loading" role="status">正在加载说明文档…</p>
  <div v-else-if="error" class="empty" role="alert"><p>{{error}}</p><button class="btn" @click="load">重新加载</button></div>
  <pre v-else>{{content}}</pre>
 </section>
</div>`};

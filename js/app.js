import {icon,escapeHTML} from './core.js';
import {datasets} from './data.js';
import * as home from './pages/home.js';
import * as resources from './pages/resources.js';
import * as arena from './pages/arena.js';
import * as community from './pages/community.js';
const defaults={user:null,reports:[],connections:[],customDatasets:[],environment:'cloud'};
let saved={};try{saved=JSON.parse(localStorage.getItem('cryptobench.v1')||'{}');}catch{}
// localStorage is retained only for non-authoritative UI preferences. Identity
// and account-owned data always come from /api/bootstrap after the server
// validates the HttpOnly session cookie.
const state={...defaults,environment:saved.environment,activeRun:null};
for(const k of ['reports','connections','customDatasets'])if(!Array.isArray(state[k]))state[k]=[];
if(!['local','cloud'].includes(state.environment))state.environment='cloud';
let route='',toastTimer;
const labels={'/':'首页','/datasets':'测评集','/tools':'测评工具','/leaderboard':'测评榜单','/arena':'PK 竞技'};
async function api(path,options={}){const init={credentials:'same-origin',...options,headers:{...(options.body?{'Content-Type':'application/json'}:{}),...(options.headers||{})}};if(init.body&&typeof init.body!=='string')init.body=JSON.stringify(init.body);const res=await fetch(path,init);let body=null;try{body=await res.json();}catch{}if(!res.ok)throw Error(body?.error||'请求失败');return body;}
const ctx={state,navigate,toast,render,save,api,modal,closeModal:()=>document.querySelector('#modal').close(),getDatasets:()=>[...datasets,...state.customDatasets.filter(d=>d.published)]};
function save(){try{localStorage.setItem('cryptobench.v1',JSON.stringify({reports:state.reports,connections:state.connections,customDatasets:state.customDatasets,environment:state.environment}));}catch{toast('浏览器存储空间不足，当前数据暂未保存');}}
async function hydrate(){try{const b=await api('/api/bootstrap');state.user=b.user||null;state.reports=(Array.isArray(b.reports)?b.reports:[]).sort((a,b)=>Date.parse(b.createdAt||0)-Date.parse(a.createdAt||0));state.connections=Array.isArray(b.connections)?b.connections:[];state.customDatasets=Array.isArray(b.customDatasets)?b.customDatasets:[];state.publicReports=Array.isArray(b.publicReports)?b.publicReports:[];save();render();}catch{ // A failed bootstrap cannot establish identity or ownership.
 state.user=null;state.reports=[];state.connections=[];state.customDatasets=[];state.publicReports=[];save();render();
}}
function navigate(path){if(location.hash==='#'+path)render();else location.hash=path;}
function toast(message){const el=document.querySelector('#toast');el.textContent=message;el.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('show'),3700);}
function modal(title,body,actions=''){const d=document.querySelector('#modal');d.innerHTML='<div class="dialog-head"><h2>'+escapeHTML(title)+'</h2><button class="icon-button" data-close-modal aria-label="关闭">'+icon('close',18)+'</button></div>'+body+(actions?'<div class="dialog-actions">'+actions+'</div>':'');if(!d.open)d.showModal();d.querySelector('[data-close-modal]').onclick=()=>d.close();return d;}
function shell(){
 const active=route.split('?')[0];
 document.querySelector('#header').innerHTML='<div class="topbar-inner"><a class="brand" href="#/"><img src="assets/logo.svg" alt=""><span>CryptoBench</span></a><nav class="nav" aria-label="主导航">'+Object.entries(labels).map(([path,label])=>'<a href="#'+path+'" class="'+((path==='/'?active==='/':active.startsWith(path))?'active':'')+'" '+((path==='/'?active==='/':active.startsWith(path))?'aria-current="page"':'')+'>'+label+'</a>').join('')+'</nav><div class="nav-end"><button class="environment" data-environment title="切换演示运行环境" aria-label="切换演示运行环境">'+icon(state.environment==='cloud'?'cloud':'terminal',16)+'<span>'+(state.environment==='cloud'?'云端':'本地')+'</span></button><a class="btn '+(state.user?'':'primary')+' small" href="#/'+(state.user?'workspace':'login')+'">'+icon('user',14)+' '+(state.user?'工作台':'登录')+'</a><button class="icon-button menu-toggle" aria-label="打开导航菜单" aria-expanded="false">'+icon('menu',18)+'</button></div></div>';
 document.querySelector('.menu-toggle').onclick=e=>{const n=document.querySelector('.nav');n.classList.toggle('open');e.currentTarget.setAttribute('aria-expanded',String(n.classList.contains('open')));};
 document.querySelector('[data-environment]').onclick=()=>{modal('切换演示环境','<p>选择要预览的运行环境，测评规则保持一致。</p>','<button class="btn" data-env="local">预览本地环境</button><button class="btn primary" data-env="cloud">预览云端平台</button>');document.querySelectorAll('[data-env]').forEach(b=>b.onclick=()=>{state.environment=b.dataset.env;save();ctx.closeModal();render();});};
 document.querySelector('#footer').innerHTML='<div class="footer"><div class="container"><div class="footer-top"><div class="brand"><img src="assets/logo.svg" alt=""><span>CryptoBench</span></div><div class="footer-links"><a href="#/docs">接入文档</a><a href="#/download">本地部署</a><a href="#/docs?tab=methodology">测评方法</a><a href="#/docs?tab=about">关于平台</a></div></div><div class="footer-bottom"><span>© 2026 CryptoBench</span></div></div></div>';
}
function render(){
 home.dispose();
 community.dispose();
 const previousRoute=route;
 try{route=decodeURI(location.hash.slice(1)||'/');}catch{route='/404';}if(!route.startsWith('/'))route='/'+route;
 let html;
 if(route==='/'||route==='')html=home.render(route,state);
 else if(/^\/(datasets|tools|download|docs)(\/|\?|$)/.test(route))html=resources.render(route,state);
 else if(/^\/(evaluate|arena|report)(\/|\?|$)/.test(route))html=arena.render(route,state,previousRoute);
 else if(/^\/(leaderboard|login|register|forgot-password|workspace|admin)(\/|\?|$)/.test(route))html=community.render(route,state);
 else html='<div class="container page"><div class="card empty">'+icon('search',32)+'<h1>页面未找到</h1><p>这个地址暂时不存在。</p><a class="btn primary" href="#/">返回首页</a></div></div>';
 shell();document.querySelector('#main').innerHTML=html;document.querySelector('#main').dataset.route=route;
 document.title=(Object.entries(labels).find(([p])=>p!=='/'&&route.startsWith(p))?.[1]||'密码分析智能体测评')+' · CryptoBench';
 home.bind();resources.bind?.(ctx);arena.bind?.(ctx);community.bind?.(ctx);
 document.querySelectorAll('[data-planned]').forEach(b=>b.onclick=()=>toast(b.dataset.planned+'正在规划中，当前开放分组密码分析'));
 document.querySelectorAll('[data-copy]').forEach(b=>b.onclick=async()=>{try{await navigator.clipboard.writeText(b.dataset.copy);toast('已复制');}catch{toast('复制不可用，请手动选择代码');}});
}
document.querySelector('.skip-link').addEventListener('click',ev=>{ev.preventDefault();const main=document.querySelector('#main');main.focus({preventScroll:true});main.scrollIntoView({behavior:'instant'});});
window.addEventListener('hashchange',()=>{render();window.scrollTo({top:0,behavior:'instant'});});
document.querySelector('#modal').addEventListener('click',e=>{if(e.target===e.currentTarget&&e.clientX!==0){const r=e.currentTarget.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)e.currentTarget.close();}});
render();
hydrate();


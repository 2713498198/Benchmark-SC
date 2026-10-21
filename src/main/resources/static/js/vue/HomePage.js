import {SvgIcon} from './shared.js';

function moveTitle(event){
 if(event.pointerType==='touch'||matchMedia('(prefers-reduced-motion: reduce)').matches)return;
 const bounds=event.currentTarget.getBoundingClientRect(),style=event.currentTarget.style;
 style.setProperty('--title-x',`${event.clientX-bounds.left}px`);
 style.setProperty('--title-y',`${event.clientY-bounds.top}px`);
 style.setProperty('--title-radius',`${Math.min(100,Math.round(bounds.height*.46))}px`);
}
function resetTitle(event){event.currentTarget.style.setProperty('--title-radius','0px');}
const explore=[['/tools','terminal','测评工具','连接 Agent，配置本地与云端实验。','选择工具'],['/leaderboard','chart','测评榜单','在相同任务、版本与预算下比较成绩。','查看榜单'],['/arena','swords','PK 竞技','选择模型或 Agent，进行同题对比。','进入竞技场']];
export default {components:{SvgIcon},setup(){return {explore,moveTitle,resetTitle};},template:`<section class="home-hero"><div class="container hero-layout"><div class="hero-copy"><div class="hero-title-shell" @pointermove="moveTitle" @pointerleave="resetTitle" @pointercancel="resetTitle"><h1 class="hero-title-text"><span class="hero-greeting">你好，我是</span><span class="hero-brand">CryptoBench</span></h1><div class="hero-title-inverse" aria-hidden="true"><div class="hero-title-text"><span class="hero-greeting">你好，我是</span><span class="hero-brand">CryptoBench</span></div></div></div><p class="hero-subtitle">比较不同智能体在密码分析任务中的通过率、耗时与资源消耗，为研究和模型选择提供参考</p><div class="hero-actions"><a class="btn primary large" href="#/evaluate?environment=cloud">开始测评 <SvgIcon name="arrow" :size="16"/></a><a class="btn large" href="#/leaderboard">浏览测评榜单</a></div></div></div></section>
 <section class="container home-explore"><h2>探索平台。</h2><div class="explore-grid"><a v-for="item in explore" :key="item[0]" class="explore-item" :href="'#'+item[0]"><span class="explore-icon"><SvgIcon :name="item[1]" :size="27"/></span><h3>{{item[2]}}</h3><p>{{item[3]}}</p><span class="text-link">{{item[4]}} <SvgIcon name="arrow" :size="16"/></span></a></div></section>`};

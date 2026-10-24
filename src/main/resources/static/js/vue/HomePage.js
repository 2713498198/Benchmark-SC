import {SvgIcon} from './shared.js';
import HomeCostChart from './HomeCostChart.js';

function moveTitle(event){
 if(event.pointerType==='touch'||matchMedia('(prefers-reduced-motion: reduce)').matches)return;
 const bounds=event.currentTarget.getBoundingClientRect(),style=event.currentTarget.style;
 style.setProperty('--title-x',`${event.clientX-bounds.left}px`);
 style.setProperty('--title-y',`${event.clientY-bounds.top}px`);
 style.setProperty('--title-radius',`${Math.min(100,Math.round(bounds.height*.46))}px`);
}
function resetTitle(event){event.currentTarget.style.setProperty('--title-radius','0px');}

const explore=[['/tools','测评工具','连接本地 Agent，配置测评。','选择工具'],['/leaderboard','测评榜单','在相同条件下比较每次测评。','查看榜单'],['/arena','PK 竞技','让模型与智能体同题对比。','进入竞技场']];
export default {components:{SvgIcon,HomeCostChart},setup(){return {explore,moveTitle,resetTitle};},template:`<section class="home-hero"><div class="container hero-layout"><div class="hero-copy"><div class="hero-title-shell" @pointermove="moveTitle" @pointerleave="resetTitle" @pointercancel="resetTitle"><h1 class="hero-title-text">CryptoBench</h1><div class="hero-title-inverse" aria-hidden="true"><div class="hero-title-text">CryptoBench</div></div></div><p class="hero-subtitle">比较智能体在密码分析任务中的表现。</p><div class="hero-actions"><a class="btn primary large" href="#/evaluate">开始测评 <SvgIcon name="arrow" :size="16"/></a><a class="text-link" href="#/leaderboard">查看榜单 <SvgIcon name="arrow" :size="16"/></a></div></div></div></section>
 <HomeCostChart/>
 <section class="container home-explore"><h2>探索平台</h2><div class="explore-grid"><a v-for="item in explore" :key="item[0]" class="explore-item" :href="'#'+item[0]"><h3>{{item[1]}}</h3><p>{{item[2]}}</p><span class="text-link">{{item[3]}} <SvgIcon name="arrow" :size="16"/></span></a></div></section>`};

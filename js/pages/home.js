import {icon} from '../core.js';

function geometry(){
 const circuit=`<svg viewBox="0 0 200 200" fill="none" aria-hidden="true"><g stroke="currentColor" stroke-width="1.05" stroke-linecap="round" stroke-linejoin="round">
 <path class="cipher-chip" d="M28 28h48v48H28zm96 0h48v48h-48zM28 124h48v48H28zm96 0h48v48h-48z"/>
 <path d="M44 44h16v16H44zm96 0h16v16h-16zM44 140h16v16H44zm96 0h16v16h-16zM76 52h24v24m48 0v24h-24m0 48h-24v-24m-48 0v-24h24"/>
 <path d="m100 76 24 24-24 24-24-24Z"/><circle cx="100" cy="100" r="7"/>
 <path d="M52 28V0m96 28V0m24 52h28m-28 96h28M148 172v28m-96-28v28M28 148H0m28-96H0" opacity=".4"/>
 </g><g fill="currentColor"><path d="M-2-2h4v4h-4zm200 0h4v4h-4zM-2 198h4v4h-4zm200 0h4v4h-4z"/></g></svg>`;
 return `<div class="cipher-sculpture"><p class="sr-only" id="cipher-instructions">点击或按空格、回车键暂停或继续动画；拖动或使用方向键旋转，Home 键复位。系统开启减少动态效果时，仅响应手动旋转。</p>
 <div class="cipher-viewport" tabindex="0" role="button" aria-label="暂停密码几何体动画" aria-pressed="false" aria-describedby="cipher-instructions" aria-keyshortcuts="Space Enter ArrowLeft ArrowRight ArrowUp ArrowDown Home">
 <div class="cipher-aura" aria-hidden="true"></div><div class="cipher-shadow" aria-hidden="true"></div><div class="cipher-object" aria-hidden="true"><div class="cipher-core">${['front','back','right','left','top','bottom'].map(side=>`<div class="cipher-face cipher-face-${side}">${circuit}</div>`).join('')}</div>${['front','back','right','left','top','bottom'].map(side=>`<div class="cipher-face cipher-face-${side}">${circuit}</div>`).join('')}<i class="cipher-axis axis-x"></i><i class="cipher-axis axis-y"></i><i class="cipher-axis axis-z"></i></div>
 </div></div>`;
}

export function render(){
 return `<section class="home-hero"><div class="container hero-layout"><div class="hero-copy">
 <h1>密码分析，<span>同题验证。</span></h1><p class="hero-subtitle">面向智能体的标准化测评平台。</p>
 <div class="hero-actions"><a class="btn primary large" href="#/evaluate?environment=cloud">开始测评 ${icon('arrow',16)}</a><a class="btn large" href="#/leaderboard">浏览测评榜单</a></div>
 <p class="hero-disclosure">测评与榜单为交互演示，真实执行器待接入。</p></div>${geometry()}</div></section>
 <section class="container home-explore"><h2>探索平台。</h2><div class="explore-grid">${[
 ['/tools','terminal','测评工具','连接 Agent，配置本地与云端实验。','选择工具'],
 ['/leaderboard','chart','测评榜单','在相同任务、版本与预算下比较成绩。','查看榜单'],
 ['/arena','swords','PK 竞技','选择模型或 Agent，进行同题对比。','进入竞技场']
 ].map(([url,glyph,title,description,action])=>`<a class="explore-item" href="#${url}"><span class="explore-icon">${icon(glyph,27)}</span><h3>${title}</h3><p>${description}</p><span class="text-link">${action} ${icon('arrow',16)}</span></a>`).join('')}</div></section>`;
}

let cleanup=()=>{};
export function dispose(){cleanup();cleanup=()=>{};}

export function bind(){
 dispose();
 const viewport=document.querySelector('.cipher-viewport');
 if(!viewport)return;
 const object=viewport.querySelector('.cipher-object'),shadow=viewport.querySelector('.cipher-shadow');
 const core=object.querySelector('.cipher-core');
 const faces=[...object.querySelectorAll(':scope > .cipher-face')];
 const normals=[[0,0,1],[0,0,-1],[1,0,0],[-1,0,0],[0,-1,0],[0,1,0]];
 const motion=matchMedia('(prefers-reduced-motion: reduce)'),events=new AbortController();
 const options={signal:events.signal};
 const pose={pitch:-24,yaw:-32,roll:-6,lift:0},pointer={x:0,y:0},velocity={x:0,y:0};
 let basePitch=-24,phase=0,paused=false,visible=true,drag=null,frame=0,previous=0,suppressClick=false;
 const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));
 const draw=()=>{
  object.style.transform=`translate3d(0,${pose.lift.toFixed(3)}px,0) rotateX(${pose.pitch.toFixed(3)}deg) rotateY(${pose.yaw.toFixed(3)}deg) rotateZ(${pose.roll.toFixed(3)}deg)`;
  shadow.style.transform=`translateX(-50%) scale(${(1+pose.lift*.009).toFixed(3)})`;
  shadow.style.opacity=(.65+pose.lift*.018).toFixed(3);
  core.style.transform=`rotateX(${(phase*6).toFixed(3)}deg) rotateY(${(-phase*14).toFixed(3)}deg) rotateZ(${(phase*4).toFixed(3)}deg)`;
  // Light stays in the scene while each translucent face turns through it.
  // The shared draw clock also freezes reflections and the core on pause.
  const rad=Math.PI/180,x=pose.pitch*rad,y=pose.yaw*rad,z=pose.roll*rad;
  faces.forEach((face,i)=>{
   const [nx,ny,nz]=normals[i],rx=nx*Math.cos(z)-ny*Math.sin(z),ry=nx*Math.sin(z)+ny*Math.cos(z);
   const wx=rx*Math.cos(y)+nz*Math.sin(y),wz=-rx*Math.sin(y)+nz*Math.cos(y);
   const wy=ry*Math.cos(x)-wz*Math.sin(x),depth=ry*Math.sin(x)+wz*Math.cos(x);
   const light=clamp((-.35*wx-.55*wy+.76*depth+1)/2,0,1);
   face.style.setProperty('--light',(.08+light*.3).toFixed(3));
   face.style.setProperty('--shine-x',`${(50-wx*38).toFixed(2)}%`);
   face.style.setProperty('--shine-y',`${(50+wy*38).toFixed(2)}%`);
  });
 };
 const running=()=>!events.signal.aborted&&!paused&&!motion.matches&&!drag&&visible&&!document.hidden;
 const tick=time=>{
  frame=0;if(!running()){previous=0;return;}
  const dt=previous?Math.min(time-previous,48)/1000:0,ease=1-Math.exp(-dt*3);
  phase+=dt;
  // All motion shares one clock, so pausing, dragging and leaving the page never jump.
  pose.yaw=(pose.yaw+(10+Math.sin(phase*.7)*2+velocity.x+pointer.x*3)*dt)%360;
  basePitch=clamp(basePitch+velocity.y*dt,-46,34);
  pose.pitch+=(basePitch+Math.sin(phase*.55)*10+pointer.y*5-pose.pitch)*ease;
  pose.roll+=(-6+Math.sin(phase*.42)*7+pointer.x*3-pose.roll)*ease;
  pose.lift+=(Math.sin(phase*.85)*8-pose.lift)*ease;
  velocity.x*=Math.exp(-dt*4);velocity.y*=Math.exp(-dt*4);
  previous=time;draw();frame=requestAnimationFrame(tick);
 };
 const update=()=>{
  viewport.setAttribute('aria-pressed',String(paused||motion.matches));
  viewport.setAttribute('aria-label',motion.matches?'密码几何体，自动动画已关闭，可手动旋转':paused?'播放密码几何体动画':'暂停密码几何体动画');
  if(running()){if(!frame)frame=requestAnimationFrame(tick);}
  else{cancelAnimationFrame(frame);frame=0;previous=0;}
 };
 viewport.addEventListener('click',()=>{
  if(suppressClick){suppressClick=false;return;}
  if(!motion.matches){paused=!paused;update();}
 },options);
 viewport.addEventListener('pointerdown',event=>{
  if(!event.isPrimary||event.button!==0)return;
  suppressClick=false;velocity.x=0;velocity.y=0;
  drag={id:event.pointerId,x:event.clientX,y:event.clientY,distance:0,time:event.timeStamp};
  viewport.setPointerCapture(event.pointerId);viewport.classList.add('is-dragging');update();
 },options);
 viewport.addEventListener('pointermove',event=>{
  if(!drag){
   if(event.pointerType==='mouse'&&running()){
    const bounds=viewport.getBoundingClientRect();
    pointer.x=clamp((event.clientX-bounds.left)/bounds.width*2-1,-1,1);
    pointer.y=clamp((event.clientY-bounds.top)/bounds.height*2-1,-1,1);
   }
   return;
  }
  if(event.pointerId!==drag.id)return;
  const dx=event.clientX-drag.x,dy=event.clientY-drag.y,dt=Math.max(8,event.timeStamp-drag.time)/1000;
  drag.distance+=Math.hypot(dx,dy);
  pose.yaw+=dx*.4;pose.pitch=clamp(pose.pitch-dy*.3,-58,46);
  velocity.x=clamp(dx*.4/dt,-100,100);velocity.y=clamp(-dy*.3/dt,-60,60);
  drag.x=event.clientX;drag.y=event.clientY;drag.time=event.timeStamp;draw();
 },options);
 viewport.addEventListener('pointerleave',()=>{pointer.x=0;pointer.y=0;},options);
 const endDrag=event=>{
  if(!drag||event.pointerId!==drag.id)return;
  suppressClick=drag.distance>5;
  if(event.type!=='pointerup'||event.timeStamp-drag.time>100){velocity.x=0;velocity.y=0;}
  basePitch=pose.pitch-Math.sin(phase*.55)*10-pointer.y*5;
  drag=null;viewport.classList.remove('is-dragging');update();
 };
 for(const event of ['pointerup','pointercancel','lostpointercapture'])viewport.addEventListener(event,endDrag,options);
 viewport.addEventListener('keydown',event=>{
  if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home',' ','Enter'].includes(event.key))return;
  event.preventDefault();
  if(event.key===' '||event.key==='Enter'){
   if(!motion.matches&&!event.repeat)paused=!paused;
  }
  else{
   paused=true;velocity.x=0;velocity.y=0;
   if(event.key==='Home'){Object.assign(pose,{pitch:-24,yaw:-32,roll:-6,lift:0});phase=0;pointer.x=0;pointer.y=0;}
   if(event.key==='ArrowLeft')pose.yaw-=12;
   if(event.key==='ArrowRight')pose.yaw+=12;
   if(event.key==='ArrowUp')pose.pitch=clamp(pose.pitch+10,-58,46);
   if(event.key==='ArrowDown')pose.pitch=clamp(pose.pitch-10,-58,46);
   basePitch=pose.pitch-Math.sin(phase*.55)*10-pointer.y*5;
   draw();
  }
  update();
 },options);
 motion.addEventListener('change',update,options);
 document.addEventListener('visibilitychange',update,options);
 const observer=new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;update();});
 observer.observe(viewport);draw();update();
 cleanup=()=>{cancelAnimationFrame(frame);events.abort();observer.disconnect();};
}

import {escapeHTML as e} from './core.js';
import {executionSourceLabel} from './execution-sources.js';

const metrics={cost:{name:'费用',unit:'USD'},time:{name:'平均耗时',unit:'s'}};
const numeric=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0;
const number=value=>value!==0&&(Math.abs(value)<.001||Math.abs(value)>=1e6)?value.toExponential(1):Number(value.toFixed(3)).toLocaleString('en-US',{maximumFractionDigits:3});
const metricText=(row,metric)=>numeric(row[metric])?(metric==='cost'?'$':'')+number(row[metric])+(metric==='time'?' s':''):'未提供';

// Clip the full segment against the label box, including a gap for the stroke.
// Testing only the endpoints misses long lines crossing a label's background.
function lineIntersectsLabel(start,end,box){
 let enter=0,leave=1;
 for(const [axis,size] of [['x','w'],['y','h']]){
  const low=box[axis]-6,high=box[axis]+box[size]+6,delta=end[axis]-start[axis];
  if(Math.abs(delta)<1e-9){if(start[axis]<low||start[axis]>high)return false;}
  else{
   const a=(low-start[axis])/delta,b=(high-start[axis])/delta;
   enter=Math.max(enter,Math.min(a,b));leave=Math.min(leave,Math.max(a,b));
   if(enter>leave)return false;
  }
 }
 return true;
}

// Minimize resource use and maximize pass rate. Equal coordinates share one
// point; equal-cost, lower-scoring records and equal-score, costlier ones do not
// belong to the frontier. Never coerce null (unreported fees) into zero.
export function chartModel(rows,metric='cost'){
 metric=metrics[metric]?metric:'cost';
 const groups=new Map();
 for(const row of rows){
  if(!numeric(row[metric])||!numeric(row.score)||row.score>100)continue;
  const key=row[metric]+':'+row.score;
  if(!groups.has(key))groups.set(key,{x:row[metric],y:row.score,rows:[],frontier:false});
  groups.get(key).rows.push(row);
 }
 const points=[...groups.values()].sort((a,b)=>a.x-b.x||b.y-a.y);
 let best=-Infinity;
 for(const point of points){point.frontier=point.y>best;best=Math.max(best,point.y);}
 const included=points.reduce((n,p)=>n+p.rows.length,0);
 return {metric,points,frontier:points.filter(p=>p.frontier),included,excluded:rows.length-included,total:rows.length};
}

export function chartAxes(points,metric='cost',zoom=false){
 const maxX=Math.max(0,...points.map(p=>p.x)),minX=points.length?Math.min(...points.map(p=>p.x)):0;
 const extent=Math.max(maxX-minX,maxX*.1,1);
 const start=metric==='time'?Math.max(0,minX-extent*.2):0;
 const end=metric==='time'?maxX+extent*.2:(maxX||1)*1.14;
 const raw=(end-start)/5;
 const power=10**Math.floor(Math.log10(raw));
 const step=[1,2,2.5,5,10].find(n=>n>=raw/power)*power;
 const xMin=Math.floor(start/step)*step,xMax=Math.ceil(end/step)*step;
 const low=Math.min(100,...points.map(p=>p.y)),high=Math.max(0,...points.map(p=>p.y));
 const yMin=zoom&&points.length?Math.max(0,Math.floor((low-5)/5)*5):0;
 const yMax=zoom&&points.length?Math.min(100,Math.max(yMin+10,Math.ceil((high+5)/5)*5)):100;
 const yStep=yMax-yMin>40?20:yMax-yMin>25?10:5;
 const xTicks=Array.from({length:Math.round((xMax-xMin)/step)+1},(_,i)=>xMin+i*step);
 const yTicks=[];for(let n=yMin;n<=yMax;n+=yStep)yTicks.push(n);
 if(yTicks.at(-1)!==yMax)yTicks.push(yMax);
 return {xMin,xMax,yMin,yMax,xTicks,yTicks};
}

export function renderRankChart(rows,metric='cost',{zoom=false}={}){
 const model=chartModel(rows,metric),m=metrics[model.metric];
 return '<section class="rank-chart card" data-rank-chart aria-labelledby="rank-chart-title">'+
  '<div class="rank-chart-heading"><div><h2 id="rank-chart-title">通过率与'+m.name+'</h2><p>向左，更低'+(metric==='cost'?'费用':'耗时')+'。向上，更高通过率。</p></div><div class="rank-chart-controls"><label class="rank-chart-zoom"><input type="checkbox" class="checkbox" data-chart-zoom '+(zoom?'checked':'')+'> 聚焦差异</label><div class="pill-switch" role="group" aria-label="图表横轴">'+['cost','time'].map(key=>'<button type="button" data-chart-metric="'+key+'" aria-pressed="'+(model.metric===key)+'" class="'+(model.metric===key?'active':'')+'">'+metrics[key].name+' <span>'+metrics[key].unit+'</span></button>').join('')+'</div></div></div>'+
  '<div class="rank-chart-meta"><div class="rank-chart-legend" aria-label="图例"><span><i class="rank-legend-frontier" aria-hidden="true"></i>帕累托前沿</span><span><i class="rank-legend-point" aria-hidden="true"></i>其他成绩</span></div><span>'+model.included+' / '+model.total+' 项成绩绘入图表</span></div>'+
  (model.points.length?'<div class="rank-chart-stage" role="group" aria-label="通过率与'+m.name+'散点图，选择数据点查看详情" aria-describedby="rank-chart-help"><canvas aria-hidden="true"></canvas><div class="rank-chart-points"></div><div class="rank-chart-labels" aria-hidden="true"></div></div>':'<div class="rank-chart-empty"><h3>'+(rows.length?'暂无可绘制的'+m.name+'数据':'当前条件下暂无成绩')+'</h3><p>'+(rows.length?'可切换横轴，或在排行榜查看已有成绩。':'调整上方筛选，查看相同条件下的测评结果。')+'</p></div>')+
  '<div class="rank-chart-tooltip" id="rank-chart-tooltip" role="dialog" aria-label="数据点详情" hidden></div>'+
  '<div class="rank-chart-foot"><p><span class="rank-axis-note">'+(zoom?'纵轴已放大 · 聚焦差异':'纵轴范围 0–100%')+'</span>'+(model.excluded?' · '+model.excluded+' 项缺少有效'+m.name+'或通过率，仅在排行榜中展示。':' · 当前筛选下的成绩均已展示。')+'</p><details><summary>如何阅读图表</summary><p id="rank-chart-help">虚线连接当前筛选中的帕累托前沿：不存在另一项成绩，在'+m.name+'不增加时获得更高通过率，或在通过率不降低时使用更少'+(metric==='cost'?'费用':'时间')+'。连线仅辅助比较，不代表未测量的结果。默认纵轴为 0–100%，可切换聚焦差异；点击或用 Tab 聚焦数据点查看详情，Esc 关闭。</p></details></div></section>';
}

export function bindRankChart(root,rows,metric,onMetric,{zoom=false,onZoom=()=>{},onLocate}={}){
 if(!root)return ()=>{};
 root.querySelectorAll('[data-chart-metric]').forEach(button=>button.onclick=()=>onMetric(button.dataset.chartMetric));
 root.querySelector('[data-chart-zoom]').onchange=event=>onZoom(event.target.checked);
 const stage=root.querySelector('.rank-chart-stage');
 if(!stage)return ()=>{};
 const model=chartModel(rows,metric),axes=chartAxes(model.points,metric,zoom),canvas=stage.querySelector('canvas'),ctx=canvas.getContext('2d');
 stage.dataset.yMin=axes.yMin;stage.dataset.yMax=axes.yMax;
 if(!ctx)return ()=>{};
 const pointLayer=stage.querySelector('.rank-chart-points'),labelLayer=stage.querySelector('.rank-chart-labels'),tooltip=root.querySelector('.rank-chart-tooltip');
 const abort=new AbortController(),options={signal:abort.signal};
 let positions=[],active=-1,pinned=false,frame=0,disposed=false;
 const buttons=model.points.map((point,index)=>{
  const button=document.createElement('button');button.type='button';button.className='rank-chart-point';button.dataset.chartPoint=String(index);
  button.setAttribute('aria-label',point.rows.map(r=>r.agent+' / '+r.model).join('；')+'，通过率 '+point.y.toFixed(1)+'%，'+metrics[metric].name+' '+metricText(point.rows[0],metric)+(point.frontier?'，帕累托前沿':'')+'，'+(point.rows.every(r=>r.demo)?'示例成绩':'包含公开成绩'));
  button.onpointerenter=event=>{if(event.pointerType==='mouse'&&!pinned)show(index);};
  button.onfocus=()=>{show(index);};
  button.onclick=()=>{pinned=true;show(index);};
  button.onkeydown=event=>{if(event.key==='Tab'&&!event.shiftKey&&active===index){const action=tooltip.querySelector('[data-chart-locate]');if(action){event.preventDefault();pinned=true;action.focus();}}};
  pointLayer.append(button);return button;
 });
 const labels=model.points.map(point=>{
  const label=document.createElement('div');label.className='rank-chart-label'+(point.frontier?' is-frontier':'');
  label.innerHTML='<strong>'+e(point.rows[0].model)+(point.rows.length>1?' <span>+'+(point.rows.length-1)+'</span>':'')+'</strong><small>'+e(point.rows[0].agent)+'</small>';
  labelLayer.append(label);return label;
 });
 function hide(){active=-1;pinned=false;tooltip.hidden=true;buttons.forEach(b=>{b.classList.remove('is-active');b.setAttribute('aria-expanded','false');b.removeAttribute('aria-describedby');});}
 function show(index){
  active=index;const point=model.points[index];
  buttons.forEach((b,i)=>{b.classList.toggle('is-active',i===index);b.setAttribute('aria-haspopup','dialog');b.setAttribute('aria-expanded',String(i===index));if(i===index)b.setAttribute('aria-describedby',tooltip.id);else b.removeAttribute('aria-describedby');});
  tooltip.innerHTML='<div class="rank-tooltip-status">'+(point.frontier?'帕累托前沿':'测评成绩')+(point.rows.length>1?' · '+point.rows.length+' 项重合':'')+'</div>'+point.rows.map(r=>'<div class="rank-tooltip-record"><strong>'+e(r.agent)+'</strong><p>'+e(r.model)+'</p><dl><div><dt>通过率</dt><dd>'+r.score.toFixed(1)+'%</dd></div><div><dt>费用</dt><dd>'+metricText(r,'cost')+'</dd></div><div><dt>平均耗时</dt><dd>'+metricText(r,'time')+'</dd></div></dl><small>'+e(executionSourceLabel(r.source))+' · '+(r.demo===true?'示例成绩':'公开记录 · 未核验')+'</small>'+(onLocate?'<button class="text-link" data-chart-locate="'+e(r.id)+'">在排行榜中定位</button>':'')+'</div>').join('');
  tooltip.querySelectorAll('[data-chart-locate]').forEach(button=>button.onclick=()=>onLocate(button.dataset.chartLocate));
  tooltip.hidden=false;positionTooltip();
 }
 function positionTooltip(){
  if(active<0)return;
  const point=positions[active];if(!point)return;
  const stageBox=stage.getBoundingClientRect(),rootBox=root.getBoundingClientRect();
  const x=stageBox.left-rootBox.left+point.x,y=stageBox.top-rootBox.top+point.y;
  const left=Math.max(12,Math.min(root.clientWidth-tooltip.offsetWidth-12,x+20));
  // Keep the detail panel inside the plot; it must never cover metric controls.
  const plotTop=stageBox.top-rootBox.top;
  tooltip.style.maxHeight=Math.min(360,stage.clientHeight-24)+'px';
  const preferred=y-tooltip.offsetHeight-20;
  const top=Math.max(plotTop+12,Math.min(plotTop+stage.clientHeight-tooltip.offsetHeight-12,preferred<plotTop?y+20:preferred));
  tooltip.style.left=left+'px';tooltip.style.top=top+'px';
 }
 root.addEventListener('pointerleave',()=>{if(!pinned&&!root.contains(document.activeElement))hide();},options);
 root.addEventListener('focusout',event=>{if(!root.contains(event.relatedTarget))hide();},options);
 root.addEventListener('keydown',event=>{
  if(event.key==='Escape'){const index=active;if(tooltip.contains(document.activeElement)&&index>=0)buttons[index].focus();hide();event.stopPropagation();}
  if(event.key==='Tab'&&tooltip.contains(event.target)){
   const actions=[...tooltip.querySelectorAll('button')],index=active;
   if(event.shiftKey&&event.target===actions[0]){event.preventDefault();buttons[index]?.focus();}
   else if(!event.shiftKey&&event.target===actions.at(-1)){event.preventDefault();pinned=false;const next=buttons[index+1];if(next)next.focus();else{hide();root.querySelector('.rank-chart-foot summary').focus();}}
  }
 },options);
 document.addEventListener('pointerdown',event=>{if(!event.target.closest('[data-chart-point]')&&!tooltip.contains(event.target))hide();},options);
 const intersect=(a,b)=>a.x<b.x+b.w+5&&a.x+a.w+5>b.x&&a.y<b.y+b.h+5&&a.y+a.h+5>b.y;
 function draw(){
  if(disposed)return;
  const w=stage.clientWidth,h=stage.clientHeight;if(!w||!h)return;
  const small=w<520,pad={left:small?43:62,right:small?18:36,top:44,bottom:58};
  const plotW=w-pad.left-pad.right,plotH=h-pad.top-pad.bottom;
  const x=value=>pad.left+(value-axes.xMin)/(axes.xMax-axes.xMin)*plotW,y=value=>pad.top+(axes.yMax-value)/(axes.yMax-axes.yMin)*plotH;
  const dpr=Math.min(window.devicePixelRatio||1,3);canvas.width=Math.round(w*dpr);canvas.height=Math.round(h*dpr);ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,w,h);ctx.font='12px '+getComputedStyle(root).fontFamily;ctx.lineWidth=1;
  // Grid, bounded to the plot. The fill only covers the observed frontier span.
  ctx.strokeStyle='#e9e9ed';
  for(const tick of axes.yTicks){ctx.beginPath();ctx.moveTo(pad.left,y(tick));ctx.lineTo(w-pad.right,y(tick));ctx.stroke();}
  for(const tick of axes.xTicks){ctx.beginPath();ctx.moveTo(x(tick),pad.top);ctx.lineTo(x(tick),h-pad.bottom);ctx.stroke();}
  if(model.frontier.length>1){
   const frontier=model.frontier;
   ctx.beginPath();ctx.moveTo(x(frontier[0].x),h-pad.bottom);for(const p of frontier)ctx.lineTo(x(p.x),y(p.y));ctx.lineTo(x(frontier.at(-1).x),h-pad.bottom);ctx.closePath();ctx.fillStyle='#11111105';ctx.fill();
   ctx.beginPath();for(const [i,p] of frontier.entries())i?ctx.lineTo(x(p.x),y(p.y)):ctx.moveTo(x(p.x),y(p.y));ctx.strokeStyle='#111111';ctx.lineWidth=2.2;ctx.setLineDash([6,6]);ctx.stroke();ctx.setLineDash([]);
  }
  ctx.fillStyle='#6e6e73';ctx.textAlign='right';ctx.textBaseline='middle';
  for(const tick of axes.yTicks)ctx.fillText(tick+'%',pad.left-12,y(tick));
  ctx.textAlign='center';ctx.textBaseline='top';
  for(const tick of axes.xTicks)ctx.fillText(number(tick),x(tick),h-pad.bottom+14);
  ctx.textAlign='left';ctx.fillText('任务通过率 (%)',pad.left,10);
  ctx.textAlign='right';ctx.fillText(metrics[metric].name+' ('+metrics[metric].unit+')',w-pad.right,h-18);
  positions=model.points.map(point=>({x:x(point.x),y:y(point.y)}));
  for(const [i,point] of model.points.entries()){
   const pos=positions[i];ctx.beginPath();ctx.arc(pos.x,pos.y,small?6:7,0,Math.PI*2);ctx.fillStyle=point.frontier?'#111111':'#86868b';ctx.fill();ctx.strokeStyle='#fff';ctx.lineWidth=2.5;ctx.stroke();
   buttons[i].style.left=pos.x+'px';buttons[i].style.top=pos.y+'px';
   let polygon=[[-16,-16],[16,-16],[16,16],[-16,16]];
   for(const [j,other] of positions.entries()){
    if(j===i)continue;
    const dx=other.x-pos.x,dy=other.y-pos.y,limit=(dx*dx+dy*dy)/2;
    const clipped=[];
    for(let k=0;k<polygon.length;k++){
     const a=polygon[k],b=polygon[(k+1)%polygon.length],av=a[0]*dx+a[1]*dy-limit,bv=b[0]*dx+b[1]*dy-limit;
     if(av<=0)clipped.push(a);
     if((av<=0)!==(bv<=0)){const t=av/(av-bv);clipped.push([a[0]+t*(b[0]-a[0]),a[1]+t*(b[1]-a[1])]);}
    }
    polygon=clipped;
   }
   buttons[i].style.clipPath='polygon('+polygon.map(([px,py])=>(px+16)+'px '+(py+16)+'px').join(',')+')';
  }
  // Reserve the entire frontier, not just its points, before placing labels.
  // Prefer nearby open space; dense plots defer excess names to tooltips.
  const occupied=[],pointRects=positions.map(p=>({x:p.x-9,y:p.y-9,w:18,h:18}));
  const frontier=model.points.flatMap((point,i)=>point.frontier?[positions[i]]:[]);
  const segments=frontier.slice(1).map((end,i)=>({start:frontier[i],end}));
  const order=model.points.map((p,i)=>i).sort((a,b)=>model.points[b].y-model.points[a].y||Number(model.points[b].frontier)-Number(model.points[a].frontier));
  for(const i of order){
   const label=labels[i],p=positions[i];label.hidden=false;
   const lw=label.offsetWidth,lh=label.offsetHeight;
   const candidates=[[p.x+15,p.y-lh-8],[p.x-lw-15,p.y-lh-8],[p.x+15,p.y+10],[p.x-lw-15,p.y+10],[p.x-lw/2,p.y-lh-18],[p.x-lw/2,p.y+18],[p.x-lw/2,p.y+38],[p.x-lw/2,p.y-lh-38],[p.x-lw/2,p.y+58]];
   const available=box=>box.y>=pad.top-16&&box.y+box.h<=h-pad.bottom-6&&!occupied.some(other=>intersect(box,other))&&!pointRects.some(other=>intersect(box,other))&&!segments.some(({start,end})=>lineIntersectsLabel(start,end,box));
   let place=candidates.map(([lx,ly])=>({x:Math.max(pad.left,Math.min(w-pad.right-lw,lx)),y:ly,w:lw,h:lh})).find(available);
   if(!place&&occupied.length<20){
    const spaces=[];
    for(let ly=pad.top;ly<h-pad.bottom-lh-6;ly+=12)for(let lx=pad.left;lx<=w-pad.right-lw;lx+=12){
     const box={x:lx,y:ly,w:lw,h:lh};
     if(available(box))spaces.push({...box,distance:Math.hypot(lx+lw/2-p.x,ly+lh/2-p.y)});
    }
    place=spaces.sort((a,b)=>a.distance-b.distance)[0];
   }
   if(place){
    label.style.left=place.x+'px';label.style.top=place.y+'px';occupied.push(place);
    const nearX=Math.max(place.x,Math.min(place.x+place.w,p.x)),nearY=Math.max(place.y,Math.min(place.y+place.h,p.y));
    const distance=Math.hypot(nearX-p.x,nearY-p.y);
    if(distance>24){ctx.beginPath();ctx.moveTo(p.x+(nearX-p.x)*9/distance,p.y+(nearY-p.y)*9/distance);ctx.lineTo(nearX,nearY);ctx.lineWidth=1;ctx.strokeStyle='#a1a1a6';ctx.stroke();}
   }else label.hidden=true;
  }
  positionTooltip();
 }
 const observer=new ResizeObserver(()=>{cancelAnimationFrame(frame);frame=requestAnimationFrame(draw);});observer.observe(stage);draw();
 return ()=>{disposed=true;cancelAnimationFrame(frame);observer.disconnect();abort.abort();};
}

// Run in the browser. Sample the rendered frontier independently of the
// placement algorithm so a label's opaque background cannot hide the line.
export function labelsClearOfFrontier(){
 const labels=[...document.querySelectorAll('.rank-chart-label')].filter(el=>!el.hidden).map(el=>el.getBoundingClientRect());
 const frontier=[...document.querySelectorAll('[data-chart-point]')]
  .filter(el=>el.getAttribute('aria-label').includes('，帕累托前沿'))
  .map(el=>{const b=el.getBoundingClientRect();return {x:b.left+b.width/2,y:b.top+b.height/2};})
  .sort((a,b)=>a.x-b.x);
 for(let i=1;i<frontier.length;i++){
  const a=frontier[i-1],b=frontier[i],steps=Math.ceil(Math.hypot(b.x-a.x,b.y-a.y));
  for(let n=0;n<=steps;n++){
   const t=steps?n/steps:0,x=a.x+(b.x-a.x)*t,y=a.y+(b.y-a.y)*t;
   if(labels.some(r=>x>=r.left-4&&x<=r.right+4&&y>=r.top-4&&y<=r.bottom+4))return false;
  }
 }
 return true;
}

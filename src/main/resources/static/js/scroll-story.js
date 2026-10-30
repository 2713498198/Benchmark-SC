const clamp=(value,min,max)=>Math.min(max,Math.max(min,value));
function prefersReducedMotion(){return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches===true;}
export function bindScrollStory(root){
 if(!root)return ()=>{};
 const scenes=[...root.querySelectorAll('[data-story-scene]')];
 if(!scenes.length)return ()=>{};
 let frame=0,disposed=false;const reduce=prefersReducedMotion();
 const paperStage=scenes[0].querySelector('.story-score-visual');
 const paperCard=paperStage?.querySelector('.story-paper');
 function setActive(index){const safe=clamp(index,0,scenes.length-1);root.dataset.storyActive=String(safe+1);root.dataset.storyIndex=String(safe);scenes.forEach((scene,i)=>{scene.classList.toggle('is-active',i===safe);scene.dataset.storyIndex=String(i+1);});}
 function updatePaper(){
  if(!paperStage||!paperCard)return;
  const progress=clamp((innerHeight*1.05-paperStage.getBoundingClientRect().top)/(innerHeight*.8),0,1);
  const open=progress*progress*(3-2*progress);
  const drift=Math.sin(Math.PI*open);
  paperCard.style.setProperty('--paper-angle',`${(-32*(1-open)).toFixed(2)}deg`);
  paperCard.style.setProperty('--paper-rise',`${(24*(1-open)-11*drift).toFixed(2)}px`);
  paperCard.style.setProperty('--paper-x',`${(-12*(1-open)+7*drift).toFixed(2)}px`);
  paperCard.style.setProperty('--paper-pitch',`${(5*(1-open)+2*drift).toFixed(2)}deg`);
  paperCard.style.setProperty('--paper-tilt',`${(-3*(1-open)+1.4*drift).toFixed(2)}deg`);
  paperCard.style.setProperty('--paper-shade',`${(1-open).toFixed(3)}`);
 }
 function update(){frame=0;if(disposed||reduce)return;updatePaper();const center=innerHeight*.52;let nearest=0,distance=Infinity;scenes.forEach((scene,i)=>{const b=scene.getBoundingClientRect(),d=Math.abs(b.top+b.height/2-center);if(d<distance){distance=d;nearest=i;}});setActive(nearest);}
 function onScroll(){if(reduce||frame)return;frame=requestAnimationFrame(update);}
 const observer=reduce?null:new IntersectionObserver(entries=>entries.forEach(entry=>entry.target.classList.toggle('is-visible',entry.isIntersecting)),{rootMargin:'-12% 0px -12% 0px',threshold:.08});
 scenes.forEach(scene=>observer?.observe(scene));
 if(reduce){root.dataset.storyMotion='reduced';scenes.forEach(scene=>scene.classList.add('is-visible'));}else{root.dataset.storyMotion='full';addEventListener('scroll',onScroll,{passive:true});addEventListener('resize',onScroll,{passive:true});update();}
 setActive(0);
 return ()=>{disposed=true;observer?.disconnect();if(frame)cancelAnimationFrame(frame);removeEventListener('scroll',onScroll);removeEventListener('resize',onScroll);};
}

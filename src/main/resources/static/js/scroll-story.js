const clamp=(value,min,max)=>Math.min(max,Math.max(min,value));
function prefersReducedMotion(){return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches===true;}
export function bindScrollStory(root){
 if(!root)return ()=>{};
 const scenes=[...root.querySelectorAll('[data-story-scene]')];
 if(!scenes.length)return ()=>{};
 let frame=0,disposed=false;const reduce=prefersReducedMotion();
 function setActive(index){const safe=clamp(index,0,scenes.length-1);root.dataset.storyActive=String(safe+1);root.dataset.storyIndex=String(safe);root.querySelector('[data-story-count]')?.replaceChildren(String(safe+1).padStart(2,'0'));scenes.forEach((scene,i)=>{scene.classList.toggle('is-active',i===safe);scene.dataset.storyIndex=String(i+1);});}
 function update(){frame=0;if(disposed||reduce)return;const rect=root.getBoundingClientRect(),travel=Math.max(1,rect.height-innerHeight);root.style.setProperty('--story-progress',clamp(-rect.top/travel,0,1).toFixed(3));const center=innerHeight*.52;let nearest=0,distance=Infinity;scenes.forEach((scene,i)=>{const b=scene.getBoundingClientRect(),d=Math.abs(b.top+b.height/2-center);if(d<distance){distance=d;nearest=i;}});setActive(nearest);}
 function onScroll(){if(reduce||frame)return;frame=requestAnimationFrame(update);}
 const observer=reduce?null:new IntersectionObserver(entries=>entries.forEach(entry=>entry.target.classList.toggle('is-visible',entry.isIntersecting)),{rootMargin:'-12% 0px -12% 0px',threshold:.08});
 scenes.forEach(scene=>observer?.observe(scene));
 if(reduce){root.dataset.storyMotion='reduced';scenes.forEach(scene=>scene.classList.add('is-visible'));}else{root.dataset.storyMotion='full';addEventListener('scroll',onScroll,{passive:true});addEventListener('resize',onScroll,{passive:true});update();}
 setActive(0);
 return ()=>{disposed=true;observer?.disconnect();if(frame)cancelAnimationFrame(frame);removeEventListener('scroll',onScroll);removeEventListener('resize',onScroll);};
}

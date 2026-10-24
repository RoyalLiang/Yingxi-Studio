/* finesse · brand:yingxi · layout:product-stage · palette:yingxi-red · type:noto-sans-sc · engine:scroll-focus */
/* Yingxi: Apple-inspired galleries, scroll focus and native dialog detail. */
const reduceMotion=window.matchMedia('(prefers-reduced-motion: reduce)');
const gallery=document.querySelector('#highlight-gallery');
if(gallery){
  const prev=document.querySelector('[data-gallery-prev]');
  const next=document.querySelector('[data-gallery-next]');
  const update=()=>{prev.disabled=gallery.scrollLeft<8;next.disabled=gallery.scrollLeft+gallery.clientWidth>=gallery.scrollWidth-8;};
  const step=direction=>{const card=gallery.querySelector('.highlight-card');gallery.scrollBy({left:direction*(card.getBoundingClientRect().width+parseFloat(getComputedStyle(gallery).gap)),behavior:reduceMotion.matches?'instant':'smooth'});};
  prev.addEventListener('click',()=>step(-1));next.addEventListener('click',()=>step(1));
  gallery.addEventListener('scroll',update,{passive:true});window.addEventListener('resize',update);update();
}
let lastTrigger=null;
function openDialog(dialog,trigger){if(!dialog)return;lastTrigger=trigger;dialog.showModal();document.body.classList.add('modal-open');dialog.querySelector('[data-close-dialog]')?.focus();}
for(const button of document.querySelectorAll('[data-open-dialog]'))button.addEventListener('click',()=>openDialog(document.getElementById(button.dataset.openDialog),button));
for(const dialog of document.querySelectorAll('dialog')){
  dialog.querySelector('[data-close-dialog]')?.addEventListener('click',()=>dialog.close());
  dialog.addEventListener('click',event=>{if(event.target===dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)dialog.close();}});
  dialog.addEventListener('close',()=>{document.body.classList.remove('modal-open');lastTrigger?.focus({preventScroll:true});});
}
if(document.querySelector('[data-open-resident]'))fetch('assets/letters.json').then(response=>{if(!response.ok)throw Error('letters unavailable');return response.json();}).then(letters=>{
  for(const button of document.querySelectorAll('[data-open-resident]'))button.addEventListener('click',()=>{
    const key=button.dataset.openResident;const letter=letters[key];if(!letter)return;
    document.querySelector('#resident-dialog-title').textContent=`${letter.name}的短笺`;
    document.querySelector('#resident-dialog-role').textContent=letter.role;
    document.querySelector('#resident-dialog-text').textContent=letter.letter;
    document.querySelector('#resident-dialog-profile').href=`world/#resident-${key}`;
    const img=document.querySelector('#resident-dialog-image');img.src=`assets/${key}.webp`;img.alt=letter.name;
    openDialog(document.querySelector('#resident-dialog'),button);
  });
}).catch(()=>{for(const button of document.querySelectorAll('[data-open-resident]'))button.addEventListener('click',()=>{const fallback=document.querySelector('#all-letters');fallback.open=true;fallback.scrollIntoView({behavior:reduceMotion.matches?'instant':'smooth',block:'center'});});});
const entranceNodes=document.querySelectorAll('.section-top,.highlight-card,.library-section>h2,.library-layout,.world-intro,.era-story article,.world-map-copy,.world-map figure,.resident-card,.world-after p,.book-era article,.book-place,.book-life,.book-resident,.book-section>h2,.download-section>h2,.faq-section>h2');
if('IntersectionObserver'in window){const observer=new IntersectionObserver(entries=>{for(const entry of entries){if(entry.isIntersecting){if(!reduceMotion.matches)entry.target.classList.add('is-entering');observer.unobserve(entry.target);}}},{threshold:.16});entranceNodes.forEach(node=>observer.observe(node));}
const hero=document.querySelector('.hero');const stage=document.querySelector('.product-stage');const pure=document.querySelector('.pure-story');let queued=false;
const clamp=x=>Math.max(0,Math.min(1,x));
function updateScroll(){queued=false;if(reduceMotion.matches)return;const h=window.innerHeight;if(hero&&stage){const r=hero.getBoundingClientRect();if(r.bottom>0&&r.top<h)stage.style.setProperty('--hero-p',clamp(-r.top/(r.height*.7)).toFixed(3));}if(pure){const r=pure.getBoundingClientRect();if(r.bottom>0&&r.top<h)pure.style.setProperty('--pure-p',clamp((h*.55-r.top)/(r.height*.65)).toFixed(3));}}
function requestScroll(){if(!queued){queued=true;requestAnimationFrame(updateScroll);}}
window.addEventListener('scroll',requestScroll,{passive:true});window.addEventListener('resize',requestScroll);reduceMotion.addEventListener('change',requestScroll);updateScroll();

const demo=document.querySelector('.watch-card video');const demoToggle=document.querySelector('[data-video-toggle]');
if(demo&&demoToggle){
  let userPaused=false;let visible=false;demoToggle.hidden=false;
  const syncVideo=()=>{const label=demo.paused?'播放演示':'暂停演示';demoToggle.textContent=label;demoToggle.setAttribute('aria-label',label);};
  demo.addEventListener('play',syncVideo);demo.addEventListener('pause',syncVideo);syncVideo();
  demoToggle.addEventListener('click',()=>{if(demo.paused){userPaused=false;demo.play().catch(syncVideo);}else{userPaused=true;demo.pause();}});
  const syncVisibility=()=>{if(visible&&!userPaused&&!reduceMotion.matches&&!document.hidden)demo.play().catch(syncVideo);else demo.pause();};
  if('IntersectionObserver'in window)new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;syncVisibility();},{threshold:.35}).observe(demo);
  document.addEventListener('visibilitychange',syncVisibility);reduceMotion.addEventListener('change',syncVisibility);
}

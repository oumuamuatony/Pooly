(function(){
 var stableViewport=null;
 function resize(){
  var phone=/phone\.html$/.test(location.pathname),w=window.innerWidth,h=window.innerHeight;
  var panel=document.documentElement.dataset.navigationPanel;
  var active=document.activeElement,editing=active&&active.matches('input,textarea,[contenteditable="true"]');
  if(panel==='create'&&editing&&stableViewport&&Math.abs(w-stableViewport.w)<4&&h<stableViewport.h){h=stableViewport.h;}
  else stableViewport={w:w,h:h};
  var size={create:[420,680],moving:[497,175],pickup:[497,246]}[panel];
  var scale=size?Math.min(w/size[0],h/size[1]):phone?Math.min(w/390,1.5):Math.min(w/1194,h/834);
  scale=Math.max(.45,scale);
  document.documentElement.style.setProperty('--frame-width',(w/scale)+'px');
  document.documentElement.style.setProperty('--frame-height',(h/scale)+'px');
  var root=document.getElementById('dc-root');if(root){root.style.zoom=scale;root.style.width=(w/scale)+'px';root.style.height=(h/scale)+'px';}
  if(window.TogetherMaps)window.TogetherMaps.resize();
 }
 var progressVersion=0;
 function progress(){var version=++progressVersion,deadline=performance.now()+7000;function frame(){if(version!==progressVersion||document.documentElement.dataset.navigationPanel!=='moving')return;var remaining=Math.max(0,(deadline-performance.now())/7000),bar=document.querySelector('.notice-progress i');if(bar){bar.style.transform='scaleX('+remaining+')';bar.parentNode.setAttribute('aria-valuenow',String(Math.ceil(remaining*7)));}if(remaining>0)requestAnimationFrame(frame);}requestAnimationFrame(frame);}
 window.TogetherPanelMode=function(mode){if(mode)document.documentElement.dataset.navigationPanel=mode;else delete document.documentElement.dataset.navigationPanel;resize();if(mode==='moving')progress();else progressVersion++;};
 window.addEventListener('resize',resize);document.addEventListener('DOMContentLoaded',function(){resize();new MutationObserver(function(){if(document.getElementById('dc-root'))resize();}).observe(document.body,{childList:true});});
})();

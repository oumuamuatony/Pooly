/* Places are displayed by Pleos Navigation. No external web map is loaded. */
(function(){
 'use strict';var T=window.Pooly,scheduled=false;
 function open(){T.native('openNavigation').catch(function(e){window.dispatchEvent(new CustomEvent('pooly:error',{detail:e.message}));});}
 function update(){scheduled=false;document.querySelectorAll('.mapbox,.mini[data-appointment-id],.ph .map').forEach(function(el){
  if(el.querySelector('.native-map-placeholder'))return;
  var hint=document.createElement('div');hint.className='native-map-placeholder';
  var label=document.createElement('p');label.textContent='위치는 차량 내장 내비에서 확인해요';hint.appendChild(label);
  if(T.app.native){var button=document.createElement('button');button.className='btn bs';button.textContent='차량 내비 열기';button.onclick=open;hint.appendChild(button);}
  el.appendChild(hint);
 });}
 function schedule(){if(scheduled)return;scheduled=true;setTimeout(update,80);}
 document.addEventListener('DOMContentLoaded',function(){new MutationObserver(schedule).observe(document.body,{childList:true,subtree:true});schedule();});T.on(schedule);
 window.PoolyMaps={context:schedule,resize:schedule,fit:open,locate:open};
})();

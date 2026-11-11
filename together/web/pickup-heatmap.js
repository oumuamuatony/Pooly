/* Synthetic aggregates for the phone prototype. No real user/vehicle locations. */
(function(){
 'use strict';
 const regions={seongsu:{name:'성수',center:[127.0525,37.546],zoom:14.1},konkuk:{name:'건대입구',center:[127.0692,37.5415],zoom:14.2},wangsimni:{name:'왕십리',center:[127.0372,37.5613],zoom:14.4}};
 const spots=[
  ['성수역 인근',127.0561,37.5445,27,41,'seongsu'],['성수역 북쪽',127.0556,37.5455,13,24,'seongsu'],
  ['성수동 연무장길',127.0553,37.5429,6,19,'seongsu'],['뚝섬역 인근',127.0474,37.5472,32,23,'seongsu'],
  ['서울숲역 인근',127.0442,37.5436,18,26,'seongsu'],['서울숲 동쪽',127.0401,37.5445,5,17,'seongsu'],
  ['성수동 북쪽',127.0524,37.5495,8,10,'seongsu'],['건대입구역 서쪽',127.0685,37.5407,24,39,'konkuk'],
  ['건대입구역 동쪽',127.0712,37.5402,21,34,'konkuk'],['건국대학교 인근',127.0731,37.5424,12,28,'konkuk'],
  ['커먼그라운드 인근',127.0659,37.5413,7,21,'konkuk'],['화양동 인근',127.0711,37.5454,10,15,'konkuk'],
  ['왕십리역 남쪽',127.0378,37.5603,31,28,'wangsimni'],['왕십리역 북쪽',127.0368,37.5622,19,24,'wangsimni'],
  ['행당동 인근',127.0354,37.5592,11,16,'wangsimni'],['상왕십리역 인근',127.0294,37.5643,18,20,'wangsimni']
 ];
 function rows(period='all',region=null){return spots.filter(s=>!region||s[5]===region).map(s=>({name:s[0],lon:s[1],lat:s[2],count:period==='morning'?s[3]:period==='evening'?s[4]:s[3]+s[4]}));}
 function data(period='all',region=null){return {type:'FeatureCollection',features:rows(period,region).map(p=>({type:'Feature',properties:{name:p.name,count:p.count},geometry:{type:'Point',coordinates:[p.lon,p.lat]}}))};}
 function completed(appointments){
  const points=new Map();
  for(const a of appointments)for(const member of a.members||[]){
   const p=member.pickup;
   if(!member.boarded_at||!p||!Number.isFinite(p.lat)||!Number.isFinite(p.lon))continue;
   const key=p.lat.toFixed(4)+','+p.lon.toFixed(4);
   points.set(key,{name:p.name,lat:p.lat,lon:p.lon,historical:true});
  }
  return [...points.values()];
 }
 function add(map,period='all',region=null){
  if(map.getSource('pickup-demo')){map.getSource('pickup-demo').setData(data(period,region));return;}
  map.addSource('pickup-demo',{type:'geojson',data:data(period,region)});
  map.addLayer({id:'pickup-demo-heat',type:'heatmap',source:'pickup-demo',paint:{
   'heatmap-weight':['interpolate',['linear'],['get','count'],0,0,15,.65,70,1.25],
   'heatmap-intensity':['interpolate',['linear'],['zoom'],11,2,15,3.4],
   'heatmap-radius':['interpolate',['linear'],['zoom'],11,26,14,49,18,82],
   'heatmap-opacity':.9,
   'heatmap-color':['interpolate',['linear'],['heatmap-density'],0,'rgba(0,91,172,0)',.10,'rgba(30,118,205,.33)',.28,'#36a9db',.48,'#50c9b2',.64,'#f9d75d',.82,'#ff8c43',1,'#ea453e']
  }});
 }
 function pin(p){
  const button=document.createElement('button');
  button.type='button';
  button.className='history-pin'+(p.historical?' own-pin':'');
  button.setAttribute('aria-label',p.name+(p.historical?' · 이전 픽업':' · 예시 픽업'));
  return button;
 }
 function addPins(map,points){
  for(const p of points){
   const element=pin(p),popup=new maplibregl.Popup({offset:23,closeButton:false}).setText(p.name);
   new maplibregl.Marker({element,anchor:'bottom'}).setLngLat([p.lon,p.lat]).setPopup(popup).addTo(map);
  }
 }
 function fallback(host,region,points){
  host.replaceChildren();
  const surface=document.createElement('div');
  surface.className='heatmap-fallback';
  const label=document.createElement('span');
  label.className='heatmap-fallback-label';
  label.textContent=region.name+' · 개략도';
  surface.append(label);
  const caption=document.createElement('output');
  caption.className='heatmap-pin-caption';
  caption.hidden=true;
  surface.append(caption);
  for(const p of points){
   const x=50+(p.lon-region.center[0])/.012*38,y=50-(p.lat-region.center[1])/.008*37;
   if(x<4||x>96||y<8||y>94)continue;
   const wrap=document.createElement('div');
   wrap.className='heatmap-fallback-point';
   wrap.style.left=x+'%';wrap.style.top=y+'%';
   if(!p.historical){
    const halo=document.createElement('span');
    halo.className='heatmap-halo';
    halo.style.setProperty('--halo-size',(42+Math.min(p.count,70))+'px');
    halo.style.setProperty('--halo-core',p.count>=45?'#fa5a3c':p.count>=26?'#f4bd3c':'#43b4d8');
    wrap.append(halo);
   }
   const marker=pin(p);
   marker.onclick=()=>{caption.textContent=p.name;caption.hidden=false;};
   wrap.append(marker);
   surface.append(wrap);
  }
  host.append(surface);
 }
 window.PoolyHeatmap={regions,rows,add,completed,addPins,fallback};
})();

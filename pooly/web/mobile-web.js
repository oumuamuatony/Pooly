(function(){
 'use strict';
 const $=id=>document.getElementById(id),query=new URLSearchParams(location.search),demoMode=query.get('demo')==='1',live=demoMode||!!(query.get('invite')||query.has('live'));
 const compactWeb=demoMode||!live;
 const esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const icons={home:'<path d="m3 10 8-7 8 7v10h-6v-6H9v6H3z"/>',route:'<circle cx="6" cy="5" r="2"/><circle cx="16" cy="18" r="2"/><path d="M6 7v4c0 4 10 0 10 5"/>',bell:'<path d="M5 9a6 6 0 0 1 12 0c0 7 3 7 3 8H2c0-1 3-1 3-8"/><path d="M9 21h4"/>',pin:'<path d="M18 9c0 5-7 11-7 11S4 14 4 9a7 7 0 0 1 14 0Z"/><circle cx="11" cy="9" r="2"/>',clock:'<circle cx="11" cy="11" r="9"/><path d="M11 5v6l4 2"/>',back:'<path d="m14 4-7 7 7 7"/>',arrow:'<path d="m8 5 6 6-6 6"/>',car:'<path d="m4 8 2-5h10l2 5M3 8h16v8H3zM5 16v3m12-3v3M6 11h1m8 0h1"/>',walk:'<circle cx="13" cy="3" r="2"/><path d="m7 10 4-4 4 5h4m-9-2-2 6-4 5m7-8 4 5v4"/>',check:'<path d="m4 11 5 5L19 6"/>',search:'<circle cx="9" cy="9" r="6"/><path d="m14 14 6 6"/>',locate:'<circle cx="11" cy="11" r="6"/><path d="M11 1v4m0 12v4M1 11h4m12 0h4"/>',people:'<circle cx="8" cy="7" r="3"/><path d="M2 20v-3a6 6 0 0 1 12 0v3m1-16a3 3 0 0 1 0 6m3 10v-3a6 6 0 0 0-2-5"/>',info:'<circle cx="11" cy="11" r="9"/><path d="M11 10v6m0-10v.1"/>',message:'<path d="M3 3h16v12H9l-6 4z"/><path d="M7 7h8M7 11h5"/>',share:'<path d="M11 15V2m-4 4 4-4 4 4M5 11H3v9h16v-9h-2"/>'};
 const icon=n=>'<svg viewBox="0 0 22 22" aria-hidden="true">'+(icons[n]||icons.pin)+'</svg>';
 const photo=(name,cls='person-photo')=>{const id={max:'max',jeff:'jeff',kelly:'kelly'}[String(name).toLowerCase()];return id?'<img class="'+cls+'" src="assets/profiles/'+id+'.png" alt="'+esc(name)+'">':'<span class="'+cls+' initials-avatar" aria-label="'+esc(name)+'">'+esc(String(name||'?').slice(0,1))+'</span>';};
 const places=[{id:'s3',name:'성수역 3번 출구',address:'서울 성동구 아차산로 100',lat:37.5445,lon:127.056},{id:'t8',name:'뚝섬역 8번 출구',address:'서울 성동구 아차산로 18',lat:37.5472,lon:127.0474},{id:'forest',name:'서울숲',address:'서울 성동구 뚝섬로 273',lat:37.5444,lon:127.0374},{id:'ws',name:'왕십리역',address:'서울 성동구 왕십리로',lat:37.5615,lon:127.0377}];
 const state={screen:'home',role:'rider',stage:'idle',joined:false,place:null,mode:'pickup',drives:false,ready:'preparing',share:false,boardConfirm:false,notes:'',tab:'home',heatPeriod:'all',heatRegion:'seongsu',pickerHeat:true,picker:null,error:'',notices:[],loading:false};
 let demoFriends=[],demoFriend='',token=demoMode?'':localStorage.getItem('pooly.web.token')||'',seatProfile={enabled:false,cold_heat:1,hot_ventilation:1,vehicle_memories:{}},appointment=null,appointments=[],invitation=null,serverPlaces=[...places],notifications=[],map=null,marker=null,driverMarker=null,watch=null,lastUpload=0,toastTimer,liveSignature='',sheetFocus=null;
 const today=new Date();today.setDate(today.getDate()+1);today.setHours(14,0,0,0);
 const demoTrip={name:'서울숲에서 만나요',meeting_at:today.toISOString(),destination:places[2],driver:{name:'Jeff'},members:[{id:'kelly',name:'Kelly',mode:'pickup',status:'pending'},{id:'max',name:'Max',mode:'pickup',status:'accepted',pickup:places[1]}]};
 function trip(){return appointment||demoTrip;}
 function me(){if(!live)return {...demoTrip.members[0],pickup:state.place,mode:state.mode,status:state.joined?'accepted':'pending',ready:state.ready,boarded_at:['boarded','finished'].includes(state.stage)?'demo':null};if(appointment)return appointment.me_id==='owner'?appointment.owner:appointment.members.find(x=>x.id===appointment.me_id);return invitation?.member||null;}
 function driver(){if(!live)return demoTrip.driver;if(appointment)return [appointment.owner,...appointment.members].find(x=>x.user_id===appointment.driver_id)||appointment.owner;return {name:invitation?.inviter||'운전자'};}
 function phase(){return live?appointment?.trip.phase||'idle':state.stage;}
 function isDriver(){return live?!!appointment?.is_driver:state.role==='driver';}
 function joined(){return live?!!appointment:state.joined;}
 function place(){return me()?.pickup||state.place;}
 function canEdit(){return !isPast({...trip(),trip:{phase:phase()}})&&!isDriver()&&(state.screen==='invite'?state.mode:(me()?.mode||state.mode))==='pickup'&&!me()?.boarded_at&&!['finished','cancelled'].includes(phase());}
 function date(value){return new Date(value).toLocaleDateString('ko-KR',{month:'long',day:'numeric',weekday:'short',timeZone:'Asia/Seoul'});}
 function time(value){return new Date(value).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit',hour12:false,timeZone:'Asia/Seoul'});}
 function toast(text){const el=$('toast');el.textContent=text;el.hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>{el.hidden=true;},3300);}
 function log(title,body){state.notices.unshift({title,body,at:new Date().toISOString()});persist();}
 function persist(){if(!live)localStorage.setItem('pooly.web.preview.v1',JSON.stringify({joined:state.joined,place:state.place,mode:state.mode,drives:state.drives,ready:state.ready,share:state.share,boardConfirm:state.boardConfirm,notes:state.notes,stage:state.stage,notices:state.notices.slice(0,15)}));}
 function go(screen){if(screen==='home'&&live)selectAppointment(appointments.find(a=>!isPast(a))||null);state.screen=screen;state.picker=null;state.error='';render();$('screen').focus({preventScroll:true});window.scrollTo({top:0,behavior:'instant'});}
 function button(label,action,cls='primary',attrs=''){return '<button class="btn '+cls+'" data-action="'+action+'" '+attrs+'>'+label+'</button>';}
 function pageTop(title,back='home',side=''){return '<div class="page-top"><button class="back-button" data-action="'+back+'" aria-label="뒤로">'+icon('back')+'</button><h1>'+title+'</h1>'+side+'</div>';}
 function sharedMeta(){const t=trip();return '<div class="info-line">'+icon('pin')+'<b>'+esc(t.destination?.name||invitation?.destination?.name||'약속 장소')+'</b></div><div class="info-line">'+icon('clock')+'<b>'+date(t.meeting_at)+' · '+time(t.meeting_at)+'</b></div>';}
 function currentTrip(){return live&&invitation&&!appointment?{...invitation,driver:driver()}:trip();}
 function isPast(a){return ['finished','cancelled'].includes(a.trip?.phase)||((a.trip?.phase||'idle')==='idle'&&new Date(a.meeting_at).getTime()<Date.now());}
 function appointmentList(past){const list=live?appointments:[{...demoTrip,id:'demo',trip:{phase:state.stage}}];return list.filter(a=>isPast(a)===past).sort((a,b)=>past?new Date(b.meeting_at)-new Date(a.meeting_at):new Date(a.meeting_at)-new Date(b.meeting_at));}
 function eventCard(t,past=false){const d=live?[t.owner,...(t.members||[])].find(m=>m?.user_id===t.driver_id)||t.owner:demoTrip.driver;return '<article class="trip-card event-card"><div class="trip-card-top">'+(past?'<span class="tag neutral">'+(t.trip?.phase==='cancelled'?'취소된 일정':'지난 일정')+'</span>':'')+'<h2>'+esc(t.destination?.name||'약속 장소')+'</h2><p class="trip-meta">'+icon('clock')+date(t.meeting_at)+' · '+time(t.meeting_at)+'</p></div><div class="driver-row">'+photo(d?.name||invitation?.inviter||'Jeff')+'<div class="driver-info"><strong>'+esc(d?.name||invitation?.inviter||'Jeff')+' 님이 운전해요</strong><small>'+esc(t.name||'')+'</small></div></div><div class="card-bottom">'+button('일정 자세히 보기','open-appointment','primary','data-id="'+esc(t.id||'invitation')+'"')+'</div></article>';}
 function home(){const list=appointmentList(false);if(live&&invitation&&!appointments.some(a=>a.id===invitation.appointment_id))list.push({...invitation,id:'invitation'});return '<div class="list-heading"><h1>일정</h1>'+(!live?'<p class="caption">예시 일정</p>':'')+'</div>'+(list.length?list.map(t=>eventCard(t)).join(''):'<div class="empty-state"><h2>예정된 일정이 없어요.</h2></div>');}
 function history(){const list=appointmentList(true);return '<div class="list-heading"><h1>여정</h1><p class="caption">지난 일정</p></div>'+(list.length?list.map(t=>eventCard(t,true)).join(''):'<div class="empty-state"><h2>아직 지난 일정이 없어요.</h2></div>');}
 function empty(title,copy){return pageTop('약속 확인')+'<div class="empty-state"><div class="hero-avatars" style="justify-content:center">'+photo('Max')+photo('Kelly')+photo('Jeff')+'</div><h2>'+esc(title)+'</h2><p>'+esc(copy)+'</p>'+(!live?button('초대 확인하기','invite'):'')+'</div>';}
 function assignedPickup(){const m=me();return live?!!(m?.pickup_requested??(m?.mode==='pickup')):true;}
 function invite(){const t=currentTrip(),d=driver(),p=place(),pickup=state.mode==='pickup';return pageTop('약속 초대')+'<p class="inviter-label">'+photo(d.name)+' '+esc(d.name)+' 님의 초대</p><h1 class="invitation-title">같이 '+esc(t.destination?.name||'약속 장소')+' 갈래요?</h1><p class="caption subcopy">'+esc(t.name||'함께하는 약속')+'</p><div class="info-card">'+sharedMeta()+'</div><div class="section-heading"><h2>직접 운전해서 오시나요?</h2></div><div class="mode-options driving-options">'+[[true,'car','운전해요'],[false,'people','운전하지 않아요']].map(([value,ic,label])=>'<button class="mode-option '+(state.drives===value?'active':'')+'" data-action="drives" data-drives="'+value+'" aria-pressed="'+(state.drives===value)+'">'+icon(ic)+label+'</button>').join('')+'</div>'+(pickup?'<div class="notice-strip">'+icon('car')+'<p>'+esc(d.name)+' 님이 데리러 와요.<br>내가 탈 위치만 정해 주세요.</p></div><div class="place-card"><div class="place-card-top"><span class="small muted">내 픽업 위치</span><span class="tag neutral">내가 직접 선택</span></div><h3>'+esc(p?.name||'어디서 만날까요?')+'</h3><p>'+esc(p?.address||'정한 위치를 운전자에게 공유해요.')+'</p><div style="margin-top:14px">'+button(p?'픽업 위치 다시 고르기':'픽업 위치 정하기','pick-place','soft')+'</div></div>':'<div class="notice-strip">'+icon(state.drives?'car':'people')+'<p>'+(state.drives?'내 차로 약속 장소에 가요.':'약속 장소에서 만나요. 픽업 대상으로 지정되면 이곳에서 만날 위치를 정할 수 있어요.')+'</p></div>')+'<label class="consent-toggle"><input type="checkbox" id="shareConsent" '+(state.share?'checked':'')+'><span><strong>이번 이동 동안 내 위치 공유</strong><small>출발한 뒤 공유가 시작돼요. 약속 일행에게 공유되며 언제든 끌 수 있어요.</small></span></label><div class="action-area">'+button(joined()?'참가 설정 저장':'이 약속에 함께하기','join','primary',state.loading?'disabled':'')+button('초대 거절하기','decline','',state.loading?'disabled':'')+'</div>'+inlineError();}
 function myPickupActive(){if(!live)return true;const t=appointment?.trip;return t?.active_pickups?.[t.index]===me()?.id;}
 function arrivalInfo(final=false){
  if(!live||!appointment)return null;
  const t=appointment.trip, rider=me();
  if(!t.started_at||['cancelled','finished','parked'].includes(t.phase))return null;
  const pickup=!final&&rider?.mode==='pickup'&&!rider.boarded_at;
  const stop=pickup?rider.id:appointment.parking?'lot':'dest';
  if(pickup&&t.phase==='arrived'&&myPickupActive())return null;
  const eta=t.navigation_etas?.[stop],now=Date.now();
  if(t.navigation?.status==='guiding'&&eta&&now-eta.observed_at<30000){
   const extra=!pickup&&appointment.parking?((appointment.parking.parking_minutes||0)+(appointment.parking.walk_minutes||0))*60000:0;
   return {at:eta.observed_at+eta.seconds*1000+extra,source:'도착 예정'};
  }
  const planned=Date.parse(pickup?t.planned?.pickup_arrivals?.[stop]:t.planned?.arrival_at);
  return Number.isFinite(planned)?{at:planned,source:'출발 시 예상'}:null;
 }
 function arrivalText(info){return info?info.source+' '+time(info.at):'';}
 function stageText(){
  const p=phase(),boarded=!!me()?.boarded_at||p==='boarded',eta=arrivalInfo();
  if(p==='finished')return ['green','여정 완료','함께 도착했어요.',''];
  if(boarded)return ['green','승차 완료','목적지로 이동 중',arrivalText(eta)];
  if(p==='arrived'&&myPickupActive())return ['green','운전자 도착','친구가 도착했어요.',''];
  if(live&&appointment?.trip.navigation?.status==='stopped')return ['neutral','안내 중지','내비 안내가 중지됐어요',arrivalText(eta)];
  if(['driving','near','destination','arrived'].includes(p))return ['','운전자가 오는 중','운전자 위치',live?(arrivalText(eta)||'도착 시간 확인 중'):'약 8분 후 도착해요'];
  return ['','','픽업 위치를 확인하세요',''];
 }
 function pickup(){if(!joined())return invite();if(isDriver())return detail();const m=me(),p=place(),t=currentTrip(),[tag,short,title,copy]=stageText(),ph=phase(),boarded=!!m?.boarded_at||['boarded','finished'].includes(ph),direct=(m?.mode||state.mode)!=='pickup';
 return pageTop('위치 확인하기')+(short?'<span class="tag '+tag+'">'+short+'</span>':'')+'<h1 class="status-heading">'+(direct?'약속 장소에서 만나요.':title)+'</h1>'+(direct||copy?'<p class="eta-line">'+(direct?(arrivalText(arrivalInfo(true))||esc(t.destination.name)+' · '+time(t.meeting_at)+' 약속'):copy)+'</p>':'')+'<div class="map-box pickup-map"><div id="journeyMap" class="map-host"></div><span class="map-status" hidden></span></div>'+(direct?'<div class="info-card">'+sharedMeta()+'</div>':'<div class="pickup-location-row">'+icon('pin')+'<div><h3>'+esc(p?.name||'픽업 위치를 정해 주세요')+'</h3>'+(p?.address?'<p>'+esc(p.address)+'</p>':'')+(p?.description?'<p>'+esc(p.description)+'</p>':'')+'</div>'+(canEdit()?'<button class="text-button" data-action="pick-place">'+(p?'변경':'설정')+'</button>':'')+'</div>')+(ph==='arrived'&&myPickupActive()&&!boarded?'<div class="action-area">'+button(state.boardConfirm?'운전자 확인을 기다리고 있어요':'탑승 확인 보내기','board-confirm','black',state.boardConfirm?'disabled':'')+'</div>':'')+(boarded?'<div class="notice-strip">'+icon('check')+'<p>승차 상태가 확인됐어요. '+esc(t.destination.name)+'까지 함께 이동해요.</p></div>':'')+(compactWeb?'':'<div class="action-area">'+button('빠른 메시지 보내기','message','')+'</div>')+inlineError();}
 function detail(){const t=currentTrip(),members=appointment?appointment.members:live?(invitation?[invitation.member]:[]):demoTrip.members.map(m=>m.id==='kelly'?me():m),my=me(),driverView=isDriver(),past=isPast({...t,trip:{phase:phase()}});
 return pageTop('일정 자세히 보기',isPast({...t,trip:{phase:phase()}})?'history':'home')+'<span class="tag">'+(past?'지난 일정':driverView?'운전자 화면':'나의 약속')+'</span><h1 class="status-heading">'+esc(t.destination?.name||'서울숲')+'</h1><p class="caption subcopy">'+date(t.meeting_at)+' · '+time(t.meeting_at)+(past?' 약속':'에 만나요')+'</p><div class="timeline"><div class="timeline-item"><span class="step-dot">출발</span><div class="timeline-info"><h3>'+esc(driver().name)+' 님 출발</h3><p>'+(phase()==='cancelled'?'취소된 일정':phase()==='idle'?(past?'주행 기록 없음':'출발 준비 중'):'출발했어요')+'</p></div></div>'+members.filter(m=>m.mode==='pickup').map((m,i)=>{const p=m.pickup,own=!driverView&&my?.id===m.id;return '<div class="timeline-item '+(own?'mine':'')+'"><span class="step-dot">'+(i+1)+'</span><div class="timeline-info"><h3>'+esc(m.name)+' 님 픽업 '+(own?'<span class="tag">나</span>':'')+'</h3><p>'+esc(p?.name||'픽업 위치 공유 대기')+'</p>'+(p?'<p>'+esc(p.address||'')+'</p>':'')+(own&&canEdit()?'<button class="text-button" data-action="pick-place">'+(p?'내 픽업 위치 변경':'내 픽업 위치 정하기')+' '+icon('arrow')+'</button>':'')+'</div></div>';}).join('')+'<div class="timeline-item"><span class="step-dot">도착</span><div class="timeline-info"><h3>'+esc(t.destination?.name||'약속 장소')+'<span class="time">'+(arrivalInfo(true)?time(arrivalInfo(true).at):time(t.meeting_at))+'</span></h3><p>'+(arrivalInfo(true)?.source||'약속 시간')+'</p></div></div></div>'+'<div class="section-heading"><h2>함께하는 일행</h2></div><div>'+[driver(),...members].map(m=>'<div class="person-list">'+photo(m.name)+'<div><strong>'+esc(m.name)+'</strong><p>'+esc(m.name===driver().name?'운전':m.mode==='pickup'?'픽업받기':m.mode==='car'?'운전해요':'운전하지 않아요')+'</p></div>'+(!driverView&&m.id===my?.id?'<span class="tag">나</span>':'')+'</div>').join('')+'</div>'+(driverView||isPast({...t,trip:{phase:phase()}})?'':'<div class="action-area">'+button(joined()?'위치 확인하기':'초대 확인하기',joined()?'pickup':'invite')+'</div>');}

 function picker(){const k=state.picker;return pageTop('픽업 위치 변경','picker-back')+'<div class="map-box picker-map"><div id="pickerMap" class="map-host"></div><span class="map-status" hidden></span><button class="current-location" data-action="locate" aria-label="현재 위치 사용">'+icon('locate')+'</button><span class="picker-heat-label">픽업 히트맵 · 예시</span><button class="picker-heat-toggle" data-action="toggle-picker-heat" aria-pressed="'+state.pickerHeat+'">'+(state.pickerHeat?'히트맵 켜짐':'히트맵 꺼짐')+'</button></div><div id="selectedPlace" class="selected-location">'+icon('pin')+'<span>'+esc(k.selected?.name||'지도에서 위치를 선택하세요')+'</span></div><p class="tool-note recommend-note">번호 핀(1~3)은 가상 정차 데이터로 계산한 <b>추천 픽업 위치 예시</b>예요. 실제 승차 기록이 쌓이면 바뀌어요.</p><label class="input-label" for="pickupNotes">상세 위치 <span class="muted">선택</span></label><input id="pickupNotes" class="input" maxlength="120" placeholder="예: 출구 앞, 흰색 표지판 옆" value="'+esc(k.notes)+'"><div class="picker-submit">'+button('이 위치에서 만나요','save-place','primary',k.selected?'':'disabled')+'</div>'+inlineError();}

 function heatmapView(){
  const H=window.PoolyHeatmap,previous=H.completed(appointments);
  const regions={...H.regions};
  if(previous.length)regions.history={name:'내 기록',center:[previous[0].lon,previous[0].lat],zoom:14.4};
  const id=regions[state.heatRegion]?state.heatRegion:'seongsu',region=regions[id],isExample=id!=='history';
  const rows=isExample?H.rows(state.heatPeriod,id):[];
  return {id,region,regions,isExample,rows,points:[...rows,...previous]};
 }
 function heatmap(){
  const view=heatmapView();
  const periods=view.isExample?'<div class="heatmap-period" role="group" aria-label="시간대">'+[['all','전체'],['morning','아침 07–10시'],['evening','저녁 17–21시']].map(([id,label])=>'<button data-action="heat-period" data-value="'+id+'" aria-pressed="'+(state.heatPeriod===id)+'">'+label+'</button>').join('')+'</div>':'';
  return '<div class="list-heading heatmap-heading"><h1>픽업 지도</h1>'+(view.isExample?'<span class="tag heatmap-demo">예시</span>':'')+'</div>'+periods+
   '<div class="heatmap-regions" role="group" aria-label="지역">'+Object.entries(view.regions).map(([id,p])=>'<button data-action="heat-region" data-value="'+id+'" aria-pressed="'+(view.id===id)+'">'+esc(p.name)+'</button>').join('')+'</div>'+
   '<div class="map-box heatmap-map"><div id="heatmapMap" class="map-host" aria-label="'+esc(view.region.name)+' 픽업 지도"></div>'+
   (view.isExample?'<div class="heatmap-legend"><span>적음</span><i></i><span>많음</span></div>':'')+'</div>';
 }
 function mountHeatmap(){
  const H=window.PoolyHeatmap,view=heatmapView(),host=$('heatmapMap');
  let instance=null,loaded=false,timer;
  function useFallback(){
   if(loaded||!host.isConnected||instance&&map!==instance)return;
   clearTimeout(timer);
   if(instance){map=null;instance.remove();}
   H.fallback(host,view.region,view.points);
  }
  if(!window.maplibregl){useFallback();return;}
  try{
   instance=map=new maplibregl.Map({container:host,style:'https://tiles.openfreemap.org/styles/positron',
    center:view.region.center,zoom:view.region.zoom,attributionControl:{compact:true},dragRotate:false,pitchWithRotate:false});
   instance.addControl(new maplibregl.NavigationControl({showCompass:false}),'top-right');
   instance.once('load',()=>{
    if(map!==instance)return;
    try{
     if(view.isExample)H.add(instance,state.heatPeriod,view.id);
     H.addPins(instance,view.points);
     const frame=view.isExample?view.rows:view.points;
     if(frame.length>1){
      const bounds=new maplibregl.LngLatBounds();
      frame.forEach(p=>bounds.extend([p.lon,p.lat]));
      instance.fitBounds(bounds,{padding:{top:44,right:36,bottom:68,left:36},maxZoom:view.region.zoom,duration:0});
     }
     loaded=true;
     clearTimeout(timer);
     window.PoolyWeb.mapLoaded=true;
    }catch{useFallback();}
   });
   timer=setTimeout(useFallback,4500);
  }catch{useFallback();}
 }
 function inlineError(){return state.error?'<p class="inline-error" role="alert">'+esc(state.error)+'</p>':'';}
 function render(keepMap=false){const journeyHost=keepMap&&map?$('journeyMap'):null,journeyBox=journeyHost?.parentElement;if(map&&!journeyHost){map.remove();map=null;marker=null;driverMarker=null;}const screen=$('screen');screen.className='screen'+(state.picker?' search-screen':'');if(state.loading&&live&&!invitation&&!appointment)screen.innerHTML='<div class="loading"><span class="loading-dot"></span><p>일정을 불러오고 있어요.</p></div>';else if(demoMode&&state.error&&!appointment)screen.innerHTML=empty('일정을 불러오지 못했어요',state.error);else if(!demoMode&&state.error&&live&&!invitation&&!appointment)screen.innerHTML=empty('초대를 열 수 없어요',state.error);else if(!demoMode&&live&&state.screen!=='heatmap'&&!invitation&&!appointment&&!appointments.length)screen.innerHTML=empty('초대 링크를 열어 주세요','친구가 공유한 링크를 열면 약속에 참가할 수 있어요.');else screen.innerHTML=state.picker?picker():({home,invite,pickup,detail,history,heatmap}[state.screen]||home)();
 $('bottomNav').hidden=!!state.picker;$('bottomNav').innerHTML=[['home','home','홈'],['heatmap','pin','픽업 지도'],['history','route','여정']].filter(([action])=>!compactWeb||action!=='heatmap').map(([action,ic,label])=>'<button class="nav-button" data-action="'+action+'" '+(state.screen===action?'aria-current="page"':'')+'>'+icon(ic)+'<span>'+label+'</span></button>').join('');
 document.querySelector('.avatar-mini').innerHTML=photo(demoMode?demoFriends.find(f=>f.id===demoFriend)?.name||'':isDriver()?driver().name:me()?.name||'Kelly');$('previewTools').hidden=live||compactWeb;document.querySelectorAll('.role-segment button').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.action===('role-'+state.role))));$('demoStage').value=state.stage;
 if(state.screen==='heatmap'&&!state.picker)mountHeatmap();else if(state.picker)mountMap('pickerMap',state.picker.selected,true);else if($('journeyMap')){const p=place()||trip().destination;if(journeyHost){$('journeyMap').parentElement.replaceWith(journeyBox);map.resize();updateDriverMarker(p);}else mountMap('journeyMap',p,false);}else if(journeyHost){map.remove();map=null;marker=null;driverMarker=null;}
 }
 function mountMap(id,p,interactive){const host=$(id);if(!window.maplibregl||typeof window.maplibregl.Map!=='function'){host.innerHTML='<div class="fallback-map">지도를 표시할 수 없어요.<br>연결을 확인한 뒤 다시 열어 주세요.</div>';return;}
 try{map=new maplibregl.Map({container:host,style:'https://tiles.openfreemap.org/styles/positron',center:[p?.lon||127.0556,p?.lat||37.5445],zoom:14.8,attributionControl:{compact:true},dragRotate:false,pitchWithRotate:false});let errorShown=false;map.on('load',()=>{window.PoolyWeb.mapLoaded=true;const hint=host.parentElement.querySelector('.map-status');if(hint)hint.hidden=true;if(interactive&&state.pickerHeat&&window.PoolyHeatmap)window.PoolyHeatmap.add(map);if(interactive&&window.PoolyHeatmap)window.PoolyHeatmap.addRecommendations(map,p||{lat:37.5445,lon:127.0556},place=>selectPlace(place),c=>token?api('/pickup-recommendations?lat='+c.lat+'&lon='+c.lon+'&hour='+new Date().getHours()):null);});if(interactive)map.addControl(new maplibregl.NavigationControl({showCompass:false}),'top-right');map.on('error',()=>{if(errorShown)return;errorShown=true;const hint=host.parentElement.querySelector('.map-status');if(hint){hint.hidden=false;hint.textContent='지도 연결이 지연되고 있어요';}});if(p)updateMarker(p);if(interactive)map.on('click',e=>selectPlace({id:'pin',name:'선택한 픽업 위치',address:e.lngLat.lat.toFixed(5)+', '+e.lngLat.lng.toFixed(5),lat:e.lngLat.lat,lon:e.lngLat.lng},false));else{
 updateDriverMarker(p);
 }
 }catch(e){host.innerHTML='<div class="fallback-map">지도 연결을 확인해 주세요.<br>연결 후 다시 열어 주세요.</div>';}}
 function updateDriverMarker(p){
  if(!map)return;
  const point=live?driver()?.location:phase()==='idle'?null:{lat:37.5413,lon:127.0635};
  const fresh=point&&Number.isFinite(point.lat)&&Number.isFinite(point.lon)&&(!live||Date.now()-Date.parse(point.updated_at)<45000);
  if(!fresh){driverMarker?.remove();driverMarker=null;return;}
  if(driverMarker){driverMarker.setLngLat([point.lon,point.lat]);return;}
  const el=document.createElement('div');el.className='driver-marker';el.innerHTML=icon('car');
  el.title=point.mock?'시연 차량 위치':'운전자 위치';
  driverMarker=new maplibregl.Marker({element:el}).setLngLat([point.lon,point.lat]).addTo(map);
  if(p)map.fitBounds([[Math.min(p.lon,point.lon)-.002,Math.min(p.lat,point.lat)-.002],[Math.max(p.lon,point.lon)+.002,Math.max(p.lat,point.lat)+.002]],{padding:45,maxZoom:15,duration:0});
  else map.jumpTo({center:[point.lon,point.lat]});
 }
 function updateMarker(p){if(!map)return;if(marker)marker.remove();const el=document.createElement('div');el.className='map-marker';marker=new maplibregl.Marker({element:el,anchor:'bottom'}).setLngLat([p.lon,p.lat]).addTo(map);}
 function selectPlace(p,fly=true){if(!state.picker||!canEdit())return;state.picker.selected={...p};$('selectedPlace').querySelector('span').textContent=p.name;document.querySelector('[data-action="save-place"]').disabled=false;updateMarker(p);if(fly&&map)map.flyTo({center:[p.lon,p.lat],zoom:16,duration:600});}
 function openPicker(){if(!canEdit())return toast('픽업받는 사람만 자신의 픽업 위치를 정할 수 있어요.');state.picker={back:state.screen,selected:place()?{...place()}:null,notes:place()?.description||''};render();}
 async function api(path,method='GET',data,customToken=token){const res=await fetch('/api'+path,{method,headers:{'Content-Type':'application/json',...(customToken?{Authorization:'Bearer '+customToken}:{})},body:data===undefined?undefined:JSON.stringify(data),cache:'no-store'});let body;try{body=await res.json();}catch{throw Error('초대 서비스에 연결할 수 없어요. 링크를 확인해 주세요.');}if(!res.ok){const error=Error(body.error||'요청을 완료하지 못했어요.');error.status=res.status;throw error;}return body;}
 function selectAppointment(a){appointment=a||null;const m=a?me():invitation?.member;if(!m)return;state.role=a?.is_driver?'driver':'rider';state.mode=m.mode||'walk';state.drives=state.mode==='car';state.ready=m.ready||'preparing';state.share=!!m.share;state.boardConfirm=!!m.boarding_confirmed_at;state.place=m.pickup||null;}
 async function refresh(){if(!live||!token)return;const session=token,data=await api('/state');if(session!==token)return;const id=appointment?.id||invitation?.appointment_id;appointments=data.appointments||[];const a=id?appointments.find(x=>x.id===id):appointments.find(x=>!isPast(x))||appointments[0];seatProfile=data.seat_profile||seatProfile;serverPlaces=data.places||serverPlaces;notifications=data.notifications||[];const signature=JSON.stringify([appointments.map(x=>[x.id,x.revision]),notifications[0]?.id,Object.values(a?.trip.navigation_etas||{}).map(eta=>Date.now()-eta.observed_at<30000)]);const oldId=appointment?.id,oldPin=JSON.stringify(place());appointment=a||null;if(a){selectAppointment(a);if(a.trip.phase==='cancelled'){stopLocation();state.error='취소된 약속이에요.';}if(['finished','cancelled'].includes(a.trip.phase))stopLocation();}if(signature!==liveSignature&&!state.picker){liveSignature=signature;render(state.screen==='pickup'&&oldId===appointment?.id&&oldPin===JSON.stringify(place()));}else if(state.screen==='pickup'&&!state.picker)updateDriverMarker(place()||trip().destination);}
 async function savePlace(){if(!state.picker?.selected)return;if(!canEdit())throw Error('픽업받는 사람만 자신의 위치를 설정할 수 있어요.');const p={...state.picker.selected,description:$('pickupNotes').value.trim()};delete p.id;const back=state.picker.back;if(live&&appointment)await api('/appointments/'+appointment.id+'/member','POST',{pickup:p});state.place=p;state.notes=p.description;state.picker=null;if(live&&appointment)await refresh();log('내 픽업 위치를 정했어요',p.name+'에서 만나기로 했어요.');go(back);toast('픽업 위치를 저장했어요.');}
 async function join(){if(isDriver())throw Error('운전자는 동승자의 픽업 위치를 지정할 수 없어요.');if(state.mode==='pickup'&&!place())throw Error('내 픽업 위치를 먼저 정해 주세요.');state.share=!!$('shareConsent')?.checked;state.loading=true;
 try{if(live){const data={drives:state.drives,mode:state.mode,pickup:state.mode==='pickup'?place():null,share:state.share};if(!token){const session=await api('/sessions','POST',{name:invitation?.member.name||'참가자'},'');token=session.token;localStorage.setItem('pooly.web.token',token);}if(appointment)await api('/appointments/'+appointment.id+'/member','POST',data);else await api('/invites/'+encodeURIComponent(query.get('invite'))+'/join','POST',data);await refresh();}else{state.joined=true;if(state.mode!=='pickup')state.place=null;demoTrip.members[0].status='accepted';log('약속에 함께하기로 했어요',state.mode==='pickup'?'픽업 위치가 '+driver().name+' 님에게 공유됐어요.':'약속 장소에서 만나기로 했어요.');persist();}go('pickup');toast('약속에 함께하기로 했어요.');}finally{state.loading=false;}}
 async function setReady(value){if(!joined())throw Error('먼저 초대를 수락해 주세요.');if(isDriver())throw Error('픽업받는 사람의 준비 상태만 변경할 수 있어요.');if(live){const m=me();if(state.share&&!m.started_at)await api('/appointments/'+appointment.id+'/member','POST',{action:'depart'});await api('/appointments/'+appointment.id+'/member','POST',{ready:value});await refresh();if(state.share)startLocation();}else{state.ready=value;persist();render();}toast('준비 상태를 일행에게 알렸어요.');}
 async function confirmBoard(){if(isDriver()||phase()!=='arrived'||!myPickupActive())throw Error('내 픽업 장소에 차량이 도착한 뒤 확인해 주세요.');if(live){await api('/appointments/'+appointment.id+'/member','POST',{action:'board_confirm'});await refresh();}else{state.boardConfirm=true;log('탑승 확인을 보냈어요','운전자가 탑승을 확인하면 함께 이동 상태로 바뀌어요.');render();}toast('운전자에게 탑승 확인을 보냈어요.');}
 function stopLocation(){if(watch!==null&&navigator.geolocation)navigator.geolocation.clearWatch(watch);watch=null;}
 function startLocation(){if(!live||!state.share||watch!==null||!navigator.geolocation)return;watch=navigator.geolocation.watchPosition(async p=>{if(Date.now()-lastUpload<8000||!appointment||!state.share||p.coords.accuracy>100)return;lastUpload=Date.now();try{await api('/appointments/'+appointment.id+'/location','POST',{lat:p.coords.latitude,lon:p.coords.longitude,accuracy:p.coords.accuracy,mock:false});}catch{}},()=>toast('현재 위치를 확인하지 못했어요. 픽업 위치는 지도에서 직접 정할 수 있어요.'),{enableHighAccuracy:true,maximumAge:5000,timeout:12000});}
 function sheet(title,body){sheetFocus=document.activeElement;$('sheetRoot').innerHTML='<div class="sheet-scrim"><section class="sheet" role="dialog" aria-modal="true" aria-labelledby="sheetTitle"><div class="sheet-head"><h2 id="sheetTitle">'+title+'</h2><button class="close" data-action="close-sheet" aria-label="닫기">×</button></div>'+body+'</section></div>';document.querySelector('.sheet .close').focus();}
 function closeSheet(){$('sheetRoot').innerHTML='';sheetFocus?.focus();}
 function seatPreferences(){const binding=me()?.seat_binding,slots=seatProfile.vehicle_memories||{};return '<div class="section-heading"><h2>내 좌석 설정</h2></div><label class="consent-toggle"><input id="seatAuto" type="checkbox" '+(seatProfile.enabled?'checked':'')+'><span><strong>승차하면 내 설정 자동 적용</strong><small>현재 실내 온도에 따라 열선·통풍을 적용해요.</small></span></label><div class="seat-preferences"><label class="input-label" for="coldHeat">추울 때 열선</label><select id="coldHeat">'+[0,1,2,3].map(v=>'<option value="'+v+'" '+(seatProfile.cold_heat===v?'selected':'')+'>'+(v?v+'단계':'끄기')+'</option>').join('')+'</select><label class="input-label" for="hotVent">더울 때 통풍</label><select id="hotVent">'+[0,1,2,3].map(v=>'<option value="'+v+'" '+(seatProfile.hot_ventilation===v?'selected':'')+'>'+(v?v+'단계':'끄기')+'</option>').join('')+'</select>'+(binding?'<label class="input-label" for="seatMemory">이 차량의 좌석 메모리</label><select id="seatMemory">'+[0,1,2,3].map(v=>'<option value="'+v+'" '+((slots[binding.vehicle_key]||0)===v?'selected':'')+'>'+(v?'메모리 '+v:'변경하지 않기')+'</option>').join('')+'</select><p class="privacy-line">차량에 미리 저장한 좌석 메모리 번호를 선택하세요. 이 차량에서만 적용해요.</p>':'')+'</div>';}
 function profile(){const m=me();if(compactWeb){sheet('좌석 설정',seatPreferences()+button('저장','save-profile'));return;}sheet('내 정보','<div class="profile-block">'+photo(isDriver()?driver().name:m?.name||'Kelly')+'<div><h2>'+esc(isDriver()?driver().name:m?.name||'Kelly')+'</h2><p>'+(!live?'예시 프로필':isDriver()?'운전자':'약속 참가자')+'</p></div></div><label class="consent-toggle"><input id="profileShare" type="checkbox" '+(state.share?'checked':'')+'><span><strong>이번 이동 동안 위치 공유</strong><small>약속 일행에게만 공유하며 언제든 중지할 수 있어요.</small></span></label>'+seatPreferences()+(!live?'<p class="privacy-line">예시 설정은 이 브라우저에만 저장해요.</p>'+button('시연 상태 설정','demo-tools','soft'):'')+button('완료','save-profile'));}
 async function saveProfile(){const updated={enabled:!!$('seatAuto').checked,cold_heat:Number($('coldHeat').value),hot_ventilation:Number($('hotVent').value)};const binding=me()?.seat_binding;if(binding&&$('seatMemory'))updated.vehicle_memory={vehicle_key:binding.vehicle_key,slot:Number($('seatMemory').value)};if(live){if(!token)throw Error('먼저 임시 친구를 선택해 주세요.');seatProfile=await api('/seat-profile','POST',updated);}else{seatProfile={...seatProfile,...updated};localStorage.setItem('pooly.web.seatProfile',JSON.stringify(seatProfile));}if(compactWeb){closeSheet();toast('좌석 설정을 저장했어요.');return;}state.share=!!$('profileShare').checked;if(live&&appointment){await api('/appointments/'+appointment.id+'/member','POST',{share:state.share});if(!state.share)stopLocation();await refresh();}persist();closeSheet();toast('내 설정을 저장했어요.');}
 function demoTools(){sheet('미리보기 상태','<p class="caption">예시 데이터의 역할과 이동 상태를 바꿔볼 수 있어요.</p><div class="mode-options" style="grid-template-columns:1fr 1fr">'+button('픽업받는 사람','role-rider','soft')+button('운전자','role-driver','soft')+'</div><label class="input-label" for="sheetStage">시연 상태</label><select id="sheetStage">'+[['idle','출발 전'],['driving','운전자가 오는 중'],['arrived','픽업 장소 도착'],['boarded','승차 완료'],['finished','여정 완료']].map(([v,l])=>'<option value="'+v+'" '+(phase()===v?'selected':'')+'>'+l+'</option>').join('')+'</select>'+button('이 상태로 보기','apply-demo-stage')+button('처음부터 다시 보기','reset-demo',''));}
 function setStage(value){if(live)return;if(value!=='idle'&&(!state.joined||(state.mode==='pickup'&&!state.place))){ $('demoStage').value=state.stage;return toast('초대를 수락하고 내 픽업 위치를 먼저 정해 주세요.');}const old=state.stage;state.stage=value;if(value==='idle')state.boardConfirm=false;if(old!==value)log(value==='arrived'?driver().name+' 님이 도착했어요':value==='boarded'?'승차가 확인됐어요':value==='finished'?'함께 이동을 마쳤어요':'운전자 상태가 바뀌었어요',value==='arrived'?'내가 정한 픽업 위치에서 만나요.':'미리보기의 예시 상태예요.');state.screen=value==='finished'?'history':isDriver()?'detail':'pickup';persist();closeSheet();render();}
 async function locate(){if(!navigator.geolocation)throw Error('이 브라우저에서는 위치 확인을 사용할 수 없어요.');if(!live){toast('미리보기에서는 예시 위치를 사용해요.');selectPlace({...places[0],name:'현재 위치 근처 · 예시'});return;}navigator.geolocation.getCurrentPosition(p=>{selectPlace({id:'current',name:'내가 있는 위치',address:'현재 위치에서 지정',lat:p.coords.latitude,lon:p.coords.longitude});},()=>toast('위치 권한을 확인해 주세요. 지도에서도 직접 선택할 수 있어요.'),{enableHighAccuracy:true,timeout:12000,maximumAge:5000});}
 async function handle(action,el){switch(action){case'home':case'invite':case'detail':case'history':case'pickup':case'heatmap':go(action);break;case'heat-period':state.heatPeriod=el.dataset.value;render();break;case'heat-region':state.heatRegion=el.dataset.value;render();break;case'toggle-picker-heat':state.pickerHeat=!state.pickerHeat;if(map&&map.isStyleLoaded()){if(!map.getSource('pickup-demo'))window.PoolyHeatmap.add(map);map.setLayoutProperty('pickup-demo-heat','visibility',state.pickerHeat?'visible':'none');}el.setAttribute('aria-pressed',state.pickerHeat);el.textContent=state.pickerHeat?'히트맵 켜짐':'히트맵 꺼짐';break;case'open-appointment':if(live){selectAppointment(appointments.find(a=>a.id===el.dataset.id)||null);}go('detail');break;case'pick-place':openPicker();break;case'picker-back':{const back=state.picker?.back||'invite';state.picker=null;go(back);break;}case'save-place':await savePlace();break;case'drives':if(joined()&&phase()!=='idle')throw Error('출발한 뒤에는 운전 여부를 바꿀 수 없어요.');state.drives=el.dataset.drives==='true';state.mode=state.drives?'car':assignedPickup()?'pickup':'walk';render();break;case'join':await join();break;case'decline':if(live){if(appointment)throw Error('이미 참가한 약속이에요. 일행에게 메시지로 알려 주세요.');if(!token){const s=await api('/sessions','POST',{name:invitation.member.name},'');token=s.token;localStorage.setItem('pooly.web.token',token);}await api('/invites/'+encodeURIComponent(query.get('invite'))+'/join','POST',{decline:true});}else{state.joined=false;persist();}state.screen='home';state.error='';$('screen').innerHTML=empty('이번 약속은 함께하지 않아요.','초대에 대한 응답을 보냈어요.');$('bottomNav').hidden=true;toast('초대를 거절했어요.');break;case'ready':await setReady(el.dataset.ready);break;case'board-confirm':await confirmBoard();break;case'profile':if(!demoMode||token)profile();break;case'close-sheet':closeSheet();break;case'save-profile':await saveProfile();break;case'message':sheet('일행에게 메시지','<p class="caption">이번 약속에 함께하는 일행에게 전달해요.</p>'+['지금 나갈게요!','픽업 위치에서 기다리고 있어요.','천천히 조심해서 오세요.'].map((m,i)=>button(esc(m),'quick-message','', 'data-message="'+i+'"')).join(''));break;case'quick-message':{const text=['지금 나갈게요!','픽업 위치에서 기다리고 있어요.','천천히 조심해서 오세요.'][Number(el.dataset.message)];if(live){if(!appointment)throw Error('먼저 초대에 참가해 주세요.');await api('/appointments/'+appointment.id+'/messages','POST',{body:text});}else log('일행에게 메시지를 보냈어요',text);closeSheet();toast(live?'메시지를 보냈어요.':'예시 메시지를 보냈어요.');break;}case'locate':await locate();break;case'demo-tools':demoTools();break;case'apply-demo-stage':setStage($('sheetStage').value);break;case'role-rider':case'role-driver':if(live)return;state.role=action==='role-driver'?'driver':'rider';closeSheet();go(state.role==='driver'?'detail':'home');break;case'reset-demo':if(live)return;stopLocation();Object.assign(state,{screen:'home',role:'rider',stage:'idle',joined:false,place:null,mode:'pickup',drives:false,ready:'preparing',share:false,boardConfirm:false,notices:[],picker:null,notes:'',error:''});persist();closeSheet();render();break;}}
 document.addEventListener('click',async e=>{const button=e.target.closest('[data-action]');if(!button||button.disabled)return;try{await handle(button.dataset.action,button);}catch(error){state.error=error.message;toast(error.message);}});
 document.addEventListener('input',e=>{if(e.target.id==='pickupNotes'&&state.picker)state.picker.notes=e.target.value;});
 document.addEventListener('change',e=>{
  if(e.target.id==='demoFriend')switchDemoFriend(e.target.value).catch(showDemoError);
  if(e.target.id==='demoStage')setStage(e.target.value);
  if(e.target.id==='shareConsent')state.share=e.target.checked;
 });
 document.addEventListener('keydown',e=>{if(e.key==='Escape'&&$('sheetRoot').innerHTML)closeSheet();if(e.key==='Tab'&&$('sheetRoot').innerHTML){const f=[...document.querySelectorAll('.sheet button,.sheet input,.sheet select')].filter(x=>!x.disabled);if(e.shiftKey&&document.activeElement===f[0]){e.preventDefault();f[f.length-1]?.focus();}else if(!e.shiftKey&&document.activeElement===f[f.length-1]){e.preventDefault();f[0]?.focus();}}});
 window.addEventListener('pagehide',stopLocation);
 window.PoolyWeb={state,live,go,openPicker,canEdit,trip,me,phase,refresh,mapLoaded:false};
 function showDemoError(error){state.loading=false;state.error=error.message;render();}
 async function switchDemoFriend(id){
  const select=$('demoFriend');
  select.disabled=true;
  stopLocation();closeSheet();token='';appointment=null;appointments=[];liveSignature='';
  Object.assign(state,{screen:'home',picker:null,error:'',loading:true});render();
  try{
   const session=await api('/demo/session','POST',{contact_id:id},'');
   token=session.token;demoFriend=id;select.value=id;
   localStorage.setItem('pooly.web.demoFriend',id);
   await refresh();
   state.loading=false;render();
  }finally{select.disabled=false;}
 }
 async function initDemo(){
  document.body.classList.add('local-demo');
  state.pickerHeat=false;
  state.loading=true;render();
  const roster=await api('/demo/roster','GET',undefined,'');
  demoFriends=roster.friends;
  const header=document.querySelector('.header-right'),selector=document.createElement('select');
  selector.id='demoFriend';selector.setAttribute('aria-label','임시 친구');
  selector.replaceChildren(...demoFriends.map(friend=>new Option(friend.name,friend.id)));
  header.prepend(selector);
  document.querySelector('.avatar-mini').setAttribute('aria-label','좌석 설정');
  const previous=localStorage.getItem('pooly.web.demoFriend');
  const id=demoFriends.some(friend=>friend.id===previous)?previous:roster.default_id;
  if(id){await switchDemoFriend(id);setInterval(()=>{if(document.visibilityState==='visible'&&token&&!state.picker)refresh().catch(()=>{});},3000);}
  else {state.loading=false;selector.disabled=true;render();}
 }
 async function init(){if(demoMode){await initDemo().catch(showDemoError);return;}if(!live){document.body.classList.add('local-demo');state.pickerHeat=false;document.querySelector('.avatar-mini').setAttribute('aria-label','좌석 설정');try{seatProfile=JSON.parse(localStorage.getItem('pooly.web.seatProfile')||'null')||seatProfile;const saved=JSON.parse(localStorage.getItem('pooly.web.preview.v1')||'null');if(saved&&typeof saved==='object'){for(const key of ['place','notes'])if(key in saved)state[key]=saved[key];}}catch{}state.joined=true;render();return;}
 state.loading=true;render();try{if(query.get('invite')){try{invitation=await api('/invites/'+encodeURIComponent(query.get('invite')),'GET',undefined,'');}catch(e){if(!token||e.status!==410)throw e;}if(invitation){demoTrip.name=invitation.name;demoTrip.destination=invitation.destination;demoTrip.meeting_at=invitation.meeting_at;state.mode=invitation.member.mode||'walk';state.drives=state.mode==='car';}}if(token){try{await refresh();}catch(error){if(error.status!==401)throw error;token='';appointment=null;localStorage.removeItem('pooly.web.token');}}if(invitation&&!appointment)state.screen='invite';state.loading=false;render();const p=await api('/places?q=','GET',undefined,token).catch(()=>null);if(p?.results)serverPlaces=p.results;setInterval(()=>{if(document.visibilityState==='visible'&&token&&!state.picker&&state.screen!=='heatmap')refresh().catch(()=>{});},5000);}catch(e){state.loading=false;state.error=e.message;render();}}
 init();
})();

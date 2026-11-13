/* Shared state/API for the original car and phone design. */
(function () {
  'use strict';
  var role = /phone\.html$/.test(location.pathname) ? 'phone' : 'car';
  var params = new URLSearchParams(location.search);
  var prefix = 'pooly.v2.' + role + '.';
  var listeners = new Set(), pending = new Map(), sequence = 0, timer, refreshing = null;
  var app = {user:null, appointments:[], contacts:[], notifications:[], places:[], parking:[], route:null,
    selected:localStorage.getItem(prefix+'selected'), invite:params.get('invite'), invitation:null,
    offline:false, loading:true, busy:false, native:!!window.PoolyNative, nativeStatus:{}, lastNotification:null};
  var token = localStorage.getItem(prefix+'token') || '';
  var routeKey = '', routeGeneration = 0;
  function emit() { listeners.forEach(function (fn) { try { fn(app); } catch (e) { console.error('UI update',e); } }); }
  function active() { return app.appointments.find(function (a) { return a.id===app.selected; }) || null; }
  function me(a) { return !a ? null : a.me_id==='owner' ? a.owner : a.members.find(function(m){return m.id===a.me_id;}); }
  function driver(a) { return a ? [a.owner].concat(a.members).find(function(m){return m.user_id===a.driver_id;}) : null; }
  function native(action, payload) {
    if (!window.PoolyNative) return Promise.reject(new Error('차량 앱에서 사용할 수 있어요.'));
    var id='request-'+(++sequence);
    return new Promise(function(resolve,reject){
      var timeout=setTimeout(function(){pending.delete(id);reject(new Error('응답이 늦어지고 있어요. 다시 시도해 주세요.'));},20000);
      pending.set(id,{resolve:resolve,reject:reject,timeout:timeout});
      window.PoolyNative.postMessage(JSON.stringify({id:id,action:action,payload:payload||{}}));
    });
  }
  if (window.PoolyNative) window.PoolyNative.onmessage=function(event){
    var d;try{d=JSON.parse(event.data);}catch(e){return;}
    if(d.event){app.nativeStatus=Object.assign({},app.nativeStatus,d.data||{});emit();return;}
    var p=pending.get(d.id);if(!p)return;clearTimeout(p.timeout);pending.delete(d.id);
    if(d.ok)p.resolve(d.data);else p.reject(new Error(d.error||'처리하지 못했어요.'));
  };
  async function api(path, method, body, unauthenticated) {
    method=method||'GET';
    var headers={'Content-Type':'application/json'};
    if(token&&!unauthenticated)headers.Authorization='Bearer '+token;
    var result;
    if(window.PoolyNative){
      result=await native('api',{path:'/api'+path,method:method,body:body||null,token:unauthenticated?'':token});
      if(result.status>=400){var e=new Error(result.data.error||'처리하지 못했어요.');e.status=result.status;throw e;}
      return result.data;
    }
    var response=await fetch('/api'+path,{method:method,headers:headers,body:body===undefined?undefined:JSON.stringify(body),cache:'no-store'});
    result=await response.json();
    if(!response.ok){var error=new Error(result.error||'처리하지 못했어요.');error.status=response.status;throw error;}
    return result;
  }
  async function register(name){
    var data=await api('/sessions','POST',{name:name},true);
    token=data.token;localStorage.setItem(prefix+'token',token);app.user=data.user;
    if(window.PoolyNative)await native('session',{token:token,role:role});
  }
  function signature(a){return a?JSON.stringify([a.id,a.origin,a.destination,a.parking,a.pickup_order,a.owner.mode,a.owner.started_at,a.timing,a.members.map(function(m){return[m.id,m.mode,m.status,m.pickup,m.started_at,m.arrived_at,m.travel_minutes];})]):'';}
  async function loadRoute(force){
    var a=active(),key=signature(a);if(!a){app.route=null;routeKey='';return;}
    if(!force&&key===routeKey)return;
    routeKey=key;var generation=++routeGeneration;
    try{var r=await api('/appointments/'+a.id+'/route');if(generation===routeGeneration){app.route=r;emit();}}
    catch(e){if(generation===routeGeneration){app.route=null;emit();}}
  }
  async function refresh(force){
    if(refreshing){await refreshing;if(!force)return;}
    if(!token)return;
    refreshing=(async function(){try{
      var snapshot=await api('/state');
      Object.assign(app,snapshot,{offline:false,loading:false});
      if(app.invite){var invited=app.appointments.find(function(a){return a.id===(app.invitation?app.invitation.appointment_id:app.inviteAid);});app.selected=invited?invited.id:null;if(invited)app.inviteError=null;}
      else if(!app.appointments.some(function(a){return a.id===app.selected;})){
        var first=app.appointments.find(function(a){return !['cancelled','finished'].includes(a.trip.phase);})||app.appointments[app.appointments.length-1];
        app.selected=first?first.id:null;
      }
      localStorage.setItem(prefix+'selected',app.selected||'');
      if(app.native)native('context',{appointmentId:app.selected||''}).catch(function(){});
      var newest=app.notifications.length?app.notifications[0].id:0;
      if(app.lastNotification!==null&&newest>app.lastNotification){
        var notification=app.notifications[0];
        window.dispatchEvent(new CustomEvent('pooly:notice',{detail:notification}));
      }
      app.lastNotification=Math.max(newest,app.lastNotification);
      var bound=app.appointments.find(function(a){return a.id===app.nativeStatus.navigationAid;});
      if(app.native&&bound&&['finished','cancelled'].includes(bound.trip.phase))native('endNavigation',{appointmentId:bound.id}).catch(function(){});
      emit();loadRoute(false);
    }catch(e){app.offline=true;app.loading=false;app.error=e.message;emit();if(e.status===401){token='';localStorage.removeItem(prefix+'token');}}
    } )();
    try{await refreshing;}finally{refreshing=null;}
  }
  async function init(){
    if(app.invite){try{app.inviteAid=JSON.parse(atob(app.invite.split('.')[0].replace(/-/g,'+').replace(/_/g,'/')))[0];}catch(e){}try{app.invitation=await api('/invites/'+encodeURIComponent(app.invite),'GET',undefined,true);}catch(e){app.inviteError=e.message;}}
    if(!token)await register(role==='car'?'운전자':app.invitation?app.invitation.member.name:'참가자');
    else if(window.PoolyNative)await native('session',{token:token,role:role});
    await refresh();
    if(!token){await register(role==='car'?'운전자':app.invitation?app.invitation.member.name:'참가자');await refresh();}
    if(app.invitation){
      var joined=app.appointments.find(function(a){return a.id===app.invitation.appointment_id;});
      if(joined){app.selected=joined.id;emit();loadRoute(true);}
    }
    if(timer)clearInterval(timer);
    timer=setInterval(function(){if(app.native?(app.nativeStatus.foreground||app.nativeStatus.navigationAid===app.selected):document.visibilityState==='visible')refresh();},2500);
  }
  async function run(task){
    if(app.busy)throw new Error('처리 중이에요. 잠시 기다려 주세요.');
    app.busy=true;emit();
    try{var result=await task();await refresh(true);return result;}
    finally{app.busy=false;emit();}
  }
  function select(id){app.selected=id;localStorage.setItem(prefix+'selected',id);if(app.native)native('context',{appointmentId:id||''}).catch(function(){});app.route=null;routeKey='';emit();loadRoute(true);}
  async function create(data){var a=await run(function(){return api('/appointments','POST',data);});select(a.id);return a;}
  async function patch(data){var a=active();if(!a)throw new Error('약속을 먼저 선택해 주세요.');return run(function(){return api('/appointments/'+a.id,'PATCH',Object.assign({revision:a.revision},data));});}
  async function action(name){var a=active();if(!a)throw new Error('약속을 먼저 선택해 주세요.');var result=await run(function(){return api('/appointments/'+a.id+'/actions','POST',{action:name});});if(app.native&&(name==='board'||name==='skip')&&app.nativeStatus.navigationAid===a.id&&app.nativeStatus.navigationSegmentOnly)native('forgetNavigation').catch(function(){});if(name==='finish'||name==='cancel'){stopTracking();if(app.native)await native('endNavigation',{appointmentId:a.id});}return result;}
  async function member(data){var a=active();if(!a)throw new Error('약속을 먼저 선택해 주세요.');var result=await run(function(){return api('/appointments/'+a.id+'/member','POST',data);});if(data.share===false||data.action==='arrive')stopTracking();return result;}
  async function join(data){if(!app.invite)throw new Error('초대 링크를 먼저 열어 주세요.');return run(async function(){var a=await api('/invites/'+encodeURIComponent(app.invite)+'/join','POST',data);if(a.id)app.selected=a.id;return a;});}
  async function addContact(name){return run(function(){return api('/contacts','POST',{name:name});});}
  var POOLY_SERVER='https://yeonil.pythonanywhere.com';
  function serverOrigin(){var base=app.nativeStatus.apiBase;if(base)return base;return location.protocol==='https:'&&location.hostname!=='appassets.androidplatform.net'?location.origin:POOLY_SERVER;}
  function inviteUrl(member){return serverOrigin()+'/phone.html?invite='+encodeURIComponent(member.invite_token);}
  async function shareInvite(member){
    if(!member||!member.invite_token)throw new Error('일정을 저장한 뒤 초대를 공유해 주세요.');
    var url=inviteUrl(member),a=active(),message=(a?a.name:'Pooly')+' 약속에 초대해요.\n'+url;
    if(window.PoolyNative)return native('share',{text:message});
    if(navigator.clipboard&&window.isSecureContext){await navigator.clipboard.writeText(message);return {copied:true,url:url};}
    return {url:url,text:message};
  }
  var locationWatch=null;
  async function locate(){
    if(window.PoolyNative)return native('location');
    return new Promise(function(resolve,reject){if(!navigator.geolocation)return reject(new Error('이 기기는 위치 확인을 지원하지 않아요.'));navigator.geolocation.getCurrentPosition(function(p){resolve({lat:p.coords.latitude,lon:p.coords.longitude,accuracy:p.coords.accuracy,name:'현재 위치',address:''});},function(){reject(new Error('위치 권한을 확인하거나 지도에서 장소를 선택해 주세요.'));},{enableHighAccuracy:true,timeout:12000,maximumAge:5000});});
  }
  async function startTracking(){
    var a=active(),mine=me(a);if(!a||!mine||!mine.share||!mine.started_at)return;
    if(window.PoolyNative)return native('tracking',{appointmentId:a.id,enabled:true});
    if(locationWatch!==null||!navigator.geolocation)return;
    locationWatch=navigator.geolocation.watchPosition(function(p){api('/appointments/'+a.id+'/location','POST',{lat:p.coords.latitude,lon:p.coords.longitude,accuracy:p.coords.accuracy,mock:false}).catch(function(){});},function(){window.dispatchEvent(new CustomEvent('pooly:error',{detail:'위치 권한을 확인해 주세요.'}));},{enableHighAccuracy:true,maximumAge:5000,timeout:15000});
  }
  function stopTracking(){if(window.PoolyNative)native('tracking',{enabled:false}).catch(function(){});if(locationWatch!==null){navigator.geolocation.clearWatch(locationWatch);locationWatch=null;}}
  async function navigate(){
    var a=active();if(!a)throw new Error('약속을 선택해 주세요.');
    if(!a.is_driver||['cancelled','finished'].includes(a.trip.phase))throw new Error('진행 중인 약속의 운전자만 안내를 요청할 수 있어요.');
    if(a.trip.phase==='idle'){await action('depart');a=active();}
    var route=await api('/appointments/'+a.id+'/route');
    if(!window.PoolyNative)throw new Error('Pleos 차량 앱에서 내비게이션을 연결할 수 있어요.');
    var stops=route.stops.filter(function(p){return p.kind!=='start';});
    var boarded=a.trip.boarded||[],remaining=a.trip.phase==='idle'?a.pickup_order:(a.trip.active_pickups||[]).slice(a.trip.index||0);
    stops=stops.filter(function(p){return p.kind!=='pickup'||remaining.includes(p.id)&&!boarded.includes(p.id);});
    app.nativeStatus.navigationAid=a.id;
    return native('navigate',{stops:stops,appointmentId:a.id});
  }
  function time(value){if(!value)return '—';return new Date(value).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit',hour12:false});}
  function date(value){if(!value)return '';return new Date(value).toLocaleDateString('ko-KR',{month:'long',day:'numeric',weekday:'short'});}
  function minutes(value){var d=new Date(value);return d.getHours()*60+d.getMinutes();}
  function rgbaName(name){var colors=['#8A6440','#3D5A73','#6A4F7A','#4F6A52','#7A4E4E'];var n=0;for(var i=0;i<name.length;i++)n+=name.charCodeAt(i);return colors[n%colors.length];}
  function status(m,a){if(m.status==='pending')return '초대 대기';if(m.status==='declined')return '참가하지 않음';if(m.home_at)return '귀가 확인 완료';if(m.arrived_at)return '약속 장소 도착';if(m.boarded_at)return '탑승 완료';if(m.mode==='pickup')return {preparing:'준비 중',ready:'준비 완료',walking:'픽업 장소로 이동 중',waiting:'픽업 장소 도착'}[m.ready]||'준비 중';return m.started_at?'이동 중':'출발 전';}
  function estimate(a,m){
    if(!a)return null;var route=app.route,travel=m&&m.travel_minutes;
    if(m&&m.mode==='pickup')travel=route?route.total_minutes:null;
    if(a.is_driver||m&&m.id==='owner'&&m.mode==='car')travel=route?route.total_minutes:null;
    if(!travel&&m&&m.origin&&a.destination){var dx=(m.origin.lon-a.destination.lon)*88000,dy=(m.origin.lat-a.destination.lat)*111000;travel=Math.max(1,Math.ceil(Math.hypot(dx,dy)/75*1.2));}
    var target=route&&route.arrival_target||a.timing&&a.timing.arrival_target||new Date(new Date(a.meeting_at).getTime()-300000).toISOString();var departure=travel?new Date(new Date(target).getTime()-travel*60000):null;
    var arrival=m&&m.started_at&&travel?new Date(new Date(m.started_at).getTime()+travel*60000):new Date(target);
    return {travel:travel,departure:departure,arrival:arrival};
  }
  document.addEventListener('visibilitychange',function(){if(app.native?(app.nativeStatus.foreground||app.nativeStatus.navigationAid===app.selected):document.visibilityState==='visible')refresh();});
  window.addEventListener('pooly:native',function(e){app.nativeStatus=Object.assign({},app.nativeStatus,e.detail);document.documentElement.classList.toggle('vehicle-restricted',!!app.nativeStatus.restricted);if(e.detail.navigationMessage)window.dispatchEvent(new CustomEvent('pooly:error',{detail:e.detail.navigationMessage}));emit();});
  window.Pooly={app:app,role:role,api:api,native:native,on:function(fn){listeners.add(fn);return function(){listeners.delete(fn);};},active:active,me:me,driver:driver,init:init,refresh:refresh,select:select,create:create,patch:patch,action:action,member:member,join:join,addContact:addContact,shareInvite:shareInvite,inviteUrl:inviteUrl,locate:locate,startTracking:startTracking,stopTracking:stopTracking,navigate:navigate,time:time,date:date,minutes:minutes,color:rgbaName,status:status,estimate:estimate,message:function(body){var a=active();return run(function(){return api('/appointments/'+a.id+'/messages','POST',{body:body});});},search:function(q){return api('/places?q='+encodeURIComponent(q));}};
})();

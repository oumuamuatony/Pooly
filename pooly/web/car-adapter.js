/* Preserve the supplied design components; replace their fixture actions/data. */
(function(){
 'use strict';var T=window.Pooly;
 function copy(v){return JSON.parse(JSON.stringify(v));}
 function photo(name){var known={max:'max',kelly:'kelly',jeff:'jeff'},key=known[String(name).toLowerCase()];if(!key){var n=0;String(name).split('').forEach(function(c){n+=c.charCodeAt(0);});key=['max','kelly','jeff'][n%3];}return 'assets/profiles/'+key+'.png';}
 function panel(mode,component){if(!T.app.native||!T.app.nativeStatus.automotive)return Promise.resolve();return T.native('showNavigationPanel',{mode:mode}).then(function(r){if(r.permissionRequired)component.toast('내비 위에 표시하려면 다른 앱 위에 표시를 허용해 주세요.');}).catch(function(e){component.toast(e.message);});}
 function geo(p){return p?[230+(p.lon-127.0374)*13350,430-(p.lat-37.5444)*12000]:[230,430];}
 function mins(v){return v?T.minutes(v):0;}
 function hm(v){v=((Math.round(v)%1440)+1440)%1440;return String(Math.floor(v/60)).padStart(2,'0')+':'+String(v%60).padStart(2,'0');}
 function safe(fn,component){return async function(){
  try{return await fn.apply(this,arguments);}
  catch(e){component.setState({actionError:e.message});}
 };}
 function margin(v){return{txt:(v>=0?v+'분 여유':(-v)+'분 지연'),cls:'bdg '+(v>=0?'b-ok':'b-err')};}
 function guidancePaused(a){
  if(!a)return false;
  var saved=a.trip.navigation||{},native=T.app.nativeStatus.navigationSession;
  if(native&&native.appointmentId===a.id&&native.requestedAt>=(saved.requested_at||0))return native.status==='stopped';
  return saved.status==='stopped';
 }
 function place(p,fallback){return p||fallback||{name:'장소 지정 대기',lat:37.5444,lon:127.0374,address:''};}
 function minuteDistance(a,b){if(!a||!b)return null;return Math.max(1,Math.ceil(Math.hypot((a.lon-b.lon)*88000,(a.lat-b.lat)*111000)/75*1.2));}
 function isUpcoming(a){return !['finished','cancelled'].includes(a.trip.phase);}
 function formatDuration(seconds){
  if(seconds===null)return '확인 중';
  var minutes=Math.ceil(seconds/60),hours=Math.floor(minutes/60);
  return hours?hours+'시간 '+(minutes%60)+'분':minutes+'분';
 }
 function routeKey(a){
  return JSON.stringify([a.origin,a.destination,a.parking,a.pickup_order,a.trip.phase,a.trip.index,
    a.members.map(function(m){return [m.id,m.mode,m.status,m.pickup];})]);
 }
 function itineraryTiming(a,entry,now){
  var ns=T.app.nativeStatus,route=entry&&entry.route,eta=ns.navigationEta,points={};
  var finalId=a.parking?'lot':'dest',extra=a.parking?((a.parking.parking_minutes||0)+(a.parking.walk_minutes||0))*60:0;
  if(a.trip.phase==='parked'){
   var parked=Date.parse(a.trip.parked_at)||now,walk=a.parking?(a.parking.walk_minutes||0)*60:0;
   return {seconds:Math.max(0,(parked-now)/1000+walk),arrival:parked+walk*1000,source:walk?'주차 후 도보 추정':'차량 도착',pickups:points};
  }
  if(ns.navigationAid===a.id&&!guidancePaused(a)&&['ROUTE_GUIDING','SIMULATION_GUIDING'].includes(ns.navigationMode)){
   (ns.navigationWaypoints||[]).forEach(function(point){
    if(point.stopId&&window.PoolyPleos.fresh(point))points[point.stopId]=point.updatedAt+point.seconds*1000;
   });
   if(window.PoolyPleos.fresh(eta)&&eta.stopId===finalId){
    var liveArrival=Math.max(now,eta.updatedAt+eta.seconds*1000)+extra*1000;
    return {seconds:(liveArrival-now)/1000,arrival:liveArrival,source:'pleos',pickups:points};
   }
  }
  if(entry&&entry.pleosSeconds>0)return {seconds:entry.pleosSeconds+extra,arrival:now+(entry.pleosSeconds+extra)*1000,source:'pleos',pickups:points};
  if(!route)return {seconds:null,arrival:null,source:'',pickups:points};
  var remaining=(a.trip.active_pickups||[]).slice(a.trip.index||0);
  var firstLeg=0;
  if(a.trip.phase!=='idle')route.stops.slice(1).forEach(function(stop,index){
   if(stop.kind==='pickup'&&(a.trip.boarded||[]).includes(stop.id))firstLeg=index+1;
  });
  var total=0;
  route.legs.slice(firstLeg).forEach(function(leg,index){
   var stop=route.stops[firstLeg+index+1];
   total+=leg.duration_s;
   if(stop&&stop.kind==='pickup'&&!points[stop.id]&&(a.trip.phase==='idle'||remaining.includes(stop.id)))points[stop.id]=now+total*1000;
  });
  total+=extra;
  return {seconds:total,arrival:now+total*1000,source:route.provider==='osrm'?'도로 경로 추정':'',pickups:points};
 }
 window.PoolyCar=function(Component,env){
  var FR=env.FR,ALL=env.ALL,LOTS=env.LOTS,RES=env.RES,RESP=env.RESP;
  RES.splice(0,RES.length);RESP.splice(0,RESP.length);
  var originalRender=Component.prototype.renderVals;
  var activeComponent=null;
  function info(m){var p=m&&m.pickup;return{id:m.id,name:m.name,ini:m.name.length===3?m.name[1]:m.name[0],av:T.color(m.name),node:m.id,place:p?p.name:'장소 지정 대기',land:p?p.description||p.address:'',desc:p?p.description||'':'',walk:p&&m.origin?minuteDistance(m.origin,p):0,josa:'님'};}
  function defaultTrip(){return {name:'새 약속',time:840,start:'출발지 선택',dest:'목적지 선택',mode:'car',members:[],pickups:[],status:{},lot:'p1',depart:795};}
  function installPeople(){
   var a=T.active(),people=T.app.contacts.slice();if(a)a.members.forEach(function(m){if(!people.some(function(c){return c.id===m.id;}))people.push(m);});
   ALL.splice(0,ALL.length);people.forEach(function(c){var member=a&&a.members.find(function(m){return m.id===c.id;});FR[c.id]=info(member||c);ALL.push(c.id);});
   T.app.places.forEach(function(p){var row={id:p.id,name:p.name,addr:p.address,pt:geo(p),lat:p.lat,lon:p.lon,tag:'저장한 장소',land:p.description||p.address,walk:0};[RES,RESP].forEach(function(list){var i=list.findIndex(function(x){return x.id===p.id;});if(i>=0)list[i]=row;else list.push(row);});});
   if(T.app.parking.length){LOTS.splice(0,LOTS.length);T.app.parking.forEach(function(p){LOTS.push({id:p.id,name:p.name,pt:geo(p),lat:p.lat,lon:p.lon,address:p.address,drive:0,park:p.parking_minutes,walk:p.walk_minutes,fee:'요금 확인 필요',base:'현장 요금을 확인해 주세요',hours:'운영 정보 확인 필요',avail:'실시간 빈자리 정보 없음',ac:'bdg',price:0});});}
  }
  Component.prototype.componentDidMount=function(){
   activeComponent=this;T.carComponent=this;this._boardingPolicy=new window.PoolyBoarding();this._routeTimings=new Map();
   var self=this;
   this._boardingTimer=setInterval(function(){self.checkBoarding();},500);
   this._vehicleNotices={};this._onNative=function(e){self.vehicleNotice(e.detail||{});};window.addEventListener('pooly:native',this._onNative);
   this._off=T.on(function(){self.applyServer();});
   this._back=function(){var s=self.state;if(s.destinationSearchOpen){self.setState({nativeDestinationPending:null,destinationSearchOpen:false});T.native('cancelDestinationSelection').catch(function(){});}else if(s.meetingPickerOpen)self.setState({meetingPickerOpen:false});else if(s.sheet)self.setState({sheet:null});else if(s.screen==='search'&&s.form){self.cancelDestinationPreview();self.go('create');}else if(T.app.nativeStatus.navigationPanel){self._draftSerial=(self._draftSerial||0)+1;self.setState({screen:'home',form:null});T.native('closeNavigationPanel',{returnToApp:T.app.nativeStatus.navigationPanel==='create'}).catch(function(e){self.toast(e.message);});}else self.setState({screen:'home',form:null});};window.addEventListener('pooly:back',this._back);
   T.init().then(function(){self.applyServer();}).catch(function(e){T.app.offline=true;T.app.loading=false;self.toast('서버에 연결하지 못했어요. 기기·권한에서 다시 연결해 주세요.');self.setState({serverTick:Date.now()});});
  };
  var oldUnmount=Component.prototype.componentWillUnmount;
  Component.prototype.toast=function(){};
  // Native seat automation announces boarding/drop-off over the OEM navigation for 7 seconds.
  Component.prototype.vehicleNotice=function(d){
   var self=this;
   if(Object.prototype.hasOwnProperty.call(d,'navigationPanel')){
    var wasOpen=self._noticePanelOpen;
    self._noticePanelOpen=d.navigationPanel==='moving';
    if(wasOpen&&!self._noticePanelOpen)self.setState({guidanceNotice:null});
   }
   ['pickupBoardingNotice','pickupAlightNotice','seatLearnedNotice'].forEach(function(key){
    var n=d[key];
    if(!n||!n.key||self._vehicleNotices[n.key]||!T.app.appointments.some(function(a){return a.id===n.appointmentId;}))return;
    if(!(Date.now()-n.observedAt>=-5000&&Date.now()-n.observedAt<60000))return;
    self._vehicleNotices[n.key]=true;
    if(T.app.selected!==n.appointmentId)T.select(n.appointmentId);
    var notice={title:n.title,message:n.message};
    self.setState({screen:'moving',sheet:null,guidanceNotice:notice},function(){
     if(self.state.guidanceNotice===notice)panel('moving',self);
    });
    if(T.refresh)T.refresh();
   });
  };
  Component.prototype.componentWillUnmount=function(){if(this._off)this._off();if(this._onNative)window.removeEventListener('pooly:native',this._onNative);clearInterval(this._boardingTimer);window.removeEventListener('pooly:back',this._back);if(oldUnmount)oldUnmount.call(this);};
  Component.prototype.refreshRouteTimings=function(){
   if(!['home','detail'].includes(this.state.screen)||this._loadingRouteTimings)return;
   var self=this,now=Date.now();
   var visible=T.app.appointments.filter(isUpcoming);
   if(this.state.screen==='detail')visible=visible.filter(function(a){return a.id===T.app.selected;});
   var needsRoute=visible.find(function(a){
    var previous=self._routeTimings.get(a.id);
    return !previous||previous.key!==routeKey(a)||now-previous.checkedAt>120000;
   });
   if(!needsRoute)return;
   var a=needsRoute,entry={key:routeKey(a),checkedAt:now,route:null,pleosSeconds:null};
   this._loadingRouteTimings=true;
   this._routeTimings.set(a.id,entry);
   T.api('/appointments/'+a.id+'/route').then(async function(route){
    entry.route=route;
    self.setState({serverTick:Date.now()});
    var native=T.app.nativeStatus,stops=route.stops;
    if(a.is_driver&&a.trip.phase==='idle'&&native.navigationConnected&&stops.length===2&&stops[0].kind==='start'){
     var result=await T.native('estimateNavigationTrip',{destination:stops[1]});
     if(Number.isFinite(result.seconds)&&result.seconds>0)entry.pleosSeconds=result.seconds;
    }
   }).catch(function(){}).finally(function(){
    self._loadingRouteTimings=false;
    self.setState({serverTick:Date.now()});
    self.refreshRouteTimings();
   });
  };
  Component.prototype.timingFor=function(a){
   var entry=this._routeTimings&&this._routeTimings.get(a.id);
   var matching=entry&&entry.key===routeKey(a)?entry:null;
   if(!matching&&a.id===T.app.selected&&T.app.route)matching={route:T.app.route};
   return itineraryTiming(a,matching,Date.now());
  };
  Component.prototype.applyServer=function(){
   installPeople();var a=T.active(),s=this.state,patch={serverTick:Date.now(),events:!!a,invitesDone:!a||a.members.every(function(m){return m.status!=='pending';})};
   if(a){
    var r=T.app.route,trip={name:a.name,time:mins(a.meeting_at),date:a.meeting_at.slice(0,10),start:a.origin?a.origin.name:'출발지',dest:a.destination.name,mode:a.owner.mode,members:a.members.map(function(m){return m.id;}),pickups:a.trip.phase==='idle'?a.pickup_order.slice():(a.trip.active_pickups||[]).slice(),status:{},lot:a.parking?a.parking.id:'none',depart:a.trip.started_at?mins(a.trip.started_at):r?mins(r.recommended_departure):mins(a.meeting_at)-45,origin_place:a.origin,destination_place:a.destination};
    a.members.forEach(function(m){trip.status[m.id]=m.status;});
    var phase={idle:'drive',driving:'drive',near:'near',arrived:'arrived',destination:'tolot',parked:'parked',finished:'parked',cancelled:'drive'}[a.trip.phase]||'drive';
    var current=a.members.find(function(m){return m.id===trip.pickups[a.trip.index];});
    if(phase==='arrived'&&current&&['ready','waiting'].includes(current.ready))phase='ready';
    var mine=T.me(a),sharing=Object.assign({},s.share,{on:!!(mine&&mine.share)});
    patch.trip=trip;patch.mv={idx:a.trip.index,phase:phase,traffic:false,done:a.trip.boarded.slice(),finished:a.trip.phase==='finished',on:!['idle','cancelled','finished'].includes(a.trip.phase)&&!guidancePaused(a)};patch.share=sharing;
    patch.pplace={};a.members.forEach(function(m){if(m.pickup)patch.pplace[m.id]={name:m.pickup.name,land:m.pickup.address,desc:m.pickup.description,walk:minuteDistance(m.origin,m.pickup)||0};});
    if(s.sheet==='friend'&&!FR[s.ctx])patch.sheet=null;
    if(a.trip.phase==='cancelled'&&!['home','history'].includes(s.screen))patch.screen='home';
    else if(a.trip.phase==='finished'&&['moving','detail'].includes(s.screen))patch.screen='record';
    else if(guidancePaused(a)&&s.screen==='moving'&&!s.form&&!s.guidanceNotice)patch.screen='detail';
   }else{patch.trip=defaultTrip();patch.mv={idx:0,phase:'drive',traffic:false,done:[],finished:false,on:false};if(!s.form&&!['home','create','search','history'].includes(s.screen))patch.screen='home';}
   if(T.app.nativeStatus.navigationPanel==='moving'&&(!a||['finished','cancelled'].includes(a.trip.phase)))T.native('closeNavigationPanel',{returnToApp:false}).catch(function(){});
   this.setState(patch);
   this.refreshRouteTimings();
   this.checkBoarding();
  };
  Component.prototype.checkBoarding=function(){
   if(T.app.nativeStatus.automotive)return;
   if(!this._boardingPolicy||this._boardingBusy||T.app.busy||this.state.form||!T.app.native)return;
   var event=this._boardingPolicy.update(T.active(),T.app.nativeStatus,Date.now());
   if(event)this.completeBoarding(event);
  };
  Component.prototype.completeBoarding=async function(event){
   var a=T.active();if(!a||a.id!==event.appointmentId||(a.trip.active_pickups||[])[a.trip.index]!==event.memberId||a.trip.phase!=='arrived')return;
   this._boardingBusy=true;
   try{
    var result=await T.action('board');
    if(!result.trip.boarded.includes(event.memberId))throw new Error('탑승 상태를 확인해 주세요.');
    a=T.active();if(!a||a.id!==event.appointmentId)return;
    await T.navigate();
   }catch(e){this.toast('자동 승차 처리를 완료하지 못했어요. '+e.message);}
   finally{this._boardingBusy=false;}
  };
  Component.prototype.submitForm=async function(){
   var f=this.state.form,serial=this._draftSerial||0;if(!f||this._savingDraft===serial)return;this._savingDraft=serial;this.setState({formSaving:true,formError:''});
   try{
    if(!f.destination_place)throw new Error('목적지를 선택해 주세요.');
    if(!f.origin_place){var origin=await T.locate();if(!this.state.form||(this._draftSerial||0)!==serial)return;f=copy(this.state.form);f.origin_place=origin;f.start='현재 위치';this.setState({form:f});}
    var data={name:f.name,origin:f.origin_place,destination:f.destination_place,meeting_at:f.meeting_at||new Date(f.date+'T'+hm(f.time)+':00+09:00').toISOString(),mode:f.mode,pickup_order:f.pickups.slice(),members:f.members.map(function(id){return{id:id,mode:f.pickups.includes(id)?'pickup':'walk'};})};
    if(!data.origin||!data.destination)throw new Error('현재 위치와 목적지를 확인해 주세요.');
    if(this.state.formMode==='edit'){data.revision=f.revision;data.pickup_order=f.pickups.slice();await T.patch(data);}else await T.create(data);
    this.setState({form:null,screen:'detail',sheet:null,ctx:'seoul'});if(T.app.nativeStatus.navigationPanel)await T.native('closeNavigationPanel',{returnToApp:true});this.toast('일정을 저장했어요.');
   }catch(e){if(this._savingDraft===serial)this.setState({formError:e.message});}finally{if(this._savingDraft===serial){this._savingDraft=null;this.setState({formSaving:false});}}
  };
  Component.prototype.startTrip=async function(){
   this.setState({actionError:''});
   try{var a=T.active();if(!a)throw new Error('약속을 선택해 주세요.');
    if(a.is_driver){
      await T.action('depart');this.setState({screen:'moving',sheet:null,guidanceNotice:null});
      try{await T.startTracking();}catch(e){this.toast('출발은 기록했어요. 위치 공유 권한을 확인해 주세요.');}
      if(T.app.native&&T.app.nativeStatus.automotive)await T.navigate();
    }
    else{await T.member({action:'depart'});this.toast('출발을 일행에게 알렸어요.');}
   }catch(e){this.setState({screen:'detail',actionError:e.message});}
  };
  Component.prototype.board=async function(){try{await T.action('board');if(T.app.native&&T.app.nativeStatus.automotive)await T.navigate();}catch(e){this.toast(e.message);}};
  Component.prototype.notify=async function(){try{await T.action('notify');this.toast('일행에게 도착 알림을 보냈어요.');}catch(e){this.toast(e.message);}};
  Component.prototype.demoNext=async function(){try{var a=T.active();if(a&&['driving','near'].includes(a.trip.phase))await T.action('arrive');}catch(e){this.toast(e.message);}};
  Component.prototype.openDestinationSearch=function(){
   var self=this;this.cancelDestinationPreview();this._searchGeneration=(this._searchGeneration||0)+1;clearTimeout(this._searchTimer);
   this.setState({screen:'search',sheet:null,toast:null,meetingPickerOpen:false,search:{purpose:'dest',q:'',st:'idle',sel:null,back:'create'},destinationSelection:null,destinationResults:[],destinationLoading:false,destinationQueried:false,destinationProvider:'saved',destinationNotice:''});
   if(T.app.native&&T.app.nativeStatus.automotive)T.native('navigationPlaces').then(function(r){self._nativePlaces=r.results||[];if(self.state.screen==='search')self.setState({serverTick:Date.now()});}).catch(function(){});
  };
  Component.prototype.cancelDestinationPreview=function(){
   this._destinationPreviewSerial=(this._destinationPreviewSerial||0)+1;
   if(T.app.native&&T.app.nativeStatus.automotive)T.native('cancelNavigationPreview').catch(function(){});
  };
  Component.prototype.chooseDestinationResult=function(point){
   var self=this,input=document.getElementById('placeQ');if(input)input.blur();
   self.setState({destinationSelection:point,destinationNotice:''});
   if(!T.app.native||!T.app.nativeStatus.automotive)return;
   var serial=self._destinationPreviewSerial=(self._destinationPreviewSerial||0)+1;
   T.native('previewNavigationPlace',{place:point}).catch(function(error){
    if(serial!==self._destinationPreviewSerial)return;
    if(self.state.screen==='search')self.setState({destinationNotice:error.message});
    else if(self.state.screen==='create')self.setState({formError:error.message});
   });
  };
  Component.prototype.searchDestinations=async function(){
   var query=(this.state.search.q||'').trim(),generation=this._searchGeneration=(this._searchGeneration||0)+1,self=this;
   if(!query){this.setState({destinationResults:[],destinationLoading:false,destinationQueried:false});return;}
   this.setState({destinationLoading:true,destinationQueried:true,destinationNotice:''});
   try{var result=await T.search(query);if(generation!==self._searchGeneration||self.state.screen!=='search')return;var local=(self._nativePlaces||[]).filter(function(p){return(p.name+(p.address||'')).replace(/\s/g,'').includes(query.replace(/\s/g,''));});self.setState({destinationResults:(result.results||[]).concat(local),destinationProvider:result.provider||'saved',destinationNotice:result.notice||'',destinationLoading:false});}
   catch(e){if(generation===self._searchGeneration&&self.state.screen==='search')self.setState({destinationLoading:false,destinationNotice:e.message});}
  };
  Component.prototype.onQ=function(value){this.cancelDestinationPreview();var self=this;this._searchGeneration=(this._searchGeneration||0)+1;clearTimeout(this._searchTimer);this.setState({search:Object.assign({},this.state.search,{q:value,st:value?'results':'idle',sel:null}),destinationSelection:null,destinationQueried:false,destinationLoading:false,destinationNotice:''});if(value.trim())this._searchTimer=setTimeout(function(){self.searchDestinations();},350);};
  Component.prototype.matches=function(purpose,q){var list=purpose==='pickup'?RESP:RES;var key=(q||'').replace(/\s/g,'');return !key?[]:list.filter(function(p){return(p.name+p.addr).replace(/\s/g,'').includes(key);});};
  Component.prototype.selectMapPlace=function(p){
   var row={id:'map-selected',name:p.name||'지도에서 선택한 장소',addr:p.address||'',pt:geo(p),lat:p.lat,lon:p.lon,tag:'지도 선택',land:p.description||'',walk:0};
   [RES,RESP].forEach(function(list){var i=list.findIndex(function(x){return x.id===row.id;});if(i>=0)list[i]=row;else list.push(row);});
   this.setState({search:Object.assign({},this.state.search,{sel:row.id,st:'results'})});
  };
  window.PoolyRouteForDesign=function(order,lotId,depart,appt){
   var self=T.carComponent,s=self?self.state:{},a=T.active(),draft=s.screen==='create'&&s.form?s.form:null;
   var origin=place(draft?draft.origin_place:a&&a.origin,T.app.places[0]);var dest=place(draft?draft.destination_place:a&&a.destination,T.app.places[1]);
   var selectedLot=(a&&a.parking&&a.parking.id===lotId)?a.parking:T.app.parking.find(function(p){return p.id===lotId;});
   if(lotId==='none')selectedLot=null;
   var parking=selectedLot||{id:'none',name:dest.name,lat:dest.lat,lon:dest.lon,parking_minutes:0,walk_minutes:0};
   var lot={id:parking.id,name:parking.name,pt:geo(parking),park:parking.parking_minutes||0,walk:parking.walk_minutes||0};
   var stops=[{kind:'start',key:'start',name:'내 출발',place:origin.name,time:depart,pt:geo(origin)}],legs=[],prev=origin,t=depart;
   var canonical=T.app.route,minutes=canonical?canonical.legs.map(function(l){return Math.max(1,Math.ceil(l.duration_s/60));}):[];
   (order||[]).forEach(function(id,i){var m=a&&a.members.find(function(x){return x.id===id;});var p=m&&m.pickup;var delta=minutes[i]||Math.max(1,Math.ceil((minuteDistance(prev,p)||10)/4));t+=delta;var f=FR[id]||{name:'일행'};legs.push([geo(prev),geo(p||dest)]);stops.push({kind:'pickup',key:id,id:id,name:f.name+' 픽업',place:p?p.name:'장소 지정 대기',time:t,pt:geo(p||dest),mins:delta});prev=p||dest;});
   var delta=minutes[(order||[]).length]||Math.max(1,Math.ceil((minuteDistance(prev,parking)||10)/4));t+=delta;legs.push([geo(prev),geo(parking)]);
   stops.push({kind:'lot',key:'lot',name:selectedLot?'주차장 도착':'차량 목적지',place:parking.name,time:t,pt:geo(parking),mins:delta});
   var arrive=t+lot.park+lot.walk;stops.push({kind:'dest',key:'dest',name:'약속 장소 도착',place:dest.name,time:arrive,pt:geo(dest)});
   return{stops:stops,legs:legs,walk:[geo(parking),geo(dest)],lot:lot,drive:t-depart,park:lot.park,walkMin:lot.walk,total:arrive-depart,arrive:arrive,arrLot:t,margin:appt-arrive};
  };
  Component.prototype.renderVals=function(){
   T.carComponent=this;var self=this,s=this.state,a=T.active(),core=T.app;
   var v=originalRender.call(this),h=v.h;
   function wrap(fn){return safe(fn,self);}
   function draftPatch(fn){var f=copy(self.state.form);fn(f);self.setState({form:f});}
   var editable=!a||a.is_owner;
   function newDraft(){var tomorrow=new Date(Date.now()+86400000);tomorrow.setHours(14,0,0,0);var dp=null;return{name:'',start:'현재 위치',dest:dp?dp.name:'목적지 선택',origin_place:null,destination_place:dp,time:tomorrow.getHours()*60,date:tomorrow.toISOString().slice(0,10),meeting_at:tomorrow.toISOString(),mode:'car',members:[],pickups:[],status:{},lot:'none'};}
   h.create=function(){self.setState({form:newDraft(),formMode:'create',back:'home',screen:'create',sheet:null,focus:null});};
   h.edit=function(){if(!a||!a.is_owner)return self.toast('약속을 만든 사람만 수정할 수 있어요.');var f=copy(s.trip);f.revision=a.revision;f.meeting_at=a.meeting_at;f.date=a.meeting_at.slice(0,10);self.setState({form:f,formMode:'edit',screen:'create',back:'detail',sheet:null});};
   h.fMeeting=function(e){var value=e.target.value;if(!value)return;draftPatch(function(f){var d=new Date(value);f.meeting_at=d.toISOString();f.date=value.slice(0,10);f.time=d.getHours()*60+d.getMinutes();});};
   h.tMinus=function(){draftPatch(function(f){var d=new Date(f.meeting_at);d.setMinutes(d.getMinutes()-10);f.meeting_at=d.toISOString();f.time=mins(d);});};
   h.tPlus=function(){draftPatch(function(f){var d=new Date(f.meeting_at);d.setMinutes(d.getMinutes()+10);f.meeting_at=d.toISOString();f.time=mins(d);});};
   h.submit=function(){self.submitForm();};
   h.confirmChange=wrap(async function(){
    var c=s.ctx;
    if(c.type==='deleteAppointment'){
     await T.deleteAppointment(c.id);
     self.setState({screen:c.back,sheet:null,ctx:null,histSel:null});
    }else if(c.type==='pickup'){
     var members=a.members.map(function(m){return{id:m.id,mode:m.id===c.id?'walk':m.mode};});
     if(a.trip.phase==='idle')await T.patch({members:members});else await T.action('skip');
     self.setState({sheet:null});
    }
   });
   v.canDeleteAppointment=!!a&&a.is_owner&&!core.nativeStatus.restricted;
   h.deleteAppointment=function(){
    if(!v.canDeleteAppointment)return;
    self.setState({sheet:'change',ctx:{type:'deleteAppointment',id:a.id,back:s.screen==='record'?'history':'home',title:'일정을 삭제할까요?',items:[a.destination.name,'일행의 일정에서도 삭제돼요'],who:[],ok:'삭제',danger:true}});
   };
   h.searchRemote=wrap(async function(){var query=self.state.search.q;self.setState({search:Object.assign({},self.state.search,{st:'loading'})});var r=await T.search(query);r.results.forEach(function(p){var row={id:p.id,name:p.name,addr:p.address,pt:geo(p),lat:p.lat,lon:p.lon,tag:'검색 결과',land:p.address,walk:0};[RES,RESP].forEach(function(list){var i=list.findIndex(function(x){return x.id===p.id;});if(i>=0)list[i]=row;else list.push(row);});});self.setState({search:Object.assign({},self.state.search,{st:r.results.length?'results':'empty'})});if(!r.results.length)self.toast('지도에서 원하는 장소를 누를 수도 있어요.');});
   h.useCur=h.grant=wrap(async function(){var p=await T.locate();self.selectMapPlace(p);});
   h.addSelectedFriends=wrap(async function(){var ids=self.state.addSel.filter(function(id){return core.contacts.some(function(c){return c.id===id;});});if(!ids.length)return;if(self.state.form&&self.state.screen==='create'){draftPatch(function(f){ids.forEach(function(id){if(!f.members.includes(id)){f.members.push(id);f.status[id]='pending';}});});self.setState({sheet:null,addSel:[]});self.toast('일행을 추가했어요.');}else{if(!a)throw new Error('일정을 먼저 만들어 주세요.');var members=a.members.map(function(m){return{id:m.id,mode:m.mode};});ids.forEach(function(id){if(!members.some(function(m){return m.id===id;}))members.push({id:id,mode:'walk'});});await T.patch({members:members});self.setState({sheet:null,addSel:[]});}});
   h.chooseLot=wrap(async function(){var p=core.parking.find(function(p){return p.id===s.lotSel;});if(!p)throw new Error('주차장을 선택해 주세요.');await T.patch({parking:p});self.setState({screen:s.lotBack||'detail'});self.toast('주차장을 경로에 반영했어요.');});
   h.findParking=function(){self.openSearch('parking',null);};
   h.demo=function(){self.demoNext();};
   h.navigate=wrap(async function(){self.setState({screen:'moving',guidanceNotice:null,sheet:null});await T.navigate();});
   h.openNavigation=wrap(async function(){var result=await T.native('openNavigation');self.toast(result.message);});
   h.readStatus=wrap(async function(){await window.PoolyPleos.speak();});
   h.voiceCommand=wrap(async function(){await window.PoolyPleos.listen();});
   h.toggleVoice=wrap(async function(){await window.PoolyPleos.toggle();self.setState({serverTick:Date.now()});});
   h.checkVehicle=wrap(async function(){await T.native('vehicle');self.toast('차량 데이터 확인을 요청했어요.');});
   h.stopShare=wrap(async function(){await T.member({share:false});self.toast('위치 공유를 중지했어요.');});
   h.resumeShare=wrap(async function(){await T.member({share:true});await T.startTracking();self.toast('이번 이동의 위치 공유를 설정했어요.');});
   h.allowNoti=wrap(async function(){if(core.native){await T.native('notifications');self.toast('기기의 알림 권한 상태를 확인했어요.');}else{if(!('Notification'in window))throw new Error('차량 앱에서 알림을 받을 수 있어요.');var permission=await Notification.requestPermission();self.toast(permission==='granted'?'알림을 허용했어요.':'알림 권한을 허용하지 않았어요.');}});
   h.allowLocation=wrap(async function(){await T.locate();self.toast('위치를 확인했어요.');});
   h.connDone=wrap(async function(){await T.init();self.setState({sheet:null});});
   h.locate=function(){if(window.PoolyMaps)window.PoolyMaps.locate();};h.overview=function(){if(window.PoolyMaps)window.PoolyMaps.fit();};
   h.histOpen=function(){var rows=core.appointments.filter(function(x){return x.trip.phase==='finished';}).sort(function(a,b){return Date.parse(b.trip.finished_at)-Date.parse(a.trip.finished_at);}),selected=rows.find(function(x){return x.id===s.histSel;})||rows[0];if(selected){T.select(selected.id);self.go('record');}else self.toast('아직 완료한 주행 기록이 없어요.');};
   v.todayLabel=T.date(new Date());v.busy=core.busy;v.loading=core.loading;v.offline=core.offline;v.connLabel=core.offline?'연결 확인':'기기·권한';v.editable=editable;
   v.friends.forEach(function(row){if(!a||!a.members.some(function(m){return m.id===row.id;})){row.sub='추가한 일행';row.hasB=false;}});v.add.list.forEach(function(row){row.sub=row.dis?'이미 일행이에요':'초대할 일행';});v.shareNavLabel=a&&T.me(a)&&T.me(a).share?'위치 공유 중':'위치 공유 설정';
   if(s.form&&s.form.mode==='walk'){var travel=minuteDistance(s.form.origin_place,s.form.destination_place);v.fs={total:travel?travel+'분':'계산 대기',detail:'직선거리 기반 도보 추정',dep:travel?hm(s.form.time-travel-5):'—',arr:hm(s.form.time-5),mg:margin(5),note:'실제 이동 시간과 다를 수 있어요.'};}v.form.meeting=s.form&&s.form.meeting_at?new Date(new Date(s.form.meeting_at).getTime()-new Date(s.form.meeting_at).getTimezoneOffset()*60000).toISOString().slice(0,16):'';
   v.cards=core.appointments.filter(isUpcoming).map(function(x){
    var timing=self.timingFor(x),members=x.members.filter(function(m){return m.status!=='declined';});
    var date=new Date(x.meeting_at);
    return {id:x.id,dest:x.destination.name,dateLabel:date.getFullYear()+'. '+(date.getMonth()+1)+'. '+date.getDate()+'.',timeLabel:T.time(date),
     duration:formatDuration(timing.seconds),durationSource:timing.source==='pleos'?'':timing.source,
     peopleLabel:members.length?members[0].name+'님'+(members.length>1?' 외 '+(members.length-1)+'명':''):'나 혼자 이동',
     avs:members.slice(0,3).map(function(m){return {name:m.name,photo:photo(m.name)};}),
     departDisabled:!x.is_driver||x.trip.phase==='parked',
     departLabel:x.trip.phase==='parked'?'도착':x.trip.phase==='idle'?'출발하기':guidancePaused(x)?'안내 다시 시작':'내비로 이동',
     depart:wrap(async function(){
      T.select(x.id);
      if(x.trip.phase==='idle')await self.startTrip();
      else if(guidancePaused(x)||core.nativeStatus.navigationAid!==x.id)await T.navigate();
      else await T.native('openNavigation');
     }),
     act:function(){T.select(x.id);self.setState({actionError:''});self.go('detail');}};
   });
   v.hasEvents=v.cards.length>0;v.noEvents=!v.hasEvents;
   if(a){
    var mine=T.me(a),r=core.route;
    v.tripOn=!['cancelled','finished'].includes(a.trip.phase)&&!guidancePaused(a);
    v.detail.lateHint=r?'권장 출발 '+T.time(r.recommended_departure)+' · 추정 시간에 여유 5분을 더했어요':'';v.detail.when=T.date(a.meeting_at)+' '+T.time(a.meeting_at);v.detail.resp='참가 '+(a.members.length+1)+'명 · 수락 '+a.members.filter(function(m){return m.status==='accepted';}).length+' · 대기 '+a.members.filter(function(m){return m.status==='pending';}).length;
    v.seoul.origin=a.origin?a.origin.name:'출발지';v.seoul.name=a.name;
    if(r){v.detail.total=r.total_minutes+'분';v.detail.pw=(r.parking_minutes+r.walk_minutes)+'분';v.detail.dep=T.time(r.recommended_departure);v.detail.arr=T.time(new Date(new Date(r.recommended_departure).getTime()+r.total_minutes*60000));v.detail.mg=margin(Math.round((new Date(a.meeting_at).getTime()-new Date(r.arrival_target||a.meeting_at).getTime())/60000));v.detail.late=new Date(r.arrival_target||a.meeting_at)>new Date(a.meeting_at);v.routeNotice=r.notice;}
    else v.routeNotice='경로를 확인하고 있어요';
    v.friends.forEach(function(row){var m=a.members.find(function(x){return x.id===row.id;});if(m){row.badge=T.status(m,a);row.bc='bdg '+(m.status==='pending'?'b-warn':m.ready==='waiting'||m.ready==='ready'||m.boarded_at?'b-ok':'b-act');row.hasB=true;row.sub=a.name+' · '+(m.mode==='pickup'?'픽업':m.mode==='car'?'내 차로':'걸어서');}});
    v.fMembers.forEach(function(row){var m=a.members.find(function(x){return x.id===row.id;});if(m)row.plc=m.pickup?m.pickup.name:'장소 지정 대기';});
    v.stops.forEach(function(row){var m=a.members.find(function(x){return x.id===row.key;});if(m){row.ready=T.status(m,a);row.place=m.pickup?m.pickup.name:'상대가 장소를 지정해야 해요';row.time=m.pickup&&m.status==='accepted'?row.time:'—';row.up=wrap(async function(){var order=a.pickup_order.slice(),i=order.indexOf(m.id);if(i>0){[order[i-1],order[i]]=[order[i],order[i-1]];await T.patch({pickup_order:order});}});row.down=wrap(async function(){var order=a.pickup_order.slice(),i=order.indexOf(m.id);if(i<order.length-1){[order[i+1],order[i]]=[order[i],order[i+1]];await T.patch({pickup_order:order});}});}});
    v.selfList=a.members.filter(function(m){return m.mode!=='pickup';}).map(function(m){return{name:m.name,ini:info(m).ini,av:T.color(m.name),sub:m.mode==='car'?'내 차로 이동':'걸어서 이동',eta:m.started_at?'출발 '+T.time(m.started_at):'출발 전',st:T.status(m,a),stCls:'bdg b-act'};});v.detail.hasSelf=v.selfList.length>0;
    v.add.list.forEach(function(row){row.sub=row.dis?'이미 일행이에요':'초대할 일행';});
    if(v.fd&&s.ctx){
     var friend=a.members.find(function(m){return m.id===s.ctx;});
     v.fd.place=friend&&friend.pickup?friend.pickup.name:'장소 지정 대기';
     v.fd.invite=wrap(async function(){
      var members=a.members.map(function(m){return {id:m.id,mode:m.mode};});
      members.push({id:s.ctx,mode:'walk'});
      await T.patch({members:members});
     });
    }
    v.lots.forEach(function(l){l.fee='요금 확인 필요';l.base='현장 요금과 운영시간을 확인해 주세요';l.avail='실시간 빈자리 정보 없음';l.ac='bdg';l.hours='운영 정보 확인 필요';});
    var current=a.members.find(function(m){return m.id===(a.trip.active_pickups||[])[a.trip.index];});
    if(current){v.moving.pst=T.status(current,a);v.moving.pstCls='bdg '+(['ready','waiting'].includes(current.ready)?'b-ok':'b-warn');v.moving.canBoard=a.trip.phase==='arrived'&&['ready','waiting'].includes(current.ready);v.moving.notBoard=!v.moving.canBoard;v.moving.stale=!!current.location&&Date.now()-new Date(current.location.updated_at).getTime()>60000;v.moving.land=current.pickup?current.pickup.address:'';v.moving.desc=current.pickup?current.pickup.description:'';}
    var remaining=r?Math.max(0,r.total_minutes-Math.floor((Date.now()-new Date(a.trip.started_at||Date.now()).getTime())/60000)):null;
    v.moving.now='지금 '+T.time(new Date());v.moving.arr=r&&a.trip.started_at?T.time(new Date(new Date(a.trip.started_at).getTime()+r.total_minutes*60000)):v.detail.arr;v.moving.mg=margin(r&&a.trip.started_at?Math.round((new Date(a.meeting_at)-new Date(a.trip.started_at))/60000-r.total_minutes):5);
    v.moving.nextEta=current&&r&&a.trip.started_at?T.time(new Date(new Date(a.trip.started_at).getTime()+r.legs.slice(0,a.trip.index+1).reduce(function(n,l){return n+l.duration_s;},0)*1000)):v.moving.arr;v.moving.walkTo='참가자가 지정한 픽업 지점';if(v.moving.tl&&v.moving.tl.length)v.moving.tl[0].label=(a.origin?a.origin.name:'출발지')+' 출발';if(!v.moving.isArrived)v.moving.bigV=remaining===null?'확인 중':remaining+'분';v.moving.bigK=v.moving.isArrived?'픽업 장소':'약속 장소까지 예상';
    v.moving.demoLabel='픽업 장소 도착 확인';
    var ns=core.nativeStatus,eta=ns.navigationEta;
    if(ns.navigationAid===a.id&&window.PoolyPleos.fresh(eta)&&ns.navigation==='ready'){
      var end=new Date(eta.updatedAt+eta.seconds*1000),isFinal=['dest','lot'].includes(eta.stopId);
      v.moving.bigK=isFinal?'차량 내비 · 목적지까지':'차량 내비 · 다음 픽업까지';
      if(!v.moving.isArrived)v.moving.bigV=Math.max(0,Math.ceil((end-Date.now())/60000))+'분';
      if(isFinal)v.moving.arr=T.time(end);else v.moving.nextEta=T.time(end);
      var wp=ns.navigationWaypoints&&current&&current.pickup&&ns.navigationWaypoints.find(function(p){return Math.abs(p.lat-current.pickup.lat)<.002&&Math.abs(p.lon-current.pickup.lon)<.002;});if(wp&&window.PoolyPleos.fresh(wp)){v.moving.nextEta=T.time(new Date(wp.updatedAt+wp.seconds*1000));v.moving.nextK='차량 내비 · 픽업 예상';}
    }
    v.rec.km=a.track&&a.track.length>1?(a.distance_m/1000).toFixed(1):'—';var plan=a.trip.planned;v.rec.expectedStart=plan?T.time(plan.departure_at):'—';v.rec.expectedParked=plan?T.time(plan.parking_at):'—';v.rec.expectedArrival=plan?T.time(plan.arrival_at):'—';v.rec.plannedKm=plan?(plan.distance_m/1000).toFixed(1)+'km (추정)':'—';v.rec.plannedMinutes=plan?plan.total_minutes+'분 (추정)':'—';v.rec.parked=T.time(a.trip.parked_at);v.rec.duration=a.trip.started_at?Math.max(0,Math.floor((new Date(a.trip.finished_at||Date.now())-new Date(a.trip.started_at))/60000))+'분':'—';v.rec.started=T.time(a.trip.started_at);v.rec.ended=T.time(a.trip.finished_at);v.rec.hasTrack=!!(a.track&&a.track.length>1);
    v.rec.pickups.forEach(function(p,i){var m=a.members.find(function(x){return x.id===a.trip.boarded[i];});p.time=m?T.time(m.boarded_at):'—';});
    v.shr.on=!!mine.share;v.shr.off=!mine.share;v.shr.status=mine.share?'이번 이동에서 위치를 공유해요':'위치 공유가 꺼져 있어요';v.shr.targets=[];
   }
   var finished=core.appointments.filter(function(x){return x.trip.phase==='finished';}).sort(function(a,b){return Date.parse(b.trip.finished_at)-Date.parse(a.trip.finished_at);});
   v.hist=finished.map(function(x){return{id:x.id,date:T.date(x.meeting_at),name:x.name,km:(x.distance_m/1000).toFixed(1)+'km',min:Math.floor((new Date(x.trip.finished_at)-new Date(x.trip.started_at))/60000)+'분 주행',people:(x.members.length+1)+'명',route:x.destination.name,time:T.time(x.trip.started_at)+'–'+T.time(x.trip.finished_at),cls:s.histSel===x.id?'stp on':'stp',pick:function(){self.setState({histSel:x.id});}};});
   v.hsel=v.hist.find(function(x){return x.id===s.histSel;})||v.hist[0]||{name:'아직 주행 기록이 없어요',date:'',km:'—',min:'',people:'',route:'이동을 완료하면 여기에 저장돼요',time:''};v.hasHistory=v.hist.length>0;
   v.cn={noti:!!core.nativeStatus.notifications,notNoti:!core.nativeStatus.notifications,location:!!core.nativeStatus.locationPermission,user:core.user?core.user.name:'연결 중',server:core.offline?'연결 끊김':'연결됨'};
   var ns=core.nativeStatus,labels={connected:'연결됨',ready:'사용 가능',receiving:'수신 중',waiting:'위치 대기',requested:'요청 중',speaking:'안내 중',listening:'듣는 중',unavailable:'연결 확인 필요',unsupported:'이 기기에서 미지원',partial:'일부 데이터 미제공',searching:'경로 탐색 중','permission-needed':'차량 위치 권한 필요',cancelled:'안내 취소',timeout:'응답 확인 필요',error:'연결 확인 필요',external:'다른 경로 안내 중'};
   v.cn.isCar=core.native&&ns.automotive;v.cn.navi=labels[ns.navigation]||'연결 대기';v.cn.tts=labels[ns.tts]||'연결 대기';v.cn.stt=labels[ns.stt]||'연결 대기';v.cn.vehicle=labels[ns.vehicle]||'연결 대기';v.cn.fused=labels[ns.fusedLocation]||(ns.locationSource==='android-fallback'?'기기 위치로 대체':'위치 사용 시 연결');v.cn.voice=window.PoolyPleos.enabled()?'상태 음성 안내 끄기':'상태 음성 안내 켜기';v.cn.noTts=ns.tts!=='ready'&&ns.tts!=='speaking';v.cn.noStt=ns.stt!=='ready';
   if(v.search.hasSel){var selected=(s.search.purpose==='pickup'?RESP:RES).find(function(x){return x.id===s.search.sel;});v.search.choose=wrap(async function(){if(!selected)return;var p={name:selected.name,address:selected.addr,lat:selected.lat,lon:selected.lon,description:self.state.search.desc||''};if(!Number.isFinite(p.lat)||!Number.isFinite(p.lon))throw new Error('지도에서 위치를 다시 선택해 주세요.');var purpose=self.state.search.purpose;if(purpose==='start'||purpose==='dest'){draftPatch(function(f){f[purpose]=p.name;f[purpose==='start'?'origin_place':'destination_place']=p;});self.go('create');}else if(purpose==='parking'){await T.patch({parking:Object.assign({},p,{id:'custom',parking_minutes:5,walk_minutes:minuteDistance(p,a.destination)})});self.go('detail');}else{if(!a||!a.is_owner)throw new Error('픽업 장소는 초대받은 휴대폰에서 지정해 주세요.');var members=a.members.map(function(m){return{id:m.id,mode:m.mode};});self.toast('일행에게 장소 변경을 요청해 주세요. 픽업 장소는 일행의 휴대폰에서 지정할 수 있어요.');}});}
   if(window.PoolyMaps)window.PoolyMaps.context({screen:s.screen,night:v.isNight,component:self,selection:v.search.sel});
   // November 9 user layouts. The backend remains the source of appointments.
   v.friends.forEach(function(f){f.photo=photo(f.name);});
   var previousCreate=h.create,previousEdit=h.edit,previousCancel=h.cancelForm;
   h.create=function(){if(core.nativeStatus.restricted)return self.toast('주차한 뒤 일정을 만들어 주세요.');self._draftSerial=(self._draftSerial||0)+1;var serial=self._draftSerial;previousCreate();self.setState({meetingPickerOpen:false,formSaving:false,formError:'',nativeDestinationPending:null,destinationSearchOpen:false});if(core.nativeStatus.locationPermission&&core.nativeStatus.fusedPermission)T.locate().then(function(p){if(self._draftSerial!==serial||!self.state.form)return;var f=copy(self.state.form);f.origin_place=p;f.start='현재 위치';self.setState({form:f});}).catch(function(){});panel('create',self);};
   h.edit=function(){if(core.nativeStatus.restricted)return self.toast('주차한 뒤 일정을 수정해 주세요.');if(!a||!a.is_owner)return self.toast('약속을 만든 사람만 수정할 수 있어요.');self._draftSerial=(self._draftSerial||0)+1;previousEdit();self.setState({meetingPickerOpen:false,formSaving:false,formError:'',nativeDestinationPending:null,destinationSearchOpen:false});panel('create',self);};
   h.cancelForm=function(){self.cancelDestinationPreview();if(self.state.nativeDestinationPending)T.native('cancelDestinationSelection').catch(function(){});self.setState({nativeDestinationPending:null});self._draftSerial=(self._draftSerial||0)+1;previousCancel();if(core.nativeStatus.navigationPanel)T.native('closeNavigationPanel',{returnToApp:true}).catch(function(e){self.toast(e.message);});};
   v.fMembers.forEach(function(u){u.photo=photo(u.name);u.portraitClass='member-photo-button'+(u.isPk?' picked':'');u.pickupAria=u.name+' 픽업 '+(u.isPk?'해제':'선택');});
   v.pickupPeople=(s.form?s.form.pickups:[]).map(function(id,index){var member=v.fMembers.find(function(m){return m.id===id;});return{id:id,order:index+1,name:member?member.name:'일행'};});
   v.noPickupPeople=!v.pickupPeople.length;
   h.fDest=function(){self.openDestinationSearch();};
   h.searchBack=function(){self.cancelDestinationPreview();self._searchGeneration=(self._searchGeneration||0)+1;clearTimeout(self._searchTimer);self.setState({screen:'create',destinationSelection:null});};
   h.searchRemote=wrap(async function(){await self.searchDestinations();});
   h.destinationSearchKey=function(e){if(e.key==='Enter'){e.preventDefault();h.searchRemote();}};
   h.chooseDestination=function(){
    var chosen=self.state.destinationSelection;if(!chosen)return;
    draftPatch(function(f){f.dest=chosen.name;f.destination_place=chosen;});
    self.setState({screen:'create',destinationSelection:null,formError:''});
   };
   var query=(s.search&&s.search.q||'').trim(),selection=s.destinationSelection,places=s.destinationResults||[],nativePlaces=self._nativePlaces||[];
   if(!query)places=core.places.concat(nativePlaces);else if(!s.destinationQueried)places=core.places.concat(nativePlaces).filter(function(p){return(p.name+(p.address||'')).replace(/\s/g,'').includes(query.replace(/\s/g,''));});
   var seen={};places=places.filter(function(p){var key=p.name+':'+Number(p.lat).toFixed(5)+':'+Number(p.lon).toFixed(5);if(seen[key])return false;seen[key]=true;return true;});
   v.destinationSearch={q:s.search&&s.search.q||'',loading:!!s.destinationLoading,notice:s.destinationNotice||'',empty:!s.destinationLoading&&!places.length,emptyMessage:['vworld','kakao'].includes(s.destinationProvider)?'다른 장소명이나 주소로 검색해 보세요.':'현재는 저장된 장소와 내비 최근 목적지에서 검색할 수 있어요.',rows:places.map(function(p){return{name:p.name,address:p.address||'',cls:'place-result'+(selection&&selection.name===p.name&&selection.lat===p.lat&&selection.lon===p.lon?' selected':''),check:selection&&selection.name===p.name&&selection.lat===p.lat&&selection.lon===p.lon?'✓':'',aria:p.name+' 선택',choose:function(){self.chooseDestinationResult(p);}};}),hasSelection:!!selection,selectedName:selection?selection.name:'',selectedAddress:selection?selection.address:'',disabled:!selection};
   h.addFriend=h.addFriends=function(){self.setState({sheet:'addFriends',addSel:[]});};
   var members=s.form?s.form.members:a?a.members.map(function(m){return m.id;}):[];
   v.friendPicker={
    empty:!core.contacts.length,disabled:!s.addSel.length,cta:s.addSel.length?s.addSel.length+'명 추가':'친구를 선택하세요',
    rows:core.contacts.map(function(friend){
     var included=members.includes(friend.id),selected=s.addSel.includes(friend.id);
     return {name:friend.name,photo:photo(friend.name),selected:included||selected,cls:'friend-choice'+(included||selected?' selected':''),
      aria:friend.name+(included?' 이미 추가됨':' 선택'),sub:included?'이미 일행이에요':'',check:included||selected?'✓':'',
      toggle:function(){
       if(included)return;
       var ids=self.state.addSel.slice(),i=ids.indexOf(friend.id);
       if(i>=0)ids.splice(i,1);else ids.push(friend.id);
       self.setState({addSel:ids});
      }};
    })};
   v.friends=v.friends.filter(function(row){return core.contacts.some(function(c){return c.id===row.id;});});
   v.noHistory=!v.hasHistory;
   var phase=a?a.trip.phase:'idle',order=a?(phase==='idle'?a.pickup_order:a.trip.active_pickups||[]):[];
   var timing=a?self.timingFor(a):{seconds:null,arrival:null,source:'',pickups:{}};
   var journeyStops=[];
   if(a){
    var departure=a.trip.started_at||new Date(),source=a.trip.started_at&&a.origin?a.origin.name:'현재 위치';
    journeyStops.push({n:'출발',name:'출발지',place:source,time:T.time(departure),pickup:false});
    order.forEach(function(id,i){
     var member=a.members.find(function(m){return m.id===id&&m.status!=='declined';});
     if(!member)return;
     var row=v.stops.find(function(r){return r.key===id;});
     var time=member.boarded_at||timing.pickups[id];
     journeyStops.push({n:String(i+1),name:member.name+' 픽업',place:member.pickup?member.pickup.name:'픽업 위치 공유 대기',
      status:member.boarded_at?'탑승 완료':'',time:time?T.time(time):'—',pickup:true,
      up:row?row.up:function(){},down:row?row.down:function(){},upDisabled:phase!=='idle'||i===0,
      downDisabled:phase!=='idle'||i===order.length-1,upAria:member.name+' 픽업 순서 앞으로',downAria:member.name+' 픽업 순서 뒤로'});
    });
    journeyStops.push({n:'도착',name:'목적지',place:a.destination.name,time:timing.arrival?T.time(timing.arrival):'—',pickup:false});
   }
   v.journey={destination:a?a.destination.name:'여정 확인',meetingTime:a?T.time(a.meeting_at):'—',stops:journeyStops,
    duration:formatDuration(timing.seconds),arrival:timing.arrival?T.time(timing.arrival):'—',source:timing.source==='pleos'?'':timing.source,
    durationLabel:phase==='idle'?'예상 소요 시간':'남은 소요 시간',notEditable:!a||!a.is_owner||phase!=='idle',
    pickupInProgress:!!a&&a.is_driver&&['driving','near','arrived'].includes(phase),
    canConfirmPickupArrival:['driving','near'].includes(phase),
    canFinishArrival:!!a&&a.is_driver&&!core.nativeStatus.restricted&&['destination','parked'].includes(phase),
    navigationDisabled:!a||!a.is_driver||['finished','cancelled'].includes(phase),
    navigationLabel:phase==='idle'?'출발하기':guidancePaused(a)?'안내 다시 시작':'내비 열기'};
   h.finishArrival=wrap(async function(){
    if(!v.journey.canFinishArrival||core.busy)return;
    self.setState({actionError:''});
    await T.finishArrival(a.id);
    if(T.active()?.id===a.id&&self.state.screen==='detail')self.go('record');
   });
   h.journeyNavigate=wrap(async function(){
    self.setState({actionError:''});
    if(phase==='idle')await self.startTrip();
    else if(guidancePaused(a)||core.nativeStatus.navigationAid!==a.id)await T.navigate();
    else await T.native('openNavigation');
   });
   v.form.meetingLabel=s.form?new Intl.DateTimeFormat('ko-KR',{year:'numeric',month:'numeric',day:'numeric'}).format(new Date(s.form.meeting_at))+' '+new Intl.DateTimeFormat('ko-KR',{hour:'numeric',minute:'2-digit',hour12:true}).format(new Date(s.form.meeting_at)):'';
   var meeting=s.form?new Date(s.form.meeting_at):new Date(),month=new Date(s.meetingPickerMonth||new Date(meeting.getFullYear(),meeting.getMonth(),1).getTime()),days=[];
   for(var blank=0;blank<month.getDay();blank++)days.push({text:'',blank:true,cls:'calendar-day',label:''});
   for(var day=1;day<=new Date(month.getFullYear(),month.getMonth()+1,0).getDate();day++)(function(n){days.push({text:String(n),blank:false,label:(month.getMonth()+1)+'월 '+n+'일',cls:'calendar-day'+(meeting.getFullYear()===month.getFullYear()&&meeting.getMonth()===month.getMonth()&&meeting.getDate()===n?' selected':''),choose:function(){draftPatch(function(f){var d=new Date(f.meeting_at);d.setFullYear(month.getFullYear(),month.getMonth(),n);f.meeting_at=d.toISOString();f.date=d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');f.time=d.getHours()*60+d.getMinutes();});}});})(day);
   v.meetingPicker={open:!!s.form&&!!s.meetingPickerOpen,monthLabel:month.getFullYear()+'년 '+(month.getMonth()+1)+'월',days:days,hour:String(meeting.getHours()).padStart(2,'0'),minute:String(meeting.getMinutes()).padStart(2,'0')};
   h.editMeeting=function(){self.setState({meetingPickerOpen:true,meetingPickerMonth:new Date(meeting.getFullYear(),meeting.getMonth(),1).getTime()});};h.closeMeeting=function(){self.setState({meetingPickerOpen:false});};
   h.previousMonth=function(){self.setState({meetingPickerMonth:new Date(month.getFullYear(),month.getMonth()-1,1).getTime()});};h.nextMonth=function(){self.setState({meetingPickerMonth:new Date(month.getFullYear(),month.getMonth()+1,1).getTime()});};
   function shiftMeeting(minutes){draftPatch(function(f){var d=new Date(f.meeting_at);d.setMinutes(d.getMinutes()+minutes);f.meeting_at=d.toISOString();f.time=d.getHours()*60+d.getMinutes();});}
   h.hourMinus=function(){shiftMeeting(-60);};h.hourPlus=function(){shiftMeeting(60);};h.minuteMinus=function(){shiftMeeting(-5);};h.minutePlus=function(){shiftMeeting(5);};
   v.draftEstimate='';
   var currentPickup=a&&a.members.find(function(m){return m.id===(a.trip.active_pickups||[])[a.trip.index];});
   var nextSeconds=null,ns2=core.nativeStatus;
   if(a&&ns2.navigationAid===a.id&&ns2.navigation==='ready'){
    var nextEta=ns2.navigationEta;
    if(currentPickup&&currentPickup.pickup){var waypoint=(ns2.navigationWaypoints||[]).find(function(p){return p.stopId===currentPickup.id||Math.abs(p.lat-currentPickup.pickup.lat)<.002&&Math.abs(p.lon-currentPickup.pickup.lon)<.002;});if(waypoint)nextEta=waypoint;else if(nextEta&&nextEta.stopId!==currentPickup.id)nextEta=null;}
    if(window.PoolyPleos.fresh(nextEta))nextSeconds=Math.max(0,Math.ceil((nextEta.updatedAt+nextEta.seconds*1000-Date.now())/60000));
   }
   v.showGuidanceNotice=!!s.guidanceNotice;
   v.pickupPanel=Object.assign({remaining:nextSeconds===null?'확인 중':'약 '+nextSeconds+'분'},s.guidanceNotice);
   v.pickupPanel.canCall=!!currentPickup;
   h.dismissPanel=function(){if(core.nativeStatus.navigationPanel)T.native('closeNavigationPanel',{returnToApp:false}).catch(function(e){self.toast(e.message);});else self.go('detail');};
   h.returnJourney=function(){self.setState({screen:'detail',sheet:null});if(core.nativeStatus.navigationPanel)T.native('closeNavigationPanel',{returnToApp:true}).catch(function(e){self.toast(e.message);});};
   h.callPickup=wrap(async function(){if(!core.native)return self.toast('차량 전화 앱에서 연락할 수 있어요.');var result=await T.native('dialPickup',{number:currentPickup&&currentPickup.phone||''});if(!result.numberKnown)self.toast('등록된 전화번호가 없어 차량 전화 앱을 열었어요.');});
   if(s.search&&s.search.purpose==='dest')v.search.title='목적지 선택';
   v.formError=s.formError||'';
   v.actionError=s.actionError||'';
   v.busy=core.busy||!!s.formSaving;
   if(core.nativeStatus.restricted){['create','edit','addFriend','addFriends','fStart','fDest','openParking'].forEach(function(key){if(h[key])h[key]=function(){self.toast('차량을 주차한 뒤 설정해 주세요.');};});}
   return v;
  };
 };
})();

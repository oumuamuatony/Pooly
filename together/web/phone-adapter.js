(function(){
 'use strict';var T=window.Together;
 function safe(fn,c){return async function(){try{return await fn.apply(this,arguments);}catch(e){c.toast(e.message);}};}
 function ini(name){return name.length===3?name[1]:name[0];}
 function geo(p){return p?[230+(p.lon-127.0374)*13350,430-(p.lat-37.5444)*12000]:[230,430];}
 function duration(p,q){return p&&q?Math.max(1,Math.ceil(Math.hypot((p.lon-q.lon)*88000,(p.lat-q.lat)*111000)/75*1.2)):null;}
 window.TogetherPhone=function(Component,env){
  var PER=env.PER,SRC=env.SRC,render=Component.prototype.renderVals;SRC.start.splice(0,SRC.start.length);SRC.pickup.splice(0,SRC.pickup.length);
  function catalog(){T.app.places.forEach(function(p){var row={id:p.id,name:p.name,addr:p.address,lat:p.lat,lon:p.lon,tag:'저장한 장소',land:p.description||p.address,walk:0};['start','pickup'].forEach(function(k){var i=SRC[k].findIndex(function(x){return x.id===p.id;});if(i>=0)SRC[k][i]=row;else SRC[k].push(row);});});}
  Component.prototype.componentDidMount=function(){
   var self=this;T.phoneComponent=this;
   this._off=T.on(function(){self.applyServer();});
   this._notice=function(e){self.toast(e.detail.title+' · '+e.detail.body);};
   window.addEventListener('together:notice',this._notice);this._error=function(e){self.toast(e.detail);};window.addEventListener('together:error',this._error);this._back=function(){if(self.state.sheet)self.setState({sheet:null});else if(self.state.screen==='search')self.setState({screen:self.state.searchBack||'invite'});else self.setState({screen:T.me(T.active())&&T.me(T.active()).mode==='pickup'?'pickup':'status'});};window.addEventListener('together:back',this._back);
   T.init().then(function(){self.applyServer();}).catch(function(e){T.app.offline=true;self.toast('서버 연결을 확인해 주세요.');self.setState({serverTick:Date.now()});});
  };
  var oldUnmount=Component.prototype.componentWillUnmount;
  Component.prototype.componentWillUnmount=function(){if(this._off)this._off();window.removeEventListener('together:error',this._error);window.removeEventListener('together:back',this._back);window.removeEventListener('together:notice',this._notice);if(oldUnmount)oldUnmount.call(this);};
  Component.prototype.applyServer=function(){
   catalog();var a=T.active(),m=T.me(a),inv=T.app.invitation,patch={serverTick:Date.now()};
   if(a&&m){
    var per=m.mode==='pickup'?'jiyeon':'mina';PER[per]={id:per,name:m.name,ini:ini(m.name),av:T.color(m.name),start:m.origin?m.origin.name:'출발지 선택',mode:m.mode,walk:m.travel_minutes||duration(m.origin,a.destination)||0,pt:geo(m.origin),tf:'translate('+geo(m.origin).join(' ')+')',home:'',place:m.pickup?Object.assign({},m.pickup,{land:m.pickup.description||m.pickup.address,walk:duration(m.origin,m.pickup)||0}):null};
    patch.per=per;patch.mode=m.mode;patch.start=m.origin?m.origin.name:'출발지 선택';patch.origin_place=m.origin;patch.place=PER[per].place;patch.inv='joined';patch.myPk={preparing:'prep',ready:'prep',walking:'moving',waiting:'waiting'}[m.ready]||'prep';patch.share=Object.assign({},this.state.share,{on:m.share});patch.arrived=!!m.arrived_at;patch.homeDone=!!m.home_at;
    if(!this._seenJoined){patch.screen=m.mode==='pickup'?'pickup':'status';this._seenJoined=true;}
    if(a.trip.phase==='cancelled'){patch.screen='invite';patch.inv='cancelled';}
    if(m.boarded_at)patch.pstage=3;else if(a.trip.phase==='arrived')patch.pstage=2;else if(a.trip.phase==='near')patch.pstage=1;else patch.pstage=0;
   }else if(inv&&!this._initializedInvite){
    var per=inv.member.mode==='pickup'?'jiyeon':'mina';PER[per]=Object.assign({},PER[per],{name:inv.member.name,ini:ini(inv.member.name),av:T.color(inv.member.name)});
    patch.per=per;patch.mode=inv.member.mode||'pickup';patch.screen='invite';patch.inv='default';patch.place=null;patch.origin_place=null;patch.start='출발지 선택';patch.share=Object.assign({},this.state.share,{on:false});this._initializedInvite=true;
   }
   if(T.app.inviteError&&!a){patch.inv='expired';patch.screen='invite';}
   this.setState(patch);
  };
  Component.prototype.matches=function(purpose,q){var key=(q||'').replace(/\s/g,'');return !key?[]:SRC[purpose].filter(function(p){return(p.name+p.addr+p.tag).replace(/\s/g,'').includes(key);});};
  Component.prototype.selectMapPlace=function(p){
   var row={id:'map-selected',name:p.name||'지도에서 선택한 장소',addr:p.address||'',lat:p.lat,lon:p.lon,tag:'지도 선택',land:p.description||'',walk:0};
   ['start','pickup'].forEach(function(k){var i=SRC[k].findIndex(function(x){return x.id===row.id;});if(i>=0)SRC[k][i]=row;else SRC[k].push(row);});
   this.setState({search:Object.assign({},this.state.search,{sel:row.id,st:'results'})});
  };
  Component.prototype.renderVals=function(){
   var self=this,s=this.state,a=T.active(),m=T.me(a),core=T.app,inv=core.invitation;
   var v=render.call(this),h=v.h,driver=T.driver(a),others=a?[a.owner].concat(a.members).filter(function(p){return p.id!==a.me_id&&p.status==='accepted';}):[];
   function wrap(fn){return safe(fn,self);}
   function patch(data){return T.member(data);}
   function startSearch(purpose){self.setState({searchBack:self.state.screen,screen:'search',search:{purpose:purpose,q:'',st:'idle',sel:null,desc:purpose==='pickup'&&self.state.place?self.state.place.description||'':''}});}
   h.back=function(){self.setState({screen:self.state.searchBack||'invite'});};
   h.pickStart=function(){startSearch('start');};h.pickPlace=h.changePlace=function(){startSearch('pickup');};
   h.join=h.enter=h.allow=wrap(async function(){var data={mode:self.state.mode,origin:self.state.origin_place,pickup:self.state.mode==='pickup'?self.state.place:null,share:!!self.state.share.on};
    if(!data.origin)throw new Error('내 출발지를 먼저 선택해 주세요.');if(data.mode==='pickup'&&!data.pickup)throw new Error('픽업 장소를 먼저 지정해 주세요.');
    data.travel_minutes=Math.max(1,Math.ceil((duration(data.origin,inv?inv.destination:a.destination)||30)/(data.mode==='car'?4:1)));
    if(m)await T.member(data);else await T.join(data);
    self.setState({screen:data.mode==='pickup'?'pickup':'status',sheet:null});self.toast('참가 설정을 저장했어요.');
   });
   h.decline=wrap(async function(){await T.join({decline:true});self.setState({inv:'declined'});self.toast('초대를 거절했어요.');});
   h.undoDecline=function(){self.setState({inv:'default'});};
   h.toStatus=function(){self.setState({screen:m&&m.mode==='pickup'?'pickup':'status'});};
   h.go=wrap(async function(){await patch({action:'depart'});await T.startTracking();self.toast('출발을 일행에게 알렸어요.');});
   h.arrive=h.imHere=wrap(async function(){await patch({action:'arrive'});self.setState({screen:'arrival',tab:'arrive'});self.toast('약속 장소 도착을 알리고 위치 공유를 종료했어요.');});
   h.pkPrep=wrap(async function(){await patch({ready:'preparing'});});h.pkMove=wrap(async function(){await patch({ready:'walking'});});h.pkWait=wrap(async function(){await patch({ready:'waiting'});});
   h.board=wrap(async function(){await patch({action:'board_confirm'});self.toast('운전자에게 탑승 확인을 보냈어요.');});
   h.homeOk=wrap(async function(){await patch({action:'home'});self.toast('귀가 확인을 남겼어요.');});
   h.stopShare=wrap(async function(){if(m)await patch({share:false});self.setState({share:Object.assign({},self.state.share,{on:false})});T.stopTracking();});
   h.resumeShare=wrap(async function(){if(m){await patch({share:true});await T.startTracking();}self.setState({share:Object.assign({},self.state.share,{on:true})});});
   h.quick=wrap(async function(e){await T.message(e&&e.target?e.target.textContent:'픽업 장소에 있어요.');self.setState({sheet:null});self.toast('일행에게 메시지를 보냈어요.');});
   h.call=function(){self.toast('전화번호를 등록하지 않았어요. 빠른 메시지로 연락해 주세요.');};
   h.rerequest=function(){self.toast('약속을 만든 사람에게 새 초대 링크를 요청해 주세요.');};
   h.onQ=function(e){var value=e.target.value;self.setState({search:Object.assign({},self.state.search,{q:value,st:value?'results':'idle',sel:null})});};
   h.useCur=h.grant=wrap(async function(){self.selectMapPlace(await T.locate());});
   h.searchRemote=wrap(async function(){self.setState({search:Object.assign({},self.state.search,{st:'loading'})});var result=await T.search(self.state.search.q);result.results.forEach(function(p){var row={id:p.id,name:p.name,addr:p.address,lat:p.lat,lon:p.lon,tag:'검색 결과',land:p.address,walk:0};['start','pickup'].forEach(function(k){var i=SRC[k].findIndex(function(x){return x.id===p.id;});if(i>=0)SRC[k][i]=row;else SRC[k].push(row);});});self.setState({search:Object.assign({},self.state.search,{st:result.results.length?'results':'empty'})});});
   h.travelMinutes=function(e){var value=Math.max(1,Math.min(1440,Number(e.target.value)||1));self.setState({travelMinutes:value});};
   h.saveMinutes=wrap(async function(){await patch({travel_minutes:self.state.travelMinutes||duration(m.origin,a.destination)||30});self.toast('내 예상 이동 시간을 저장했어요.');});
   v.driverName=driver?driver.name:inv?inv.inviter:'운전자';v.meetingName=a?a.name:inv?inv.name:'초대받은 약속';v.meetingWhen=a?T.date(a.meeting_at)+' '+T.time(a.meeting_at):inv?T.date(inv.meeting_at)+' '+T.time(inv.meeting_at):'';v.destinationName=a?a.destination.name:inv?inv.destination.name:'약속 장소';v.meetingSummary=v.destinationName+' · '+(a?T.time(a.meeting_at):inv?T.time(inv.meeting_at):'');
   v.inviteReady=!!(inv||a||core.inviteError);v.noInvitation=!inv&&!a&&!core.inviteError;v.inviteError=core.inviteError||'';v.offline=core.offline;v.busy=core.busy;v.hasMessages=!!(a&&a.messages.length);v.messages=a?a.messages.slice(-5).map(function(x){return{sender:x.sender,body:x.body,time:T.time(x.created_at)};}):[];
   if(!a&&inv)others=[{name:inv.inviter}];v.others=others.map(function(x){return{name:x.name,ini:ini(x.name),av:T.color(x.name)};});
   v.share.on=!!(m?m.share:s.share.on);v.share.off=!v.share.on;v.share.txt=v.share.on?'이번 이동 동안 공유':'위치 공유 꺼짐';v.share.cls='bdg '+(v.share.on?'b-act':'');v.share.targets=[];
   if(v.search.hasSel){var selected=SRC[s.search.purpose].find(function(p){return p.id===s.search.sel;});v.search.choose=wrap(async function(){if(!selected)return;if(!Number.isFinite(selected.lat)||!Number.isFinite(selected.lon))throw new Error('지도에서 위치를 선택해 주세요.');var p={name:selected.name,address:selected.addr,lat:selected.lat,lon:selected.lon,description:self.state.search.desc||''};var values=s.search.purpose==='start'?{origin:p}:{pickup:p};if(m)await patch(values);self.setState(s.search.purpose==='start'?{origin_place:p,start:p.name,screen:self.state.searchBack||'invite'}:{place:Object.assign({},p,{land:p.description||p.address,walk:duration(s.origin_place,p)||0}),screen:self.state.searchBack||'invite'});self.toast('장소를 저장했어요.');});}
   if(!m&&inv){var travel=s.mode==='pickup'?null:duration(s.origin_place,inv.destination);if(travel&&s.mode==='car')travel=Math.max(1,Math.ceil(travel/4));var dep=travel?new Date(new Date(inv.meeting_at).getTime()-(travel+5)*60000):null;v.est={how:{car:'내 차로',walk:'걸어서',pickup:'픽업받기'}[s.mode],mins:travel?travel+'분':'장소 선택 후 계산',detail:s.mode==='pickup'?'운전자가 경로를 확인하면 픽업 시간을 안내해요':'직선거리 기반 도보 추정 · 실제 이동과 다를 수 있어요',dep:dep?T.time(dep):'—',arr:dep?T.time(new Date(new Date(inv.meeting_at).getTime()-300000)):'—'};}
   if(a&&m){
    var est=T.estimate(a,m),travel=est&&est.travel,now=Date.now(),remaining=m.started_at&&travel?Math.max(0,Math.ceil((new Date(m.started_at).getTime()+travel*60000-now)/60000)):travel;
    v.est={how:{car:'내 차로',walk:'걸어서',pickup:'픽업받기'}[m.mode],mins:travel?travel+'분':'계산 대기',detail:m.mode==='walk'?'도보 예상 · 필요하면 내 소요 시간을 수정해 주세요':core.route?core.route.notice:'경로 확인 중',dep:est&&est.departure?T.time(est.departure):'—',arr:est&&est.arrival?T.time(est.arrival):'—'};
    var driverStarted=driver&&driver.started_at,departDue=est&&est.departure&&now>=est.departure.getTime();
    v.status={now:'지금 '+T.time(new Date()),a0:!driverStarted&&!m.started_at,a1:!!driverStarted&&!departDue&&!m.started_at,a2:!!departDue&&!m.started_at,moving:!!m.started_at&&!m.arrived_at,done:!!m.arrived_at,before:!m.started_at&&!m.arrived_at,people:others.map(function(x){return{name:x.name,ini:ini(x.name),av:T.color(x.name),role:x.mode==='pickup'?'픽업':x.mode==='car'?'내 차로 이동':'걸어서 이동',st:T.status(x,a),cls:'bdg '+(x.arrived_at?'b-ok':'b-act'),stale:!!x.location&&Date.now()-new Date(x.location.updated_at).getTime()>60000};}),meSt:T.status(m,a),meCls:'bdg b-act',remain:remaining===null?'확인 중':remaining+'분',canDemo:false,mineDep:v.est.dep};
    v.departureNotice=driverStarted?v.driverName+'님이 '+T.time(driver.started_at)+'에 출발했어요.':'일행이 출발하면 알려드릴게요.';
    v.matchNotice='내 예상 이동 '+(travel||'—')+'분 · 예상 도착 '+v.est.arr;v.movingNotice='도착 예상 '+v.est.arr;v.travelMinutes=s.travelMinutes||m.travel_minutes||travel||30;
    var phase=a.trip.phase,ids=a.trip.active_pickups||[],index=ids.indexOf(m.id),boarded=!!m.boarded_at,atMine=index===a.trip.index&&phase==='arrived';
    var position=core.route&&core.route.stops.filter(function(p){return p.kind==='pickup';}).findIndex(function(p){return p.id===m.id;});var legMins=core.route?core.route.legs.slice(0,Math.max(0,position+1)).reduce(function(n,l){return n+l.duration_s/60;},0):null;
    var eta=driverStarted&&legMins!==null?new Date(new Date(driver.started_at).getTime()+legMins*60000):null;
    v.pk.remain=atMine?'도착':!driverStarted?'출발 전':eta?Math.max(0,Math.ceil((eta.getTime()-now)/60000))+'분':'확인 중';v.pk.eta=eta?T.time(eta):'출발 후 안내';v.pk.now=T.time(new Date());v.pk.dst=boarded?'함께 이동 중':atMine?'픽업 장소에 도착했어요':driverStarted?'운전자가 이동 중이에요':'운전자가 아직 출발하지 않았어요';v.pk.arrived=atMine;v.pk.driving=!atMine&&!boarded;v.pk.boarded=boarded;v.pk.notBoarded=!boarded;v.pk.canBoard=atMine&&!m.boarding_confirmed_at;v.pk.cantBoard=!v.pk.canBoard;v.pk.confirmLabel=m.boarding_confirmed_at?'운전자 확인 대기':'탑승 확인 보내기';v.pk.disabledLabel=m.boarding_confirmed_at?'운전자 확인 대기':'차가 오면 탑승 확인';v.pk.place=m.pickup?Object.assign({},m.pickup,{land:m.pickup.address,desc:m.pickup.description,walk:duration(m.origin,m.pickup)||0}):{name:'픽업 장소를 지정해 주세요',land:'',desc:'',walk:0};v.pk.req=false;v.pk.reqOk=false;v.pk.stale=!!(driver&&driver.location)&&now-new Date(driver.location.updated_at).getTime()>60000;
    v.arrPeople=others.map(function(x){return{name:x.name,ini:ini(x.name),av:T.color(x.name),st:T.status(x,a),cls:'bdg '+(x.arrived_at?'b-ok':'b-act')};});
    v.homeList=others.map(function(x){return{name:x.name,ini:ini(x.name),av:T.color(x.name),st:x.home_at?'귀가 확인 · '+T.time(x.home_at):'귀가 확인 대기',cls:'bdg '+(x.home_at?'b-ok':'b-warn')};});
    v.arrived=!!m.arrived_at;v.notArrived=!m.arrived_at;v.homeDone=!!m.home_at;v.notHomeDone=!m.home_at;v.homeTime=T.time(m.home_at);v.arrivedAt=T.time(m.arrived_at);v.arrivalMessage=m.arrived_at?'약속 장소 도착을 일행에게 전달했어요.':'약속 장소에 도착하면 알려 주세요.';
    if(a.trip.phase==='cancelled'){v.inv.cancelled=true;v.inv.open=false;v.inv.def=false;}
   }
   if(window.TogetherMaps)window.TogetherMaps.context({screen:s.screen,component:self,selection:v.search.sel});
   return v;
  };
 };
})();

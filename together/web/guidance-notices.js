/* Notification decisions use fresh, matching guidance data. No startup timer. */
(function(root){
 'use strict';
 function Policy(){this.stops={};this.notices={};}
 function active(a,ns){return !!a&&a.is_driver&&ns.navigationAid===a.id&&!['idle','finished','cancelled'].includes(a.trip.phase);}
 Policy.prototype.update=function(a,ns,now){
  if(!active(a,ns)||ns.navigation!=='ready')return null;
  var ids=a.trip.active_pickups||[],id=ids[a.trip.index],person=a.members.find(function(m){return m.id===id;}),key=a.id+':'+(id||'dest');
  var eta=id?(ns.navigationWaypoints||[]).find(function(p){return p.stopId===id;}):ns.navigationEta;
  if(!eta&&ns.navigationEta&&ns.navigationEta.stopId===id)eta=ns.navigationEta;
  if(!id&&eta&&!['dest','lot'].includes(eta.stopId))eta=null;
  var fresh=eta&&Number.isFinite(eta.seconds)&&eta.seconds>=0&&now-eta.updatedAt>=0&&now-eta.updatedAt<30000;
  var seconds=fresh?Math.max(0,eta.seconds-(now-eta.updatedAt)/1000):null;
  var old=this.stops[key],state=old||{phase:a.trip.phase,seconds:null,nearShown:false,arrivalShown:false};
  this.stops[key]=state;var result=null,name=person?person.name+' 님':a.destination.name;
  if(old&&fresh&&state.seconds!==null&&state.seconds>600&&seconds<=600&&!state.nearShown){state.nearShown=true;result={key:key+':ten',title:name+' 도착 10분 전',remaining:'약 '+Math.ceil(seconds/60)+'분',message:'픽업 준비 상태와 위치를 확인하세요.'};}
  if(old&&state.phase!==a.trip.phase&&['near','arrived'].includes(a.trip.phase)&&!state.arrivalShown){state.arrivalShown=true;result={key:key+':'+a.trip.phase,title:person?person.name+' 님 픽업':'목적지 도착',remaining:seconds===null?'도착 확인':Math.ceil(seconds/60)+'분',message:a.trip.phase==='arrived'?'픽업 장소에 도착했어요. 일행을 확인하세요.':'픽업 장소에 가까워졌어요.'};}
  state.phase=a.trip.phase;if(fresh)state.seconds=seconds;
  return result;
 };
 Policy.prototype.notification=function(a,ns,n){
  if(!active(a,ns)||n.appointment_id!==a.id||!n.id||this.notices[n.id]||!/준비|도착|취소|장소.*변경|탑승|약속 변경/.test(n.title))return null;
  this.notices[n.id]=true;return {key:a.id+':notice:'+n.id,title:n.title,remaining:'',message:n.body};
 };
 root.TogetherGuidanceNotices=Policy;if(typeof module!=='undefined')module.exports=Policy;
})(typeof window!=='undefined'?window:globalThis);

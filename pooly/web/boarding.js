/* A seat sensor cannot identify a person: match one fresh boarding event to one arrived pickup. */
(function(root){
 'use strict';
 function Policy(){this.key=null;this.armedAt=0;this.used={};}
 function current(a){var ids=a&&a.trip.active_pickups||[];return a&&a.members.find(function(m){return m.id===ids[a.trip.index];});}
 function fresh(row,now){return row&&Number.isFinite(row.updatedAt)&&now>=row.updatedAt&&now-row.updatedAt<6000;}
 function toward(name){var code=name.charCodeAt(name.length-1)-44032,tail=code>=0&&code<=11171?code%28:0;return name+(tail&&tail!==8?'으로':'로');}
 Policy.prototype.update=function(a,ns,now){
  var m=current(a),key=a&&m?a.id+':'+m.id:null;
  var eligible=!!a&&a.is_driver&&m&&m.status==='accepted'&&!m.boarded_at&&a.trip.phase==='arrived'&&['ready','waiting'].includes(m.ready)&&ns.automotive&&ns.navigationAid===a.id;
  if(!eligible){this.key=null;return null;}
  if(key!==this.key){this.key=key;this.armedAt=now;return null;}
  if(ns.restricted!==false||!ns.vehicleDriverSeat||ns.vehicleSeatMonitoring!=='connected')return null;
  var seats=ns.vehicleSeats||{},doors=ns.vehicleDoors||{},armed=this.armedAt;
  var candidates=Object.keys(seats).filter(function(area){var s=seats[area];return area!==ns.vehicleDriverSeat&&/^ROW_[123]_(LEFT|CENTER|RIGHT)$/.test(area)&&fresh(s,now)&&s.occupied===true&&s.previous===false&&s.changedAt>=armed;});
  if(candidates.length!==1)return null;
  var area=candidates[0],s=seats[area],edge=key+':'+area+':'+s.changedAt;
  if(this.used[edge]||now-s.changedAt<1500||now-s.changedAt>30000)return null;
  var names=area.endsWith('_CENTER')?[area.replace('_CENTER','_LEFT'),area.replace('_CENTER','_RIGHT')]:[area];
  var door=names.map(function(name){return doors[name];}).find(function(d){return fresh(d,now)&&d.open===false&&d.lastOpenedAt>=armed&&d.lastClosedAt>=d.lastOpenedAt&&d.lastClosedAt>=s.changedAt-5000&&now-d.lastClosedAt>=500;});
  if(!door)return null;
  this.used[edge]=true;
  return {appointmentId:a.id,memberId:m.id,name:m.name,seat:area,observedAt:s.changedAt};
 };
 Policy.prototype.notice=function(a,boarding){
  var m=current(a),next=m?m.name+' 님에게 이동 중':toward(a.destination.name)+' 이동 중';
  return {key:a.id+':boarded:'+boarding.memberId,title:boarding.name+' 님 승차 완료',remaining:'',message:next};
 };
 root.PoolyBoarding=Policy;if(typeof module!=='undefined')module.exports=Policy;
})(typeof window!=='undefined'?window:globalThis);

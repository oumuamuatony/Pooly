/* Optional native capabilities. The original design and browser companion remain shared. */
(function () {
  'use strict';
  var T=window.Pooly, prefix='pooly.v2.car.', enabled=localStorage.getItem(prefix+'voice')==='true';
  var lastText='', lastTextAt=0, refreshAt=0;
  function tell(text){window.dispatchEvent(new CustomEvent('pooly:error',{detail:text}));}
  function car(){return T.app.native&&T.app.nativeStatus.automotive&&T.role==='car';}
  function current(){var a=T.active();if(!a)throw new Error('약속을 먼저 선택해 주세요.');return a;}
  function summary(){
    var a=current(),next=(a.trip.active_pickups||a.pickup_order||[])[a.trip.index||0];
    var person=a.members.find(function(m){return m.id===next;}),eta=T.app.nativeStatus.navigationEta;
    return a.name+'. '+(person?'다음 픽업은 '+person.name+'님이에요. ':'목적지로 이동해요. ')+(fresh(eta)&&T.app.nativeStatus.navigationAid===a.id?'차량 내비 기준 '+Math.ceil(eta.seconds/60)+'분 남았어요.':'예상 시간은 화면에서 확인해 주세요.');
  }
  function fresh(eta){return eta&&Number.isFinite(eta.seconds)&&eta.seconds>=0&&Date.now()-eta.updatedAt<30000;}
  async function speak(text){if(!car())throw new Error('Pleos 차량 앱에서 사용할 수 있어요.');return T.native('speak',{text:text||summary()});}
  async function toggle(){enabled=!enabled;localStorage.setItem(prefix+'voice',String(enabled));if(!enabled)await T.native('stopVoice');tell(enabled?'약속 상태 음성 안내를 켰어요.':'음성 안내를 껐어요.');}
  async function listen(){if(!car())throw new Error('Pleos 차량 앱에서 사용할 수 있어요.');await T.native('listen');tell('음성 인식을 요청했어요. 픽업 상태, 내비 안내, 도착 알림, 위치 공유 중지를 말해 주세요.');}
  async function command(text){
    if(!text||!car()||!T.app.nativeStatus.foreground)return;
    var normalized=text.replace(/\s/g,''),a=current(),aid=a.id,title,run;
    if(/상태|누구|얼마나|몇분/.test(normalized)){await speak();return;}
    if(/내비|네비|안내시작|길안내/.test(normalized)){title='남은 픽업 장소와 목적지를 차량 내비로 안내';run=function(){return T.navigate();};}
    else if(/도착.*알|알.*도착/.test(normalized)){title='현재 픽업 대상에게 도착 알림 보내기';run=function(){return T.action('notify');};}
    else if(/공유.*(중지|꺼|종료)/.test(normalized)){title='이번 이동의 내 위치 공유 중지';run=function(){return T.member({share:false});};}
    else {tell('“픽업 상태”, “내비 안내”, “도착 알려줘”, “위치 공유 중지” 중 하나를 말해 주세요.');return;}
    var result=await T.native('confirmVoice',{title:title});
    if(result.confirmed){if(!T.active()||T.active().id!==aid)throw new Error('선택한 약속이 바뀌었어요. 다시 말해 주세요.');await run();tell('음성 명령을 처리했어요.');}
  }
  window.addEventListener('pooly:native',function(e){
    var text=e.detail.speechText;
    if(text&&(text!==lastText||Date.now()-lastTextAt>3000)){lastText=text;lastTextAt=Date.now();command(text).catch(function(e){tell(e.message);});}
  });
  window.addEventListener('pooly:notice',function(e){if(car()&&enabled&&T.app.nativeStatus.foreground)speak(e.detail.title+'. '+e.detail.body).catch(function(){});});
  T.on(function(){
    if(!car())return;
    // Refresh only while our route is selected. Never use OEM ETA for another appointment.
    if(T.app.nativeStatus.foreground&&T.app.nativeStatus.navigationAid===T.app.selected&&Date.now()-refreshAt>10000){refreshAt=Date.now();T.native('refreshNavigation').catch(function(){});}
  });
  window.PoolyPleos={speak:speak,toggle:toggle,listen:listen,fresh:fresh,enabled:function(){return enabled;}};
})();

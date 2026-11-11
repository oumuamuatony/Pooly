"""Apply functional bindings to the user's preserved design, reproducibly."""
from pathlib import Path
import re

WEB = Path(__file__).resolve().parents[1] / "web"


def common(name):
    s = (WEB / (name + ".design-original.html")).read_text()
    s = s.replace('<meta charset="utf-8">', '<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">')
    scripts = ['vendor/d949f1c3687aedad.js', 'vendor/35f4f974f4b2bcd4.js', 'core.js', 'pleos.js', 'guidance-notices.js', 'boarding.js', name + '-adapter.js', 'maps.js', 'layout.js', 'vendor/85c4831871e1b2b2.js']
    s = s.replace('<script src="vendor/85c4831871e1b2b2.js"></script>', '\n'.join('<script src="' + p + '"></script>' for p in scripts) + '\n<link rel="stylesheet" href="app.css">')
    s=s.replace('차량용 프로토타입','차량').replace('휴대폰 프로토타입','휴대폰')
    html, js = s.split('<script type="text/x-dc"', 1)
    return html, '<script type="text/x-dc"' + js


html, js = common('car')
start = html.index('<sc-if value="{{is.home}}"')
end = html.index('<sc-if value="{{is.create}}"', start)
html = html[:start] + (WEB / 'templates/car-home.html').read_text() + '\n' + html[end:]
html = html.replace('>위치 공유 중</span>','>{{shareNavLabel}}</span>')
html = html.replace('>공유 대상</span>','>공유 대상 · 이번 약속 참가자 모두</span>')
html = html.replace('통신이 끊겼어요 · 13:31 기준 정보를 보여드려요', '연결이 끊겼어요 · 마지막으로 받은 정보를 보여드려요')
html = re.sub(r'<div class="row" style="gap: 8px"><button class="pick" style="flex: 1" sc-camel-on-click="\{\{h.fDate\}\}">.*?</div></div></div>', '<input type="datetime-local" class="inp" value="{{form.meeting}}" sc-camel-on-change="{{h.fMeeting}}" aria-label="약속 날짜와 시간"></div>', html, count=1, flags=re.S)
html = html.replace('<div class="sb">\n<div style="padding: 16px 0">', '<div class="sb">\n<div style="padding:16px 0"><label class="lab" for="newFriendName">새 일행 이름</label><div class="row" style="margin-top:10px"><input id="newFriendName" class="inp" value="{{addName}}" sc-camel-on-change="{{h.onAddName}}" placeholder="일행 이름"><button class="btn bs sm" sc-camel-on-click="{{h.addPerson}}">추가</button></div></div>\n<div style="padding: 16px 0">', 1)
# Insert the name field at a stable sheet-specific anchor if the optional source
# whitespace differs between exported versions.
if 'id="newFriendName"' not in html:
    marker='<sc-if value="{{sh.addFriends}}"'
    pos=html.index(marker);body=html.index('<div class="sb"',pos);close=html.index('>',body)+1
    html=html[:close]+'<div style="padding:16px 0"><label class="lab" for="newFriendName">새 일행 이름</label><div class="row" style="margin-top:10px"><input id="newFriendName" class="inp" value="{{addName}}" sc-camel-on-change="{{h.onAddName}}" placeholder="일행 이름"><button class="btn bs sm" sc-camel-on-click="{{h.addPerson}}">추가</button></div></div>'+html[close:]
html=html.replace('최근 함께 이동 3회','약속별로 참가와 준비 상태를 확인해요').replace('서울숲 산책에 초대','선택한 약속에 추가')
html=html.replace('다음 상황 · {{moving.demoLabel}} ▶','{{moving.demoLabel}}').replace('<span class="tagd">시연</span>','<span class="tagd">도착 확인</span>')
html=html.replace('위치 공유 종료됨 · 13:58','위치 공유 종료됨').replace('2026년 12월 5일 (토) · 실제 주행 기록','{{detail.when}} · 실제 주행 기록')
html=html.replace('귀가 확인은 차량 이동과 별도로, 각자 휴대폰에서 남겨요 · 대기 2명','귀가 확인은 차량 이동과 별도로, 각자 휴대폰에서 남겨요')
replacements={'13:15':'{{detail.dep}}','13:17':'{{rec.started}}','13:50':'{{detail.arr}}','13:49':'{{rec.parked}}','13:55':'{{detail.arr}}','13:58':'{{rec.ended}}','7.4km':'{{rec.plannedKm}}','7.8km':'{{rec.km}} km','30분':'{{rec.plannedMinutes}}','32분':'{{rec.duration}}'}
rstart=html.index('<sc-if value="{{is.record}}"');rend=html.index('<sc-if value="{{is.history}}"',rstart)
section=html[rstart:rend]
for old,new in replacements.items():section=section.replace(old,new)
section=section.replace('예상 경로','예상 장소 연결선')
section=section.replace('{{detail.dep}}','{{rec.expectedStart}}').replace('{{detail.arr}}','{{rec.expectedParked}}',1).replace('{{detail.arr}}','{{rec.expectedArrival}}')
html=html[:rstart]+section+html[rend:]
html=re.sub(r'(<sc-for list="\{\{th.to\}\}"[^>]*>)(.*?)(</sc-for>)', lambda m:m[1]+m[2].replace('<button class="li"','<div class="li"').replace(' sc-camel-on-click="{{u.toggle}}"','').replace('</button>','</div>')+m[3], html, flags=re.S)
html=html.replace('받는 사람</span>','받는 사람 · 이번 약속 참가자 모두</span>')
html=re.sub(r'<button class="\{\{u.cls\}\}" sc-camel-on-click="\{\{u.toggle\}\}">(.*?)</button>', r'<span class="opt on">\1</span>', html, flags=re.S)
start=html.index('<sc-if value="{{sh.thanks}}"')
end=html.index('</aside></sc-if>',start)+len('</aside></sc-if>')
html=html[:start]+html[end:]
cstart=html.index('<sc-if value="{{sh.connect}}"');cend=html.index('</div></sc-if>',cstart)+len('</div></sc-if>')
html=html[:cstart]+'''<sc-if value="{{sh.connect}}"><div class="dlg" role="dialog" aria-label="기기와 권한" style="width:680px"><div class="col" style="gap:12px;padding:28px 32px"><span class="lab">기기와 권한</span><h2 class="h2">함께가기 연결</h2><p class="b">{{cn.user}} · 서버 {{cn.server}}</p><div class="cell"><span class="t">이번 이동의 위치</span><p class="cap">출발 후 위치 공유를 켠 경우에만 일행과 위치를 공유하고 주행을 기록해요.</p><button class="btn bs sm" sc-camel-on-click="{{h.allowLocation}}">위치 권한 확인</button></div><div class="cell"><span class="t">일행 알림</span><p class="cap">출발·준비 상태·도착 알림을 받아요.</p><button class="btn bs sm" sc-camel-on-click="{{h.allowNoti}}">알림 권한 확인</button></div><p class="cap">초대 관리에서 링크를 공유하면 다른 기기의 일행이 참가할 수 있어요.</p><div class="row" style="justify-content:flex-end"><button class="btn bp" sc-camel-on-click="{{h.connDone}}">다시 연결·닫기</button></div></div></div></sc-if>'''+html[cend:]
# Native features extend the existing connection dialog and moving controls.
html=html.replace('<p class="cap">초대 관리에서 링크를 공유하면 다른 기기의 일행이 참가할 수 있어요.</p>', '<sc-if value="{{cn.isCar}}"><div class="cell"><span class="t">차량 내비 · {{cn.navi}}</span><p class="cap">남은 픽업 장소를 경유해요. 경유지는 최대 2곳이며 더 많으면 다음 픽업부터 안내해요.</p><div class="row" style="gap:8px"><button class="btn bs sm" sc-camel-on-click="{{h.navigate}}">경로 안내</button><button class="btn bs sm" sc-camel-on-click="{{h.openNavigation}}">내비 열기</button></div></div><div class="cell"><span class="t">음성 안내 {{cn.tts}} · 음성 인식 {{cn.stt}}</span><p class="cap">버튼을 누르면 음성을 받아요. 실행 전 화면에서 확인해요.</p><div class="row" style="gap:8px;flex-wrap:wrap"><button class="btn bs sm" sc-camel-on-click="{{h.readStatus}}" disabled="{{cn.noTts}}">상태 읽기</button><button class="btn bs sm" sc-camel-on-click="{{h.voiceCommand}}" disabled="{{cn.noStt}}">음성 명령</button><button class="btn bs sm" sc-camel-on-click="{{h.toggleVoice}}">{{cn.voice}}</button></div></div><p class="cap">차량 위치 {{cn.fused}} · 차량 데이터 {{cn.vehicle}}</p><p class="cap">Gleo: 픽업 상태 확인 · 내비 안내 · 도착 알림<br>Gleo의 앱 명령 활성화는 공식 연동 승인이 필요해요.</p></sc-if><p class="cap">초대 관리에서 링크를 공유하면 다른 기기의 일행이 참가할 수 있어요.</p>')
html=html.replace('aria-label="기기와 권한" style="width:680px"', 'aria-label="기기와 권한" style="width:680px;max-height:90vh;overflow:auto"')
html=html.replace('<div class="demo pe">', '<div class="demo pe" style="display:flex;gap:8px"><sc-if value="{{cn.isCar}}"><button class="btn bs sm" sc-camel-on-click="{{h.navigate}}">다음 경로 안내</button><button class="btn bs sm" sc-camel-on-click="{{h.voiceCommand}}" disabled="{{cn.noStt}}">음성 명령</button><button class="btn bs sm" sc-camel-on-click="{{h.openNavigation}}">내비 열기</button></sc-if>')

# A single supported sharing lifetime is clearly stated rather than simulating
# a second, unimplemented expiry behavior.
html=re.sub(r'<div class="seg"[^>]*>\s*<button[^>]*sc-camel-on-click="\{\{h.untilArrive\}\}".*?</div>', '<p class="cap">위치 공유는 이번 이동이 끝나거나 직접 중지하면 종료돼요.</p>', html, count=1, flags=re.S)
html=html.replace('약속 종료 오후 4:00까지','이번 이동이 끝날 때까지')
html=html.replace('<span class="cap">{{detail.when}}</span>','<span class="cap">{{detail.when}}</span>')
html=html.replace('<h1 class="h2">주변 주차장</h1>','<h1 class="h2">주변 주차장</h1><button class="btn bs sm" sc-camel-on-click="{{h.findParking}}">지도에서 직접 선택</button>')
# Add real search submission, including an accessible explicit button.
search_pos=html.index('aria-label="{{search.title}}"') if 'aria-label="{{search.title}}"' in html else -1
needle='sc-camel-on-click="{{h.clearQ}}"'
if needle in html:
    qpos=html.index(needle); qend=html.index('</button>',qpos)+len('</button>')
    html=html[:qend]+'<button class="btn bs sm" sc-camel-on-click="{{h.searchRemote}}">검색</button>'+html[qend:]
html=html.replace('<p class="cap">{{detail.lateHint}}</p>','<p class="cap">{{detail.lateHint}}</p><p class="cap">{{routeNotice}}</p>')
html=html.replace('출발하기</button>','출발하기</button><button class="btn bs sm" sc-camel-on-click="{{h.navigate}}">차량 내비로 안내</button>')
html=html.replace('<div class="demo pe"><span', '<div class="demo pe"><button class="btn bs sm" sc-camel-on-click="{{h.navigate}}">차량 내비로 안내</button><span')
js=re.sub(r"  var recent=\(S.purpose==='pickup'.*?;\n", "  var recent=src.slice(0,3).map(function(p){return rowOf(p,'저장한 장소');});\n", js, count=1)
js=js.replace("(search.idle?(S.purpose==='pickup'?[byId('s3'),byId('t8')]:[byId('d'),byId('k'),byId('s')]):[])", "(search.idle?src.slice(0,3):[])")
js=re.sub(r"  var fav=S.purpose==='pickup'.*?;\n", "  var fav=[];\n", js, count=1)
js=js.replace('초대를 보냈어요 · 아직 응답 전','초대 링크 생성됨 · 응답 대기')
js=js.replace("events:st!=='empty'","events:false").replace("thanksTo:['jiyeon','hyunjun']","thanksTo:[]")
# November 9 user layouts; preserve the other working screens and bindings.
for screen, following in [('create', 'search'), ('moving', 'record')]:
    begin = html.index('<sc-if value="{{is.' + screen + '}}"')
    end = html.index('<sc-if value="{{is.' + following + '}}"', begin)
    html = html[:begin] + (WEB / ('templates/car-' + screen + '.html')).read_text() + '\n' + html[end:]
# Pickup locations are viewed only in the built-in navigation, never a web map.
begin = html.index('<sc-if value="{{sh.preview}}"')
end = html.index('</aside></sc-if>', begin) + len('</aside></sc-if>')
html = html[:begin] + html[end:]
js=re.sub(r"  var trip=\{name:'서울숲 산책'.*?;\n", "  var trip={name:'새 약속',time:840,start:'출발지',dest:'약속 장소',mode:'car',members:[],pickups:[],status:{},lot:'p1',depart:795};\n",js,count=1)
hook='\nwindow.TogetherCar(Component,{FR:FR,ALL:ALL,PT:PT,LOTS:LOTS,RES:RES,RESP:RESP});\nroute=function(order,lotId,depart,appt,delay){return window.TogetherRouteForDesign(order,lotId,depart,appt,delay);};\n'
idx=js.rfind('</script>');js=js[:idx]+hook+js[idx:]
# Keep the existing binding engine; replace the remaining car screens and picker.
for screen, following in [('search','detail'),('detail','parking'),('record','history')]:
    begin=html.index('<sc-if value="{{is.'+screen+'}}"')
    end=html.index('<sc-if value="{{is.'+following+'}}"',begin)
    html=html[:begin]+(WEB/('templates/car-'+screen+'.html')).read_text()+'\n'+html[end:]
begin=html.index('<sc-if value="{{is.history}}"');end=html.index('<sc-if value="{{sh.any}}"',begin)
html=html[:begin]+(WEB/'templates/car-history.html').read_text()+'\n'+html[end:]
begin=html.index('<sc-if value="{{sh.addFriends}}"');end=html.index('<sc-if value="{{sh.invites}}"',begin)
html=html[:begin]+(WEB/'templates/car-friends.html').read_text()+'\n'+html[end:]
begin=html.index('<sc-if value="{{sh.invites}}"')
end=html.index('</aside></sc-if>',begin)+len('</aside></sc-if>')
html=html[:begin]+html[end:]
html=re.sub(r'<div class="brand">.*?</div>','',html,count=1,flags=re.S)
html=html.replace('함께가기','Pooly')
(WEB/'car.html').write_text(html+js)

# The phone experience is a browser website; no Android-specific bridge is required.
(WEB/'phone.html').write_text((WEB/'templates/phone-web.html').read_text())
print('Built preserved vehicle UI and mobile web prototype.')

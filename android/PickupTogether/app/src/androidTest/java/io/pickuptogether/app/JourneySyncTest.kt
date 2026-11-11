package io.pickuptogether.app

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable API/app records; real NaviHelper callbacks and real overlay measurements. */
@RunWith(AndroidJUnit4::class)
class JourneySyncTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val base="http://127.0.0.1:59583"
    private fun web(a: MainActivity)=MainActivity::class.java.getDeclaredField("web").let { it.isAccessible=true;it.get(a) as WebView }
    private fun eval(s: ActivityScenario<MainActivity>,js: String): String {
        val latch=CountDownLatch(1);var value="null"
        s.onActivity { a->web(a).evaluateJavascript(js){value=it;latch.countDown()} };check(latch.await(8,TimeUnit.SECONDS));return value
    }
    private fun waitFor(s: ActivityScenario<MainActivity>,js: String,seconds: Int=35) {
        val until=System.currentTimeMillis()+seconds*1000
        while(System.currentTimeMillis()<until){if(eval(s,js)=="true")return;eval(s,"if(window.Together)Together.refresh();true");Thread.sleep(300)}
        fail("Timed out: $js; "+eval(s,"JSON.stringify({mode:Together.app.nativeStatus.navigationMode,session:Together.active()?.trip.navigation,phase:Together.active()?.trip.phase})"))
    }
    private fun capture(name: String){val bmp=inst.uiAutomation.takeScreenshot();assertNotNull(bmp);java.io.File(inst.targetContext.filesDir,name).outputStream().use { bmp!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }}
    private fun stopInOem(){
        val until=System.currentTimeMillis()+2500
        val roots={inst.uiAutomation.windows.mapNotNull { it.root }+listOfNotNull(inst.uiAutomation.rootInActiveWindow)}
        while(System.currentTimeMillis()<until){
            for(root in roots()){
                val stop=root.findAccessibilityNodeInfosByText("안내 종료").firstOrNull {
                    it.isVisibleToUser&&it.packageName?.toString()=="ai.umos.maps.android.navigation.app"&&
                        (it.text?.toString()=="안내 종료"||it.contentDescription?.toString()=="안내 종료")
                } ?: continue
                var node: android.view.accessibility.AccessibilityNodeInfo?=stop
                while(node!=null){
                    if(node.isClickable&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return
                    node=node.parent
                }
            }
            Thread.sleep(250)
        }
        // This emulator draws the split-screen navigation control without
        // exporting a clickable node. Use the control measured in its 2560×1440 layout.
        capture("oem-before-stop.png")
        val screenshot=inst.uiAutomation.takeScreenshot()
        assertEquals(2560,screenshot.width)
        assertEquals(1440,screenshot.height)
        val now=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
            val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,1217f,1227f,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(inst.uiAutomation.injectInputEvent(event,true))
            event.recycle()
        }
    }
    @Test fun selectionLimitOrderLayoutAndBothGuidanceStopDirections(){
        ActivityScenario.launch<MainActivity>(Intent(inst.targetContext,MainActivity::class.java).putExtra("apiUrl",base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { s->
            try{
                waitFor(s,"!!(window.Together&&Together.app.user&&!Together.app.loading&&Together.app.nativeStatus.navigationConnected)")
                eval(s,"(async()=>{window.qaFriends=[];for(var i=0;i<12;i++)qaFriends.push(await Together.api('/contacts','POST',{name:['Kelly','Max','Jeff'][i%3]+(i<3?'':' '+i)}));await Together.refresh();window.qaFriendsReady=true;})();true")
                waitFor(s,"window.qaFriendsReady===true")
                eval(s,"Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='일정 만들기').click();true")
                waitFor(s,"document.documentElement.dataset.navigationPanel==='create'")
                assertEquals("false",eval(s,"!!document.querySelector('.pickup-hint')"))
                eval(s,"Together.carComponent.setState({form:Object.assign({},Together.carComponent.state.form,{members:qaFriends.slice(0,3).map(x=>x.id),pickups:[]})});true")
                waitFor(s,"document.querySelectorAll('.member-photo-button').length===3")
                assertEquals("\"일행을 클릭하여 경유지 추가\"",eval(s,"document.querySelector('.pickup-hint').textContent"))
                eval(s,"document.querySelectorAll('.member-name')[1].click();document.querySelectorAll('.member-photo-button')[0].click();true")
                waitFor(s,"Together.carComponent.state.form.pickups.length===2")
                assertEquals("true",eval(s,"document.querySelectorAll('.member-photo-button')[2].disabled"))
                assertEquals("true",eval(s,"JSON.stringify(Together.carComponent.state.form.pickups)===JSON.stringify([qaFriends[1].id,qaFriends[0].id])"))
                assertEquals("true",eval(s,"Array.from(document.querySelectorAll('.pickup-sequence-row strong')).map(x=>x.textContent).join(',')==='Max,Kelly'"))
                assertEquals("true",eval(s,"!document.querySelector('.create-panel').innerText.match(/픽업할 일행|최대 2명|초대 링크|픽업 위치/)&&document.querySelector('.create-body').scrollHeight<=document.querySelector('.create-body').clientHeight+2"))
                Thread.sleep(400)
                capture("create-panel-right.png")
                var rectangle=listOf<Int>();s.onActivity { a->val v=web(a);val xy=IntArray(2);v.getLocationOnScreen(xy);rectangle=listOf(xy[0],xy[1],v.width,v.height) }
                assertEquals(680.0 / 420.0,rectangle[3].toDouble()/rectangle[2],0.005)
                android.util.Log.i("PoolySyncTest","PASS selection limit/order, fixed panel bounds=$rectangle")
                eval(s,"Together.carComponent.setState({form:Object.assign({},Together.carComponent.state.form,{members:qaFriends.map(x=>x.id)})});true")
                waitFor(s,"document.querySelectorAll('.member-photo-button').length===12")
                capture("create-panel-twelve.png")
                val layout=eval(s,"JSON.stringify(['.create-body','.member-portraits','.create-footer'].map(sel=>{let e=document.querySelector(sel),r=e.getBoundingClientRect();return [sel,e.scrollHeight,e.clientHeight,r.top,r.bottom,getComputedStyle(e).gridTemplateColumns]}))")
                assertEquals(layout,"true",eval(s,"document.querySelector('.create-body').scrollHeight<=document.querySelector('.create-body').clientHeight+2&&document.querySelector('.member-portraits').getBoundingClientRect().bottom<document.querySelector('.create-footer').getBoundingClientRect().top"))
                eval(s,"Together.native('closeNavigationPanel',{returnToApp:true}).then(()=>Together.carComponent.setState({form:null,screen:'home'}));true")
                waitFor(s,"!document.documentElement.dataset.navigationPanel")
                val meeting=java.time.Instant.now().plusSeconds(7200).toString()
                eval(s,"Together.create({name:'SDK guidance sync verification',meeting_at:'$meeting',origin:Together.app.places[0],destination:Together.app.places[1],mode:'car',members:[]}).then(()=>window.qaAppointmentCreated=true);true")
                waitFor(s,"window.qaAppointmentCreated===true&&document.querySelector('.appointment-duration strong')?.textContent!=='확인 중'")
                waitFor(s,"!Together.carComponent._loadingRouteTimings")
                assertEquals("true",eval(s,"(()=>{const t=Together.carComponent.timingFor(Together.active());return t.source==='pleos'&&t.seconds>0&&Math.abs(t.arrival-Date.now()-t.seconds*1000)<1000;})()"))
                Thread.sleep(350)
                capture("upcoming-appointment.png")
                eval(s,"document.querySelector('.appointment-actions .bs').click();true")
                waitFor(s,"!!document.querySelector('.journey-timeline')")
                assertEquals("true",eval(s,"(()=>{const times=[...document.querySelectorAll('.stop-time')].map(e=>e.textContent),t=Together.carComponent.timingFor(Together.active());return times[0]===Together.time(new Date())&&times.at(-1)===Together.time(t.arrival)&&!document.querySelector('.journey-screen').innerText.match(/각자 이동|초대 관리|출발 전|약속 장소/);})()"))
                Thread.sleep(350)
                capture("journey-eta.png")
                eval(s,"Together.navigate().then(()=>window.qaNavigationStarted=true);true")
                waitFor(s,"window.qaNavigationStarted===true&&Together.active()?.trip.navigation?.status==='guiding'",50)
                waitFor(s,"Together.carComponent.timingFor(Together.active()).source==='pleos'")
                eval(s,"window.dispatchEvent(new CustomEvent('together:native',{detail:{navigationMessage:'차량 내비 안내가 종료됐어요.',pickupBoardingNotice:{appointmentId:Together.active().id,key:'test',observedAt:Date.now(),title:'탑승 완료'}}}));true")
                assertEquals("true",eval(s,"!document.querySelector('.toast')&&!Together.app.nativeStatus.navigationPanel"))
                val prefs=inst.targetContext.getSharedPreferences("together_session",0)
                val firstSession=prefs.getString("navigationSessionId","")
                stopInOem()
                waitFor(s,"Together.active()?.trip.navigation?.status==='stopped'",30)
                assertEquals("destination",eval(s,"Together.active().trip.phase").trim('"'))
                assertEquals("null",eval(s,"Together.active().trip.finished_at"))
                assertEquals("",prefs.getString("navigationAppointment",""))
                eval(s,"Together.refresh();true")
                android.util.Log.i("PoolySyncTest","PASS OEM SDK stop -> server guidance stopped, appointment preserved")
                eval(s,"Together.navigate().then(()=>window.qaResumed=true);true")
                waitFor(s,"window.qaResumed===true&&Together.active()?.trip.navigation?.status==='guiding'",50)
                assertNotEquals(firstSession,prefs.getString("navigationSessionId",""))
                val aid=eval(s,"Together.active().id").trim('"')
                val token=prefs.getString("token","")!!
                for(action in listOf("park","finish"))assertEquals(200,LocalApi.request(base,"/api/appointments/$aid/actions","POST",JSONObject().put("action",action),token).getInt("status"))
                val until=System.currentTimeMillis()+25000
                while(System.currentTimeMillis()<until&&prefs.getString("navigationAppointment","")!!.isNotEmpty())Thread.sleep(250)
                assertEquals("Remote finish must cancel the matching OEM route","",prefs.getString("navigationAppointment",""))
                assertEquals("SAFE_GUIDING",PleosRuntime.state().optString("navigationMode"))
                android.util.Log.i("PoolySyncTest","PASS backend finish while OEM foreground -> matching route cancelled")
                eval(s,"Together.refresh();true")
                waitFor(s,"Together.active()?.trip.phase==='finished'")
                eval(s,"Together.carComponent.go('home');true")
                waitFor(s,"!document.querySelector('.appointment-card')")
                assertEquals("true",eval(s,"document.querySelector('nav').innerText.trim().split(/\\s+/).join(' ')==='홈 주행 기록'"))
                eval(s,"Together.carComponent.go('history');true")
                waitFor(s,"document.querySelector('.history-list')?.innerText.includes('SDK guidance sync verification')")
                capture("sync-finished.png")
            }finally{
                s.onActivity { a->
                    val field=MainActivity::class.java.getDeclaredField("panel");field.isAccessible=true;(field.get(a) as NavigationPanelWindow).close()
                    PleosRuntime.navigation?.cancelPreview()
                }
            }
        }
    }

    @Test fun finalApproachFinishesGuidanceAndSavesRecord() {
        val intent = Intent(inst.targetContext, MainActivity::class.java).putExtra("apiUrl", base)
        ActivityScenario.launch<MainActivity>(intent).use { s ->
            waitFor(s, "!!(window.Together&&Together.app.user&&!Together.app.loading&&Together.app.nativeStatus.navigationConnected)")
            val meeting = java.time.Instant.now().plusSeconds(7200)
            eval(s, "Together.create({name:'Destination approach verification',meeting_at:'$meeting',origin:Together.app.places[0],destination:Together.app.places[1],mode:'car',members:[]}).then(()=>Together.navigate());true")
            waitFor(s, "Together.active()?.name==='Destination approach verification'&&Together.active()?.trip.navigation?.status==='guiding'", 50)
            eval(s, "Together.carComponent.go('detail');true")
            waitFor(s, "Array.from(document.querySelectorAll('.journey-footer button')).some(b=>b.textContent.trim()==='취소')")
            assertEquals("true", eval(s, "!document.querySelector('.journey-footer').innerText.includes('주차 완료')"))
            val target = JSONObject(eval(s, "Together.active().destination"))
            val prefs = inst.targetContext.getSharedPreferences("together_session", 0)
            val until = System.currentTimeMillis() + 12000
            while (System.currentTimeMillis() < until && prefs.getString("navigationAppointment", "").orEmpty().isNotEmpty()) {
                val now = System.currentTimeMillis()
                val eta = JSONObject().put("stopId", "dest").put("meters", 30).put("seconds", 5).put("updatedAt", now)
                val position = JSONObject(target.toString()).put("updatedAt", now)
                s.onActivity { PleosRuntime.publish(JSONObject().put("navigationEta", eta).put("navigationPosition", position)) }
                Thread.sleep(250)
            }
            waitFor(s, "Together.active()?.trip.phase==='finished'&&!!document.querySelector('.record-screen')")
            assertEquals("true", eval(s, "!document.querySelector('.record-screen').innerText.includes('고마움 전하기')"))
            assertEquals("The matching OEM route should be released", "", prefs.getString("navigationAppointment", ""))
            eval(s, "Together.carComponent.go('history');true")
            waitFor(s, "document.querySelector('.history-list')?.innerText.includes('Destination approach verification')")
        }
    }

}

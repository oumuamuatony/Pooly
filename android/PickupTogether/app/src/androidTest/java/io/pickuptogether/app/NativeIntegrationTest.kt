package io.pickuptogether.app

import android.content.Intent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the bundled UI -> scoped native bridge -> disposable API database. */
@RunWith(AndroidJUnit4::class)
class NativeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = "http://127.0.0.1:59583"
    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java).putExtra("apiUrl", base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val latch = CountDownLatch(1); var value = "null"
        scenario.onActivity { activity -> ((activity.findViewById<FrameLayout>(android.R.id.content)).getChildAt(0) as WebView).evaluateJavascript(script) { result -> value = result; latch.countDown() } }
        check(latch.await(8, TimeUnit.SECONDS)); return value
    }
    private fun until(scenario: ActivityScenario<MainActivity>, expression: String, seconds: Int = 25) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < deadline) { if (evaluate(scenario, expression) == "true") return; evaluate(scenario, "if(window.Together)Together.refresh();true"); Thread.sleep(300) }
        fail("Timed out: $expression; UI: " + evaluate(scenario, "document.body.innerText.slice(-1000)"))
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(700) // Allow the map worker to paint the new screen before capture.
        val folder = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val directory = java.io.File(folder).apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> val bytes = java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes); java.io.File(directory, name + ".b64").writeText(android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)) }
    }
    private fun foregroundActivity(): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader.lineSequence().firstOrNull { it.contains("topResumedActivity=") } ?: "No foreground activity"
        }
    }
    private fun focusedWindow(): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys window")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader.lineSequence().firstOrNull { it.contains("mCurrentFocus=") } ?: "No focused window"
        }
    }
    private fun returnToPickup(scenario: ActivityScenario<MainActivity>) {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            context.startActivity(Intent(context, MainActivity::class.java).putExtra("apiUrl", base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        until(scenario, "!!(window.Together&&Together.app.user&&!Together.app.loading&&Together.app.nativeStatus.foreground)")
    }
    @Test fun navigationButtonActuallyOpensVehicleNavigation() = verifyNavigationOpening(false)
    @Test fun departureActuallyOpensVehicleNavigation() = verifyNavigationOpening(true)
    private fun verifyNavigationOpening(depart: Boolean) {
        launch().use { scenario ->
            until(scenario, "!!(window.Together&&Together.app.user&&!Together.app.loading&&Together.app.nativeStatus.navigationConnected===true)")
            val meeting = java.time.Instant.now().plusSeconds(7200).toString()
            evaluate(scenario, "Together.create({name:'내비 화면 연동 검증',meeting_at:'$meeting',origin:Together.app.places[0],destination:Together.app.places[1],mode:'car',members:[]}).then(()=>Together.member({share:false})).then(()=>{Together.carComponent.setState({screen:'detail',sheet:null});window.__navigationAppointment=true;});true")
            until(scenario, "!!window.__navigationAppointment&&Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()==='차량 내비로 안내')")
            val label = if (depart) "출발하기" else "차량 내비로 안내"
            evaluate(scenario, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='$label').click();true")
            val prefs = instrumentation.targetContext.getSharedPreferences("together_session", 0)
            val deadline = System.currentTimeMillis() + 25000
            var focus = focusedWindow()
            while (System.currentTimeMillis() < deadline && (!focus.contains("ai.umos.maps.android.navigation.app") || !prefs.getBoolean("navigationConfirmed", false))) { Thread.sleep(300); focus = focusedWindow() }
            android.util.Log.i("TogetherTest", "NAVIGATION ${if (depart) "departure" else "button"}: $focus; bound destination: ${prefs.getBoolean("navigationConfirmed", false)}")
            capture(if (depart) "native-navigation-departure.png" else "native-navigation-button.png")
            try {
                assertTrue("Navigation window did not receive focus: $focus", focus.contains("ai.umos.maps.android.navigation.app"))
                assertTrue("OEM navigation did not return the selected destination", prefs.getBoolean("navigationConfirmed", false))
            } finally {
                returnToPickup(scenario)
                evaluate(scenario, "Together.native('cancelNavigation');true")
                if (depart) { until(scenario, "Together.active().trip.phase==='destination'"); evaluate(scenario, "Together.action('cancel');true") }
            }
        }
    }
    @Test fun nativeBridgePersistsAndSynchronizesPickup() {
        launch().use { scenario ->
            until(scenario, "!!(window.Together&&Together.app.user&&!Together.app.loading)")
            assertEquals("true", evaluate(scenario, "!!window.TogetherNative"))
            val geometry = evaluate(scenario, "JSON.stringify({w:innerWidth,h:innerHeight,right:document.querySelector('.app').getBoundingClientRect().right,bottom:document.querySelector('.app').getBoundingClientRect().bottom})")
            android.util.Log.i("TogetherTest", "Layout $geometry")
            assertEquals("true", evaluate(scenario, "document.querySelector('.app').getBoundingClientRect().right <= innerWidth+2 && document.querySelector('.app').getBoundingClientRect().bottom <= innerHeight+2"))
            evaluate(scenario, "Together.addContact('자동 검증 참가자').then(c=>{window.__contact=c;}).catch(e=>window.__failure=e.message);true")
            until(scenario, "!!window.__contact")
            val meeting = java.time.Instant.now().plusSeconds(7200).toString()
            evaluate(scenario, "Together.create({name:'자동 검증 약속',meeting_at:'$meeting',origin:Together.app.places[0],destination:Together.app.places[1],mode:'car',members:[{id:window.__contact.id,mode:'pickup'}]}).then(a=>window.__created=a).catch(e=>window.__failure=e.message);true")
            until(scenario, "!!window.__created")
            assertEquals("true", evaluate(scenario, "Together.active().id===window.__created.id && Together.active().members.length===1"))
            val raw = evaluate(scenario, "JSON.stringify(Together.active())")
            val appointment = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
            val token = instrumentation.targetContext.getSharedPreferences("together_session", 0).getString("token", "")!!
            val member = appointment.getJSONArray("members").getJSONObject(0)
            val invitation = member.getString("invite_token")
            val other = LocalApi.request(base, "/api/sessions", "POST", JSONObject().put("name", "자동 검증 참가자")).getJSONObject("data")
            val riderToken = other.getString("token")
            val place = JSONObject().put("name", "성수역 3번 출구").put("lat", 37.5444).put("lon", 127.0556).put("address", "서울 성동구")
            assertEquals(200, LocalApi.request(base, "/api/invites/$invitation/join", "POST", JSONObject().put("mode", "pickup").put("pickup", place), riderToken).getInt("status"))
            until(scenario, "Together.active().members[0].status==='accepted'")
            evaluate(scenario, "Together.carComponent.startTrip().then(()=>window.__departureFlowDone=true);true")
            until(scenario, "Together.active().trip.phase==='driving'&&window.__departureFlowDone")
            val navigationDeadline = System.currentTimeMillis() + 25000
            val navigationPrefs = instrumentation.targetContext.getSharedPreferences("together_session", 0)
            while (System.currentTimeMillis() < navigationDeadline && (!focusedWindow().contains("ai.umos.maps.android.navigation.app") || !navigationPrefs.getBoolean("navigationConfirmed", false))) Thread.sleep(200)
            returnToPickup(scenario)
            val aid = appointment.getString("id")
            assertEquals(403, LocalApi.request(base, "/api/appointments/$aid/actions", "POST", JSONObject().put("action", "board"), riderToken).getInt("status"))
            assertEquals(200, LocalApi.request(base, "/api/appointments/$aid/actions", "POST", JSONObject().put("action", "arrive"), token).getInt("status"))
            LocalApi.request(base, "/api/appointments/$aid/member", "POST", JSONObject().put("ready", "waiting").put("action", "board_confirm"), riderToken)
            until(scenario, "!!Together.active().members[0].boarding_confirmed_at")
            evaluate(scenario, "Together.carComponent.board();true")
            until(scenario, "Together.active().trip.phase==='destination'")
            LocalApi.request(base, "/api/appointments/$aid/actions", "POST", JSONObject().put("action", "park"), token)
            until(scenario, "Together.active().trip.phase==='parked'")
            evaluate(scenario, "Together.native('cancelNavigation');true")
            evaluate(scenario, "Together.carComponent.finish();true")
            until(scenario, "Together.active().trip.phase==='finished'&&Together.carComponent.state.screen==='record'")
            assertEquals("true", evaluate(scenario, "document.body.innerText.includes('이동 완료') && document.body.innerText.includes('자동 검증 약속')"))
            assertEquals("null", evaluate(scenario, "Together.active().members[0].home_at"))
            capture("native-pickup-record.png")
            val result = LocalApi.request(base, "/api/appointments/$aid/member", "POST", JSONObject().put("action", "home"), riderToken)
            assertEquals(200, result.getInt("status"))
            until(scenario, "!!Together.active().members[0].home_at")
        }
    }
    @Test fun nativeLocationSharingStartsAndStopsWithConsent() {
        val pkg = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.grantRuntimePermission(pkg, android.Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(pkg, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(pkg, "pleos.car.permission.FUSED_LOCATION")
        launch().use { scenario ->
            until(scenario, "!!(window.Together&&Together.app.user&&!Together.app.loading)")
            val meeting = java.time.Instant.now().plusSeconds(7200).toString()
            evaluate(scenario, "Together.create({name:'위치 공유 자동 검증',meeting_at:'$meeting',origin:Together.app.places[0],destination:Together.app.places[1],mode:'car',members:[]}).then(()=>window.__gpsAppointment=true);true")
            until(scenario, "!!window.__gpsAppointment")
            evaluate(scenario, "Together.locate().then(p=>window.__gps=p).catch(e=>window.__gpsError=e.message);true")
            until(scenario, "!!window.__gps")
            assertEquals("true", evaluate(scenario, "Number.isFinite(window.__gps.lat) && Number.isFinite(window.__gps.lon) && window.__gps.mock && (window.__gps.source==='pleos-fused'||Math.abs(window.__gps.lat-37.5444)<0.01 && Math.abs(window.__gps.lon-127.0556)<0.01)"))
            android.util.Log.i("TogetherTest", "LOCATION source " + evaluate(scenario, "window.__gps.source||Together.app.nativeStatus.locationSource"))
            evaluate(scenario, "Together.action('depart').then(()=>Together.member({share:true})).then(()=>Together.startTracking()).then(()=>window.__tracking=true).catch(e=>window.__gpsError=e.message);true")
            until(scenario, "!!window.__tracking")
            until(scenario, "!!Together.active().owner.location", 35)
            assertEquals("true", evaluate(scenario, "Together.active().owner.location.mock && Together.active().track.length>0"))
            evaluate(scenario, "Together.carComponent.setState({screen:'moving'});true")
            capture("native-location-sharing.png")
            evaluate(scenario, "Together.member({share:false}).then(()=>window.__stopped=true);true")
            until(scenario, "!!window.__stopped && !Together.active().owner.share && Together.active().owner.location===null")
            evaluate(scenario, "Together.action('cancel').then(()=>window.__cancelled=true);true")
            until(scenario, "!!window.__cancelled")
        }
    }
    @Test fun bridgeIsAbsentFromUntrustedOrigin() {
        launch().use { scenario ->
            until(scenario, "!!window.TogetherNative")
            scenario.onActivity { activity -> ((activity.findViewById<FrameLayout>(android.R.id.content)).getChildAt(0) as WebView).loadDataWithBaseURL("https://untrusted.invalid/", "<html><body>origin isolation test</body></html>", "text/html", "utf-8", null) }
            until(scenario, "document.body.innerText==='origin isolation test'")
            assertEquals("\"undefined\"", evaluate(scenario, "typeof TogetherNative"))
        }
    }
    @Test fun pleosServicesReturnRealAvailabilityAndNavigationResult() {
        launch().use { scenario ->
            until(scenario, "!!(window.Together&&Together.app.user&&!Together.app.loading)")
            until(scenario, "!!(Together.app.nativeStatus.navigation&&Together.app.nativeStatus.tts&&Together.app.nativeStatus.stt)", 20)
            evaluate(scenario, "Together.native('sdkStatus').then(s=>window.__sdk=s);true")
            until(scenario, "!!window.__sdk")
            android.util.Log.i("TogetherTest", "SDK availability " + evaluate(scenario, "JSON.stringify({navigation:__sdk.navigation,tts:__sdk.tts,ttsError:__sdk.ttsError,stt:__sdk.stt,sttError:__sdk.sttError,vehicle:__sdk.vehicle})"))
            assertEquals("true", evaluate(scenario, "Together.app.nativeStatus.automotive===true"))
            if (evaluate(scenario, "Together.app.nativeStatus.navigationConnected===true") == "true") {
                evaluate(scenario, "Together.native('navigate',{appointmentId:'0123456789abcdef01234567',foreground:false,stops:[{id:'dest',name:'서울숲 공영주차장',lat:37.5445,lon:127.0438,address:'서울 성동구'}]}).then(r=>window.__naviRequest=r).catch(e=>window.__naviError=e.message);true")
                until(scenario, "!!(window.__naviRequest||window.__naviError)")
                assertEquals("true", evaluate(scenario, "!!(__naviRequest&&__naviRequest.requested&&__naviRequest.waypoints===0)"))
                until(scenario, "['ready','error','timeout','external'].includes(Together.app.nativeStatus.navigation)", 22)
                android.util.Log.i("TogetherTest", "NAVI real outcome " + evaluate(scenario, "JSON.stringify({state:Together.app.nativeStatus.navigation,message:Together.app.nativeStatus.navigationMessage,eta:Together.app.nativeStatus.navigationEta})"))
                assertEquals("\"ready\"", evaluate(scenario, "Together.app.nativeStatus.navigation"))
                if (evaluate(scenario, "Together.app.nativeStatus.navigation==='ready'") == "true") {
                    scenario.recreate()
                    until(scenario, "!!(window.Together&&Together.app.user&&Together.app.nativeStatus.navigationAid==='0123456789abcdef01234567'&&Together.app.nativeStatus.navigationEta)")
                    android.util.Log.i("TogetherTest", "NAVI resume binding verified")
                }
                evaluate(scenario, "Together.native('cancelNavigation');true")
                until(scenario, "Together.app.nativeStatus.navigation==='cancelled'")
                // The OEM speaks its cancellation phrase before releasing audio.
                // Verify app synthesis after that utterance, not during it.
                Thread.sleep(2500)
            }
            if (evaluate(scenario, "Together.app.nativeStatus.tts==='ready'") == "true") {
                evaluate(scenario, "window.__ttsEvents=[];window.addEventListener('together:native',e=>{if(e.detail.tts)__ttsEvents.push(e.detail.tts);});Together.native('speak',{text:'함께가기 연결을 확인했어요.'}).then(()=>window.__spoken=true).catch(e=>window.__speechFailure=e.message);true")
                until(scenario, "!!(window.__spoken||window.__speechFailure)")
                until(scenario, "__ttsEvents.includes('unavailable')||__ttsEvents.includes('speaking')&&__ttsEvents[__ttsEvents.length-1]==='ready'", 20)
                android.util.Log.i("TogetherTest", "TTS real outcome " + evaluate(scenario, "JSON.stringify({events:__ttsEvents,error:Together.app.nativeStatus.ttsError})"))
            }
            evaluate(scenario, "Together.carComponent.setState({sheet:'connect'});true")
            until(scenario, "document.body.innerText.includes('음성 안내')")
            capture("native-sdk-connections.png")
        }
    }
    @Test fun gleoIntentUsesTheAuthenticatedSelectedAppointment() {
        launch().use { scenario ->
            until(scenario, "!!(window.Together&&Together.app.user&&!Together.app.loading)")
            val meeting = java.time.Instant.now().plusSeconds(7200).toString()
            evaluate(scenario, "Together.create({name:'Gleo 연동 검증',meeting_at:'$meeting',origin:Together.app.places[0],destination:Together.app.places[1],mode:'car',members:[]}).then(()=>window.__gleoReady=true);true")
            until(scenario, "!!window.__gleoReady")
            val prefs = instrumentation.targetContext.getSharedPreferences("together_session", 0)
            prefs.edit().remove("gleoLastStatus").commit()
            scenario.onActivity { it.startActivity(Intent("GET_PICKUP_STATUS").setPackage(instrumentation.targetContext.packageName).putExtra("ACTION_PARAM", "{}")) }
            val deadline = System.currentTimeMillis() + 7000
            while (System.currentTimeMillis() < deadline && prefs.getString("gleoLastStatus", null) == null) Thread.sleep(100)
            assertEquals("success", prefs.getString("gleoLastStatus", null))
            assertEquals("GET_PICKUP_STATUS", prefs.getString("gleoLastAction", null))
            until(scenario, "Together.active().name==='Gleo 연동 검증'&&Together.active().trip.phase==='idle'")
            android.util.Log.i("TogetherTest", "GLEO actual activity intent and authenticated API callback verified; vendor activation not tested")
            evaluate(scenario, "Together.action('cancel');true")
        }
    }
    @Test fun gleoContractLimitsFunctionsAndProtectsAppointmentSelection() {
        val schema = JSONObject(instrumentation.targetContext.assets.open("schema.json").bufferedReader().use { it.readText() })
        val calls = schema.getJSONArray("actionCalls")
        assertEquals(3, calls.length())
        assertEquals(GleoContract.actions, (0 until calls.length()).map { calls.getJSONObject(it).getJSONObject("function").getString("name") }.toSet())
        for (invalid in listOf("{\"api\":\"/api/state\"}", "[]", "x".repeat(2049))) {
            try { GleoContract.validate("GET_PICKUP_STATUS", invalid); fail("Unexpected parameters accepted") } catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
        }
        fun appointment(id: String, phase: String) = JSONObject().put("id", id).put("trip", JSONObject().put("phase", phase))
        val state = JSONObject().put("appointments", org.json.JSONArray().put(appointment("a", "idle")).put(appointment("b", "driving")).put(appointment("c", "finished")))
        assertNull(GleoContract.select(state, null))
        assertEquals("b", GleoContract.select(state, "b")!!.getString("id"))
        assertNull(GleoContract.select(state, "c"))
        val response = GleoContract.response("GET_PICKUP_STATUS", "success", "Status retrieved.", JSONObject().put("phase", "driving"))
        assertEquals("driving", org.json.JSONArray(response.getString("data")).getJSONObject(0).getString("phase"))
        val a = JSONObject("{\"trip\":{\"phase\":\"driving\",\"active_pickups\":[\"first\",\"skipped\",\"next\"],\"index\":2,\"boarded\":[\"first\"]}}")
        val route = JSONObject("{\"stops\":[{\"kind\":\"start\",\"id\":\"start\"},{\"kind\":\"pickup\",\"id\":\"first\"},{\"kind\":\"pickup\",\"id\":\"skipped\"},{\"kind\":\"pickup\",\"id\":\"next\"},{\"kind\":\"dest\",\"id\":\"dest\"}]}")
        val remaining = GleoContract.remainingStops(a, route)
        assertEquals(2, remaining.length()); assertEquals("next", remaining.getJSONObject(0).getString("id"))
        assertEquals("dest", remaining.getJSONObject(1).getString("id"))
    }
}

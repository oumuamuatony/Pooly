package io.pickuptogether.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Disposable records and real console seats. Demo mode supplies no GPS; strict mode injects a test coordinate. */
@RunWith(AndroidJUnit4::class)
class AutoPickupConsoleTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val base = "http://127.0.0.1:59583"
    private fun request(path: String, body: JSONObject? = null, token: String = ""): JSONObject {
        val r=LocalApi.request(base,path,if(body==null)"GET" else "POST",body,token)
        assertTrue("API ${r.optInt("status")}",r.getInt("status") in 200..299);return r.getJSONObject("data")
    }
    @Test fun consoleOccupancyAdvancesPickupWithoutOpeningDoor() {
        val scenario=ActivityScenario.launch<MainActivity>(Intent(inst.targetContext,MainActivity::class.java).putExtra("apiUrl",base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { runFlow() } finally { scenario.close() }
    }
    private fun runFlow() {
        val user=request("/api/sessions",JSONObject().put("name","SDK test driver"));val token=user.getString("token")
        val contact=request("/api/contacts",JSONObject().put("name","SDK test passenger"),token)
        val p=JSONObject().put("name","Controlled pickup").put("lat",37.5445).put("lon",127.056)
        val dest=JSONObject().put("name","Controlled destination").put("lat",37.5472).put("lon",127.0474)
        val a=request("/api/appointments",JSONObject().put("name","Console occupancy validation").put("meeting_at",Instant.now().plusSeconds(7200).toString()).put("origin",p).put("destination",dest).put("members",JSONArray().put(JSONObject().put("id",contact.getString("id")).put("mode","pickup"))),token)
        val aid=a.getString("id");val invite=a.getJSONArray("members").getJSONObject(0).getString("invite_token")
        val rider=request("/api/sessions",JSONObject().put("name","SDK test passenger")).getString("token")
        request("/api/invites/$invite/join",JSONObject().put("drives",false).put("pickup",p),rider)
        request("/api/seat-profile",JSONObject().put("enabled",true).put("cold_heat",1).put("hot_ventilation",1),rider)
        request("/api/appointments/$aid/actions",JSONObject().put("action","depart"),token)
        inst.runOnMainSync { PleosRuntime.start(inst.targetContext);PleosRuntime.vehicle?.setJourneyActive(true) }
        val readyUntil=System.currentTimeMillis()+25000
        var ready=false
        while(System.currentTimeMillis()<readyUntil) {
            val ns=PleosRuntime.state()
            if(PickupPolicy().parked(ns,System.currentTimeMillis()) && ns.optJSONObject("vehicleSeats")?.optJSONObject("ROW_1_RIGHT")?.optBoolean("occupied",true)==false && ns.optString("vehicleDriverSeat").isNotEmpty()) {ready=true;break}
            Thread.sleep(500)
        }
        assertTrue("SDK vacancy/park signals unavailable: ${PleosRuntime.state()}",ready)
        // This test owns the previous "Controlled destination" route; stop it so its real GPS
        // callbacks cannot race the explicitly controlled geofence coordinate used by this fixture.
        val navReady=java.util.concurrent.CountDownLatch(1)
        inst.runOnMainSync { PleosRuntime.navigation?.whenReady { PleosRuntime.navigation?.cancel();PleosRuntime.navigation?.forget();navReady.countDown() } }
        assertTrue(navReady.await(10,java.util.concurrent.TimeUnit.SECONDS));Thread.sleep(1500)
        val proximityRequired=request("/api/state",token=token).optBoolean("auto_pickup_proximity_required",true)
        val armed=java.util.concurrent.atomic.AtomicBoolean(false)
        val automation=PickupAutomation(base,token,aid) {
            val ns=PleosRuntime.state().put("navigationAid",aid)
            if(proximityRequired) ns.put("navigationPosition",JSONObject(p.toString()).put("updatedAt",System.currentTimeMillis()))
            else ns.remove("navigationPosition")
            if(armed.compareAndSet(false,true))android.util.Log.i("AutoPickupTest","SEAT_ARMED: console Passenger Occupied; doors closed; proximityRequired=$proximityRequired")
            ns
        }
        try {
            automation.start()
            var boarded: JSONObject?=null
            val deadline=System.currentTimeMillis()+75000
            while(System.currentTimeMillis()<deadline) {
                // No artificial arrival action: boarding is valid while phase is still driving.
                val all=request("/api/state",token=token).getJSONArray("appointments")
                val current=(0 until all.length()).map{all.getJSONObject(it)}.first{it.getString("id")==aid}
                if(current.getJSONArray("members").getJSONObject(0).has("boarded_at")){boarded=current;break}
                Thread.sleep(400)
            }
            assertNotNull("No automatic boarding from console occupancy: ${PleosRuntime.state()}",boarded)
            val binding=boarded!!.getJSONArray("members").getJSONObject(0).getJSONObject("seat_binding")
            assertEquals(if(proximityRequired)"nearby_occupancy" else "demo_occupancy",binding.getString("method"))
            assertEquals("ROW_1_RIGHT",binding.getString("seat"));assertEquals("destination",boarded.getJSONObject("trip").getString("phase"))
            android.util.Log.i("AutoPickupTest","PASS real console occupancy -> native match -> persisted seat binding -> next destination; binding=$binding")
            val thermalDeadline=System.currentTimeMillis()+12000
            while(System.currentTimeMillis()<thermalDeadline && PleosRuntime.state().optJSONObject("seatPersonalization")?.optString("status")!="applied")Thread.sleep(250)
            android.util.Log.i("AutoPickupTest","FINAL SETTINGS ${PleosRuntime.state()}")
            assertEquals("Personalized thermal setting was not applied", "applied",PleosRuntime.state().optJSONObject("seatPersonalization")?.optString("status"))
            val navigationDeadline=System.currentTimeMillis()+15000
            val prefs=inst.targetContext.getSharedPreferences("together_session",0)
            while(System.currentTimeMillis()<navigationDeadline && prefs.getString("navigationAppointment","")!=aid)Thread.sleep(250)
            assertEquals("Next destination was not submitted to Pleos",aid,prefs.getString("navigationAppointment",""))
            android.util.Log.i("AutoPickupTest","PASS combined: actual seat -> persisted rider profile -> thermal applied -> next navigation requested")
            android.util.Log.i("AutoPickupTest","Native navigation/settings state: ${PleosRuntime.state()}")
        } finally {
            automation.close();inst.runOnMainSync {
                val prefs=inst.targetContext.getSharedPreferences("together_session",0)
                if(prefs.getString("navigationAppointment","")==aid)PleosRuntime.navigation?.cancel()
                PleosRuntime.vehicle?.setJourneyActive(false)
            }
        }
    }
}

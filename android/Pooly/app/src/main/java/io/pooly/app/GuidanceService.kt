package io.pooly.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reconciles this app's route with its appointment while the WebView is behind OEM navigation. */
class GuidanceService : Service() {
    private val worker=Executors.newSingleThreadScheduledExecutor()
    private val ui=Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("pooly_session",MODE_PRIVATE) }
    private var observer: Int?=null
    @Volatile private var appointment=""
    @Volatile private var destroyed=false
    private var started=false
    private val base=PoolyApi.BASE
    private var token=""
    private var automation: PickupAutomation?=null
    private var automationAid=""
    private var lastDemoPositionAt=0L
    private var lastEtaAt=0L
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val aid=intent?.getStringExtra("appointmentId").orEmpty()
        if(!aid.matches(Regex("[a-f0-9]{24}"))){stopSelf();return START_NOT_STICKY}
        appointment=aid
        PleosRuntime.start(applicationContext)
        PleosRuntime.vehicle?.setJourneyActive(true)
        token=prefs.getString("token","").orEmpty()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("guidance_updates","여정 알림 동기화",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,703,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"guidance_updates").setSmallIcon(R.drawable.ic_pickup)
            .setContentTitle("이동 상태 동기화 중").setContentText("차량 내비와 일정 상태를 연결해요").setContentIntent(open).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(703,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(703,notification)
        if(prefs.getString("navigationAppointment","")==aid&&(automationAid!=aid||automation==null)){
            automation?.close();automationAid=aid;automation=PickupAutomation(base,token,aid).also { it.start() }
        }
        if(!started){
            started=true
            observer=PleosRuntime.observe { data->
                if(data.has("navigationAid")&&data.optString("navigationAid")!=appointment&&prefs.getString("navigationAppointment","")!=appointment){automation?.close();automation=null}
            }
            worker.scheduleWithFixedDelay({if(!destroyed)try{reconcile()}catch(_:Exception){/* Offline intervals retain the durable outbox and never finish a trip. */}},0,2,TimeUnit.SECONDS)
        }
        return START_REDELIVER_INTENT
    }
    private fun reconcile() {
        val response=PoolyApi.request(base,"/api/state","GET",token=token)
        if(response.getInt("status") in listOf(401,403)){stopSelf();return}
        if(response.getInt("status") !in 200..299)return
        val raw=response.getJSONObject("data").getJSONArray("appointments")
        val rows=(0 until raw.length()).map { raw.getJSONObject(it) }
        for(event in NavigationOutbox.pending(prefs)) {
            val aid=event.optString("appointmentId")
            val a=rows.find { it.optString("id")==aid }
            if(a==null||!a.optBoolean("is_driver")||a.getJSONObject("trip").optString("phase") in setOf("idle","finished","cancelled")){
                NavigationOutbox.ack(prefs,event);continue
            }
            val result=PoolyApi.request(base,"/api/appointments/$aid/actions","POST",JSONObject().put("action","navigation_status")
                .put("status",event.getString("status")).put("session_id",event.getString("sessionId"))
                .put("requested_at",event.getLong("requestedAt")).put("reason",event.optString("reason")),token)
            if(result.getInt("status") in 200..299||result.getInt("status") in listOf(403,409))NavigationOutbox.ack(prefs,event)
        }
        val aid=appointment
        val active=rows.find { it.optString("id")==aid }
        val phase=active?.getJSONObject("trip")?.optString("phase")
        val bound=prefs.getString("navigationAppointment","")==aid
        val completed = active?.optBoolean("is_driver") == true &&
            phase in setOf("destination", "parked") && completeAtDestination(active)
        val ended=completed||active==null||phase in setOf("idle","finished","cancelled")
        val remoteStopped=active?.getJSONObject("trip")?.optJSONObject("navigation")?.let {
            it.optString("status")=="stopped"&&it.optString("session_id")==prefs.getString("navigationSessionId","")
        }==true
        if(ended||remoteStopped){
            ui.post {
                if(!destroyed&&appointment==aid&&prefs.getString("navigationAppointment","")==aid){
                    automation?.close();automation=null;stopService(Intent(this,JourneyService::class.java))
                    PleosRuntime.navigation?.endAppointment(aid)
                }
            }
        }
        if (!ended && bound && active?.optBoolean("is_driver") == true) publishTripStatus(aid)
        if(!bound&&NavigationOutbox.pending(prefs).isEmpty())stopSelf()
    }

    private fun publishTripStatus(aid: String) {
        val state = PleosRuntime.state()
        if (state.optString("navigationAid") != aid) return
        val session = state.optJSONObject("navigationSession") ?: return
        if (session.optString("appointmentId") != aid) return
        val now = System.currentTimeMillis()
        if (session.optString("status") == "guiding" && now - lastEtaAt >= 5000) {
            val stops = JSONObject()
            val waypoints = state.optJSONArray("navigationWaypoints")
            val reports = (0 until (waypoints?.length() ?: 0)).mapNotNull { waypoints?.optJSONObject(it) } +
                listOfNotNull(state.optJSONObject("navigationEta"))
            for (eta in reports) {
                val stop = eta.optString("stopId")
                val observed = eta.optLong("updatedAt")
                val seconds = eta.optDouble("seconds")
                if (stop.isNotBlank() && seconds in 0.0..86400.0 &&
                    observed >= session.optLong("requestedAt") && now - observed in 0..15000) {
                    stops.put(stop, JSONObject().put("seconds", seconds).put("observed_at", observed))
                }
            }
            if (stops.length() > 0) {
                lastEtaAt = now
                PoolyApi.request(base, "/api/appointments/$aid/actions", "POST", JSONObject()
                    .put("action", "navigation_eta").put("session_id", session.getString("sessionId")).put("stops", stops), token)
            }
        }
        // A public demo account never uploads a physical vehicle's GPS.
        if (Build.HARDWARE !in setOf("ranchu", "goldfish") ||
            !Build.MODEL.contains("emulator", ignoreCase = true) || now - lastDemoPositionAt < 5000) return
        val position = state.optJSONObject("navigationPosition") ?: return
        val observed = position.optLong("updatedAt")
        if (observed < session.optLong("requestedAt") || now - observed !in 0..15000) return
        lastDemoPositionAt = now
        val body = JSONObject().put("lat", position.getDouble("lat")).put("lon", position.getDouble("lon"))
            .put("mock", true).put("observed_at", observed)
        PoolyApi.request(base, "/api/appointments/$aid/demo-position", "POST", body, token)
    }

    private fun completeAtDestination(active: JSONObject): Boolean {
        val aid = active.getString("id")
        val parking = active.optJSONObject("parking")
        val stopId = if (parking == null) "dest" else "lot"
        val target = parking ?: active.getJSONObject("destination")
        val state = PleosRuntime.state()
        val now = System.currentTimeMillis()
        val arrival = state.optJSONObject("navigationFinalArrival")
        val session = state.optJSONObject("navigationSession")
        val evidence = JSONObject().put("action", "auto_finish")
        val position: JSONObject
        if (arrival != null && arrival.optString("appointmentId") == aid &&
            arrival.optString("stopId") == stopId &&
            arrival.optString("sessionId") == session?.optString("sessionId") &&
            now - arrival.optLong("updatedAt") in 0..20_000) {
            position = arrival
            evidence.put("source", "arrival").put("session_id", arrival.getString("sessionId"))
        } else {
            if (state.optString("navigationAid") != aid ||
                state.optString("navigationMode") !in setOf("ROUTE_GUIDING", "SIMULATION_GUIDING")) return false
            if (session == null) return false
            val eta = state.optJSONObject("navigationEta") ?: return false
            position = state.optJSONObject("navigationPosition") ?: return false
            val meters = eta.optInt("meters", -1)
            val requestedAt = session.optLong("requestedAt")
            if (session.optString("appointmentId") != aid || session.optString("status") != "guiding" ||
                eta.optString("stopId") != stopId || meters !in 0..80 ||
                now - eta.optLong("updatedAt") !in 0..12_000 ||
                now - position.optLong("updatedAt") !in 0..12_000 ||
                position.optLong("updatedAt") < requestedAt) return false
            evidence.put("source", "nearby").put("session_id", session.getString("sessionId")).put("remaining_m", meters)
        }
        if (appointment != aid || destroyed || PickupPolicy.metres(target, position) > 80) return false
        evidence.put("lat", position.getDouble("lat")).put("lon", position.getDouble("lon"))
            .put("observed_at", position.getLong("updatedAt"))
        val response = PoolyApi.request(base, "/api/appointments/$aid/actions", "POST", evidence, token)
        return response.getInt("status") in 200..299
    }
    override fun onDestroy(){destroyed=true;automation?.close();ui.removeCallbacksAndMessages(null);PleosRuntime.vehicle?.setJourneyActive(false);PleosRuntime.removeObserver(observer);worker.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
    override fun onBind(intent: Intent?): IBinder?=null
}

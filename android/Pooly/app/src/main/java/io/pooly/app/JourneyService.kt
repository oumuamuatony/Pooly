package io.pooly.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in foreground journey tracking; no always-on background location permission. */
class JourneyService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var appointment = ""; private val base = PoolyApi.BASE; private var token = ""
    private var started = false; private var lastSent = 0L; private var newestNotification = 0L; private var notificationsInitialized = false
    private lateinit var locations: LocationManager
    private var fused: PleosLocation? = null
    private val ui = Handler(Looper.getMainLooper())
    private var androidUpdates = false
    private var lastFused = 0L
    private var destroyed = false
    private val listener = LocationListener { location ->
        if (location.accuracy <= 100) {
            val body = JSONObject().put("lat", location.latitude).put("lon", location.longitude).put("accuracy", location.accuracy.toDouble()).put("mock", location.isFromMockProvider || android.os.Build.MODEL.contains("emulator", true))
            send(body)
        }
    }
    override fun onCreate() {
        super.onCreate(); locations = getSystemService(LOCATION_SERVICE) as LocationManager
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("journey", "진행 중인 이동", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("updates", "약속 알림", NotificationManager.IMPORTANCE_DEFAULT))
    }
    @Suppress("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { executor.execute { try { PoolyApi.request(base, "/api/appointments/$appointment/member", "POST", JSONObject().put("share", false), token) } finally { stopSelf() } }; return START_NOT_STICKY }
        appointment = intent?.getStringExtra("appointmentId") ?: ""
        token = intent?.getStringExtra("token") ?: ""
        val stop = PendingIntent.getService(this, 2, Intent(this, JourneyService::class.java).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(200, NotificationCompat.Builder(this, "journey").setSmallIcon(R.drawable.ic_pickup).setContentTitle("Pooly · 이번 이동 위치 공유 중").setContentText("이동 종료 또는 공유 중지 시 꺼져요").setContentIntent(open).addAction(0, "공유 중지", stop).setOngoing(true).build())
        if (appointment.isEmpty() || token.isEmpty() || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY }
        if (!started) {
            started = true
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                val sdk = PleosLocation(this) { state -> android.util.Log.d("PoolyLocation", "Fused ${state.optString("fusedLocation")}") }; fused = sdk
                if (!sdk.start({ point -> ui.post { if (!destroyed) { lastFused = System.currentTimeMillis(); if (androidUpdates) { locations.removeUpdates(listener); androidUpdates = false }; send(point) } } }, { ui.post { fallbackLocation() } })) fallbackLocation()
                ui.postDelayed({ if (lastFused == 0L) fallbackLocation() }, 5000)
            } else fallbackLocation()
            executor.scheduleWithFixedDelay({ sync() }, 0, 8, TimeUnit.SECONDS)
        }
        return START_NOT_STICKY
    }
    private fun send(body: JSONObject) {
        if (destroyed || System.currentTimeMillis() - lastSent < 5000) return
        lastSent = System.currentTimeMillis()
        executor.execute { try { val r = PoolyApi.request(base, "/api/appointments/$appointment/location", "POST", body, token); if (r.getInt("status") in listOf(401, 403, 404, 409)) stopSelf() } catch (_: Exception) {} }
    }
    @Suppress("MissingPermission")
    private fun fallbackLocation() {
        if (destroyed || androidUpdates) return
        androidUpdates = true
        locations.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }.forEach { locations.requestLocationUpdates(it, 5000, 5f, listener, Looper.getMainLooper()) }
    }
    private fun sync() {
        if (fused != null && System.currentTimeMillis() - lastFused > 20000) ui.post { fallbackLocation() }
        try {
            val response = PoolyApi.request(base, "/api/state", "GET", token = token)
            if (response.getInt("status") in listOf(401, 403)) { stopSelf(); return }
            val data = response.getJSONObject("data"); val all = data.getJSONArray("appointments")
            val a = (0 until all.length()).map { all.getJSONObject(it) }.find { it.getString("id") == appointment }
            if (a == null) { stopSelf(); return }
            val mine = if (a.getString("me_id") == "owner") a.getJSONObject("owner") else a.getJSONArray("members").let { members -> (0 until members.length()).map { members.getJSONObject(it) }.find { it.getString("id") == a.getString("me_id") } }
            if (mine == null || !mine.optBoolean("share") || mine.isNull("started_at") || a.getJSONObject("trip").getString("phase") in listOf("finished", "cancelled") || !mine.isNull("arrived_at")) { stopSelf(); return }
            val notices = data.getJSONArray("notifications")
            val latest = if (notices.length() > 0) notices.getJSONObject(0).getLong("id") else 0
            if (notificationsInitialized && latest > newestNotification && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                val n = notices.getJSONObject(0)
                NotificationManagerCompat.from(this).notify(201, NotificationCompat.Builder(this, "updates").setSmallIcon(R.drawable.ic_pickup).setContentTitle(n.getString("title")).setContentText(n.getString("body")).setSilent(true).setAutoCancel(true).build())
            }
            newestNotification = latest; notificationsInitialized = true
        } catch (_: Exception) { /* Temporary offline state must not erase the journey. */ }
    }
    override fun onDestroy() { destroyed = true; ui.removeCallbacksAndMessages(null); fused?.close(); locations.removeUpdates(listener); executor.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

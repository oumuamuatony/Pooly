package io.pooly.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Gleo's exported entry point. External calls cannot silently send messages or replace routes. */
class GleoActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var responded = false
    private var navigation: PleosNavigation? = null
    private var sdkObserver: Int? = null
    private var actionName = ""
    private val prefs by lazy { getSharedPreferences("pooly_session", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavigationWindows.register(this)
        setContentView(TextView(this).apply { text = "Pooly · 약속 확인 중…"; textSize = 26f; setPadding(40, 40, 40, 40) })
        try {
            check(packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
            actionName = GleoContract.validate(intent.action, intent.getStringExtra("ACTION_PARAM"))
        } catch (_: Exception) { if (intent.action in GleoContract.actions) { actionName = intent.action!!; reply("fail", "The action parameters are invalid.") }; finish(); return }
        ui.postDelayed({ if (!responded) { reply("fail", "The app could not respond in time."); finish() } }, 6000)
        io.execute {
            try {
                val token = prefs.getString("token", "").orEmpty()
                check(token.isNotBlank())
                val state = request("/api/state").getJSONObject("data")
                val a = GleoContract.select(state, prefs.getString("appointmentId", null))
                ui.post { if (!isFinishing && !responded) {
                    if (a == null) { reply("asking", "Open the app and select an active appointment first."); finish() }
                    else handle(a)
                } }
            } catch (_: Exception) { ui.post { if (!isFinishing) { reply("fail", "The appointment is unavailable. Open the app and check the Pooly server connection."); finish() } } }
        }
    }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val r = PoolyApi.request(PoolyApi.BASE, path, method, body, prefs.getString("token", "").orEmpty(), 1800)
        check(r.getInt("status") in 200..299)
        return r
    }
    private fun handle(a: JSONObject) {
        val summary = GleoContract.summary(a)
        if (actionName == "GET_PICKUP_STATUS") { reply("success", "The selected appointment status was retrieved.", summary); finish(); return }
        if (!a.optBoolean("is_driver")) { reply("fail", "Only this appointment's driver can perform this action."); finish(); return }
        if (actionName == "NOTIFY_PICKUP_ARRIVAL" && a.getJSONObject("trip").getString("phase") != "arrived") {
            reply("asking", "Confirm arrival at the pickup point in the app first."); finish(); return
        }
        // Gleo allows asking for confirmation. Send within the documented seven-second window.
        reply("asking", "Confirm this action on the vehicle display.", summary)
        val navigating = actionName == "START_PICKUP_NAVIGATION"
        AlertDialog.Builder(this).setTitle(if (navigating) "차량 내비로 안내할까요?" else "일행에게 도착을 알릴까요?")
            .setMessage(a.getString("name") + if (navigating) "\n남은 픽업 장소와 목적지로 안내를 요청해요." else "\n현재 픽업 대상에게 도착 알림을 보내요.")
            .setNegativeButton("취소") { _, _ -> finish() }.setOnCancelListener { finish() }
            .setPositiveButton(if (navigating) "안내 시작" else "알림 보내기") { _, _ -> execute(a.getString("id"), navigating) }.show()
    }
    private fun execute(aid: String, navigating: Boolean) {
        io.execute {
            try {
                // Revalidate against the current server state after confirmation.
                val state = request("/api/state").getJSONObject("data")
                var a = GleoContract.select(state, aid)
                check(a?.optString("id") == aid && a.optBoolean("is_driver"))
                if (!navigating) {
                    request("/api/appointments/$aid/actions", "POST", JSONObject().put("action", "notify"))
                    ui.post { android.widget.Toast.makeText(this, "도착 알림을 보냈어요.", android.widget.Toast.LENGTH_LONG).show(); finish() }
                } else {
                    if(a!!.getJSONObject("trip").optString("phase")=="idle")a=request("/api/appointments/$aid/actions","POST",JSONObject().put("action","depart")).getJSONObject("data")
                    val route = request("/api/appointments/$aid/route").getJSONObject("data")
                    val remaining = GleoContract.remainingStops(a!!, route)
                    ui.post {
                        if (!isFinishing) {
                            PleosRuntime.start(applicationContext)
                            navigation = PleosRuntime.navigation
                            sdkObserver = PleosRuntime.observe { event ->
                                if (event.optString("navigation") in setOf("ready", "error", "unavailable")) { android.widget.Toast.makeText(this, event.optString("navigationMessage", "차량 내비 응답을 확인했어요."), android.widget.Toast.LENGTH_LONG).show() }
                            }
                            navigation?.whenReady { try { navigation?.navigate(remaining, appointmentId = aid); startForegroundService(Intent(this, GuidanceService::class.java).putExtra("appointmentId",aid)) } catch (_: Exception) { failed() } }
                            ui.postDelayed({ if (!isFinishing) finish() }, 18000)
                        }
                    }
                }
            } catch (_: Exception) { ui.post { failed() } }
        }
    }
    private fun failed() { if (!isFinishing) { android.widget.Toast.makeText(this, "처리하지 못했어요. 앱에서 약속과 연결 상태를 확인해 주세요.", android.widget.Toast.LENGTH_LONG).show(); finish() } }
    private fun reply(status: String, message: String, data: JSONObject? = null) {
        if (responded) return
        responded = true
        val response = GleoContract.response(actionName, status, message, data)
        prefs.edit().putString("gleoLastAction", actionName).putString("gleoLastStatus", status).putLong("gleoLastResponseAt", System.currentTimeMillis()).apply()
        // Limit the callback to the system Gleo package; never broadcast appointment data to all apps.
        try {
            val info = packageManager.getApplicationInfo(BuildConfig.GLEO_PACKAGE, 0)
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").setPackage(BuildConfig.GLEO_PACKAGE).putExtra("packageName", packageName).putExtra("response", response.toString()))
        } catch (_: PackageManager.NameNotFoundException) {}
        android.util.Log.i("PoolyGleo", "${actionName}: $status")
    }
    override fun onDestroy() { PleosRuntime.removeObserver(sdkObserver); NavigationWindows.unregister(this); ui.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy() }
}

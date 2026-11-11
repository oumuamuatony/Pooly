package io.pickuptogether.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Local emulator diagnostics. Constructed only in the separately installed demo build. */
class DemoSupport(private val activity: Activity, private val navigation: PleosNavigation?, private val snapshot: () -> JSONObject) {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val preferences = activity.getSharedPreferences("together_session", Context.MODE_PRIVATE)
    private var fused: PleosLocation? = null
    private var position: JSONObject? = null
    private var closed = false
    private val sending = AtomicBoolean(false)
    private var web: WebView? = null
    private val controls = EmulatorTestController(activity, io, { web?.reload() }, { web?.let { pageReady(it, "navigate") } })
    private val locations = activity.getSystemService(LocationManager::class.java)
    private var nmeaStarted = false
    private var validNmea = 0
    private var invalidNmea = 0
    private var lastNmea = ""
    private val nmea = OnNmeaMessageListener { message, _ ->
        val sentence = message.trim()
        val parts = sentence.removePrefix("$").split("*")
        val checksum = parts.first().fold(0) { sum, char -> sum xor char.code }
        if (parts.size == 2 && parts[1].toIntOrNull(16) == checksum) { validNmea++; lastNmea = sentence } else invalidNmea++
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            if (fused == null && activity.checkSelfPermission("pleos.car.permission.FUSED_LOCATION") == PackageManager.PERMISSION_GRANTED) {
                fused = PleosLocation(activity) {}.also { sdk -> sdk.start({ point -> position = point }, {}) }
            }
            if (!nmeaStarted && activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("MissingPermission")
                nmeaStarted = locations.addNmeaListener(nmea, ui)
            }
            navigation?.refreshPosition()
            val data = snapshot().put("nmea", JSONObject().put("valid", validNmea).put("invalid", invalidNmea).put("lastValid", lastNmea)).also { position?.let { point -> it.put("fusedPosition", point) } }
            preferences.edit().putString("demoTelemetry", data.toString()).apply()
            val token = preferences.getString("token", "").orEmpty()
            if (token.isNotEmpty() && sending.compareAndSet(false, true)) io.execute {
                try { LocalApi.request(BuildConfig.API_BASE_URL, "/api/demo/telemetry", "POST", data, token); controls.sync() } catch (_: Exception) {} finally { sending.set(false) }
            }
            ui.postDelayed(this, 2000)
        }
    }
    fun start() { check(EmulatorTestController.available()); ui.post(tick) }
    fun showControls() = controls.show()
    fun pageReady(web: WebView, command: String?) {
        this.web = web
        web.evaluateJavascript("""(function(){function mount(){
            var b=document.getElementById('demo-label');
            if(document.documentElement.dataset.navigationPanel){if(b)b.remove();return;}
            if(!b){b=document.createElement('button');b.id='demo-label';b.textContent='에뮬레이터 테스트';b.onclick=function(){if(window.Together)Together.native('demoControls').catch(function(){});};}
            var bar=document.querySelector('.home-footer'),nav=document.querySelector('.navr');
            if(!bar&&nav&&getComputedStyle(nav.closest('nav')).display!=='none')bar=nav;
            if(bar){if(b.parentNode!==bar)bar.appendChild(b);if(b.className!=='btn bp xs')b.className='btn bp xs';if(b.style.position)b.style.cssText='';}
            else if(!b.parentNode){b.style.cssText='position:fixed;right:16px;bottom:16px;z-index:99999;padding:8px 12px;background:#002c5f;color:white;border:0;border-radius:8px;font:15px sans-serif';document.body.appendChild(b);}
        }mount();if(!window.__demoControlsObserver){window.__demoControlsObserver=new MutationObserver(mount);window.__demoControlsObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['data-navigation-panel']});}})();""", null)
        controls.command(command)
        if (command == "stop") {
            navigation?.cancel()
            activity.stopService(Intent(activity, JourneyService::class.java))
            web.evaluateJavascript("if(window.Together)Together.stopTracking();", null)
        }
        if (command == "navigate") web.evaluateJavascript("""(function(){var n=0;function go(){var t=window.Together;if(!t||!t.app.user||t.app.loading||!t.app.nativeStatus.navigationConnected){if(++n<30)setTimeout(go,500);return;}t.refresh(true).then(function(){return t.startTracking();}).catch(function(e){console.warn(e.message);}).then(function(){return t.navigate();}).catch(function(e){console.error('시연 내비: '+e.message);});}go();})();""", null)
    }
    fun close() { closed = true; controls.close(); web = null; ui.removeCallbacksAndMessages(null); if (nmeaStarted) locations.removeNmeaListener(nmea); fused?.close(); io.shutdownNow() }
}

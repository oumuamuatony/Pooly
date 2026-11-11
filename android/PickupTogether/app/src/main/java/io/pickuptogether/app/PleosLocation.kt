package io.pickuptogether.app

import android.content.Context
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import org.json.JSONObject

/** The SDK confidence field is not an Android accuracy radius. Keep it separate. */
class PleosLocation(context: Context, private val status: (JSONObject) -> Unit) {
    private val sdk = FusedLocation(context.applicationContext)
    private var initialized = false
    private var listener: FusedLocationListener? = null
    fun start(point: (JSONObject) -> Unit, failed: () -> Unit): Boolean {
        return try {
            if (!sdk.isSupported()) { status(JSONObject().put("fusedLocation", "unsupported")); return false }
            if (contextPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) { status(JSONObject().put("fusedLocation", "permission-needed")); return false }
            sdk.initialize(); initialized = true
            val callback = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    location ?: return
                    val age = System.currentTimeMillis() - location.timestampMillis
                    if (age !in -5000..60000 || !location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
                    status(JSONObject().put("fusedLocation", "receiving"))
                    // The existing server treats missing accuracy conservatively as 100m.
                    // Never convert SDK confidence to a fictional metre accuracy.
                    point(JSONObject().put("lat", location.latitude).put("lon", location.longitude)
                        .put("timestamp", location.timestampMillis).put("source", "pleos-fused")
                        .put("mock", android.os.Build.MODEL.contains("emulator", true))
                        .put("name", "현재 위치").put("address", "").apply {
                            if (location.heading.isFinite()) put("heading", location.heading.toDouble())
                            if (location.confidence.isFinite()) put("confidence", location.confidence.toDouble())
                        })
                }
                override fun onError(exception: Exception) {
                    status(JSONObject().put("fusedLocation", "error")); failed()
                }
            }
            listener = callback
            sdk.registerFusedLocationCallback(callback); sdk.getLastKnownLocation(callback)
            status(JSONObject().put("fusedLocation", "waiting")); true
        } catch (e: Exception) { android.util.Log.w("TogetherLocation", "Fused init ${e.javaClass.simpleName}: ${e.message?.take(160)}"); status(JSONObject().put("fusedLocation", "unavailable")); close(); false }
    }
    private val appContext = context.applicationContext
    private fun contextPermission() = appContext.checkSelfPermission("pleos.car.permission.FUSED_LOCATION")
    fun close() {
        try { listener?.let { sdk.unregisterFusedLocationCallback(it) } } catch (_: Exception) {}
        listener = null
        if (initialized) try { sdk.release() } catch (_: Exception) {}
        initialized = false
    }
}

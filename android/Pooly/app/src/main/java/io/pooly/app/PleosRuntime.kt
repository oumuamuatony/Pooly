package io.pooly.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** One SDK client per process. Recreating an Activity must not unregister a new client. */
object PleosRuntime {
    private val ui = Handler(Looper.getMainLooper())
    private val snapshot = JSONObject()
    private val observers = linkedMapOf<Int, (JSONObject) -> Unit>()
    private var nextObserver = 0
    var navigation: PleosNavigation? = null; private set
    var voice: PleosVoice? = null; private set
    var vehicle: PleosVehicle? = null; private set
    private var started = false
    fun publish(data: JSONObject) = emit(data)
    fun state(): JSONObject = synchronized(snapshot) { JSONObject(snapshot.toString()) }
    private fun emit(data: JSONObject) { ui.post {
        synchronized(snapshot) { data.keys().forEach { snapshot.put(it, data.get(it)) } }
        observers.values.toList().forEach { it(JSONObject(data.toString())) }
    } }
    fun ensureNavigation(context: Context): PleosNavigation? {
        if (navigation == null) try { navigation = PleosNavigation(context.applicationContext, ::emit) }
        catch (_: Exception) { emit(JSONObject().put("navigation", "unavailable").put("navigationConnected", false)) }
        return navigation
    }
    fun start(context: Context) {
        if (started) return
        started = true
        ensureNavigation(context)
        try { voice = PleosVoice(context.applicationContext, ::emit) } catch (_: Exception) { emit(JSONObject().put("tts", "unavailable").put("stt", "unavailable")) }
        try { vehicle = PleosVehicle(context.applicationContext, ::emit); vehicle?.refresh() } catch (_: Exception) { emit(JSONObject().put("vehicle", "unavailable")) }
    }
    fun observe(observer: (JSONObject) -> Unit): Int {
        val id = ++nextObserver; observers[id] = observer
        observer(JSONObject(snapshot.toString()))
        return id
    }
    fun removeObserver(id: Int?) { if (id != null) observers.remove(id) }
}

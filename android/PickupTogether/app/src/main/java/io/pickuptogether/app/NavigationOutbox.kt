package io.pickuptogether.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Durable latest state per native route session, including after OEM cancellation/process recreation. */
object NavigationOutbox {
    private val lock=Any()
    fun pending(prefs: SharedPreferences): List<JSONObject> = synchronized(lock) {
        val rows=runCatching { JSONArray(prefs.getString("navigationOutbox","[]")) }.getOrElse { JSONArray() }
        (0 until rows.length()).map { JSONObject(rows.getJSONObject(it).toString()) }
    }
    fun put(prefs: SharedPreferences, event: JSONObject) = synchronized(lock) {
        val rows=pending(prefs).filter { it.optString("sessionId")!=event.optString("sessionId") }+event
        prefs.edit().putString("navigationOutbox",JSONArray(rows.takeLast(64)).toString()).apply()
    }
    fun ack(prefs: SharedPreferences, event: JSONObject) = synchronized(lock) {
        val rows=pending(prefs).filterNot { it.optString("sessionId")==event.optString("sessionId")&&it.optString("status")==event.optString("status") }
        prefs.edit().putString("navigationOutbox",JSONArray(rows).toString()).apply()
    }
}

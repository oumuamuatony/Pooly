package io.pooly.app

import org.json.JSONArray
import org.json.JSONObject

object GleoContract {
    val actions = setOf("GET_PICKUP_STATUS", "START_PICKUP_NAVIGATION", "NOTIFY_PICKUP_ARRIVAL")
    fun validate(action: String?, raw: String?): String {
        require(action in actions) { "Unsupported action." }
        require((raw ?: "{}").length <= 2048) { "Invalid parameters." }
        // The documented ACTION_PARAM is a JSON object; reject arbitrary API commands.
        val parameters = JSONObject(raw ?: "{}")
        require(parameters.length() == 0) { "This action takes no parameters." }
        return action!!
    }
    fun select(state: JSONObject, selected: String?): JSONObject? {
        val all = state.getJSONArray("appointments")
        val active = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getJSONObject("trip").getString("phase") !in listOf("cancelled", "finished") }
        return if (!selected.isNullOrEmpty()) active.find { it.getString("id") == selected } else active.singleOrNull()
    }
    fun response(action: String, status: String, message: String, data: JSONObject? = null): JSONObject {
        require(status in setOf("success", "asking", "fail"))
        return JSONObject().put("functionName", action).put("status", status).put("message", message).put("mode", "voice").apply {
            data?.let { put("data", JSONArray().put(it).toString()) }
        }
    }
    fun summary(a: JSONObject): JSONObject {
        val trip = a.getJSONObject("trip")
        val nextId = (if (trip.optString("phase") == "idle") a.optJSONArray("pickup_order") else trip.optJSONArray("active_pickups"))?.optString(trip.optInt("index"))
        val members = a.getJSONArray("members")
        val next = (0 until members.length()).map { members.getJSONObject(it) }.find { it.optString("id") == nextId }
        return JSONObject().put("appointment", a.getString("name")).put("phase", trip.getString("phase"))
            .put("nextPassenger", next?.optString("name") ?: "").put("meetingTime", a.getString("meeting_at"))
    }
    fun remainingStops(a: JSONObject, route: JSONObject): JSONArray {
        val t = a.getJSONObject("trip")
        val ids = if (t.optString("phase") == "idle") a.getJSONArray("pickup_order") else t.optJSONArray("active_pickups") ?: JSONArray()
        val start = if (t.optString("phase") == "idle") 0 else t.optInt("index")
        val remaining = (start until ids.length()).map { ids.getString(it) }.toSet()
        val boarded = t.optJSONArray("boarded") ?: JSONArray()
        val completed = (0 until boarded.length()).map { boarded.getString(it) }.toSet()
        val stops = route.getJSONArray("stops")
        return JSONArray((0 until stops.length()).map { stops.getJSONObject(it) }.filter { it.optString("kind") != "start" && (it.optString("kind") != "pickup" || it.optString("id") in remaining && it.optString("id") !in completed) })
    }
}

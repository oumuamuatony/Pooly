package io.pickuptogether.app

import org.json.JSONObject
import kotlin.math.*

/** The active pickup is an inference, never an identity supplied by an occupancy sensor. */
class PickupPolicy {
    data class Match(val appointmentId: String, val memberId: String, val seat: String, val occupiedAt: Long, val observedAt: Long) {
        val id get() = "$appointmentId:$memberId:$seat:$occupiedAt"
    }
    private var key = ""
    private var armedAt = 0L
    private var consumed = ""
    fun reset() { key = ""; armedAt = 0 }
    fun current(a: JSONObject): JSONObject? {
        val trip = a.optJSONObject("trip") ?: return null
        val id = trip.optJSONArray("active_pickups")?.optString(trip.optInt("index")) ?: return null
        val members = a.optJSONArray("members") ?: return null
        return (0 until members.length()).map { members.getJSONObject(it) }.find { it.optString("id") == id && it.optString("status") == "accepted" && it.isNull("boarded_at") }
    }
    fun fresh(value: JSONObject?, now: Long, limit: Long = 6000): Boolean = value != null && now - value.optLong("updatedAt", 0) in 0..limit
    fun parked(ns: JSONObject, now: Long): Boolean = ns.optJSONObject("vehicleMotion")?.let {
        fresh(it, now) && it.optBoolean("parked") && it.optDouble("speedMps", Double.NaN).let { speed -> speed.isFinite() && abs(speed) <= .3 }
    } == true
    fun near(a: JSONObject, ns: JSONObject, now: Long): Boolean {
        val m = current(a) ?: return false
        val p = m.optJSONObject("pickup") ?: return false
        val location = ns.optJSONObject("navigationPosition") ?: return false
        return fresh(location, now, 10000) && metres(p, location) <= 80.0
    }
    fun update(a: JSONObject, ns: JSONObject, now: Long, requireProximity: Boolean = true): Match? {
        val m = current(a)
        val eligible = a.optBoolean("is_driver") && ns.optString("navigationAid") == a.optString("id") &&
            a.optJSONObject("trip")?.optString("phase") in setOf("driving", "near", "arrived") && m != null
        if (!eligible) { reset(); return null }
        val nextKey = a.getString("id") + ":" + m!!.getString("id")
        if (nextKey != key) { key = nextKey; armedAt = now; return null }
        // Arm for the active pickup as soon as guidance starts, independent of gear/location.
        if (requireProximity && !near(a, ns, now)) {
            // A seat occupied while far away must not become a new boarding upon later arrival.
            armedAt = now + 1
            return null
        }
        if (ns.optString("vehicleSeatMonitoring") != "connected") return null
        val driver = ns.optString("vehicleDriverSeat")
        if (!driver.matches(Regex("ROW_[123]_(LEFT|CENTER|RIGHT)"))) return null
        val rows = ns.optJSONObject("vehicleSeats") ?: return null
        val candidates = rows.keys().asSequence().filter { area ->
            val row = rows.optJSONObject(area)
            area != driver && area.matches(Regex("ROW_[123]_(LEFT|CENTER|RIGHT)")) && fresh(row, now) &&
                row!!.optBoolean("occupied") && row.has("previous") && !row.getBoolean("previous") &&
                row.optLong("changedAt") >= armedAt && now >= row.optLong("changedAt")
        }.toList()
        // More than one newly occupied seat is ambiguous; never guess the first passenger.
        if (candidates.size != 1) return null
        val seat = candidates.single(); val at = rows.getJSONObject(seat).getLong("changedAt")
        val match = Match(a.getString("id"), m.getString("id"), seat, at, now)
        return match.takeUnless { it.id == consumed }
    }
    fun commit(match: Match) { consumed = match.id; reset() }
    companion object {
        fun metres(a: JSONObject, b: JSONObject): Double {
            val lat1 = a.optDouble("lat", Double.NaN); val lat2 = b.optDouble("lat", Double.NaN)
            val lon1 = a.optDouble("lon", Double.NaN); val lon2 = b.optDouble("lon", Double.NaN)
            if (!listOf(lat1, lat2, lon1, lon2).all { it.isFinite() } || abs(lat1)>90 || abs(lat2)>90 || abs(lon1)>180 || abs(lon2)>180) return Double.POSITIVE_INFINITY
            val dlat = Math.toRadians(lat2-lat1); val dlon = Math.toRadians(lon2-lon1)
            val v = sin(dlat/2).pow(2) + cos(Math.toRadians(lat1))*cos(Math.toRadians(lat2))*sin(dlon/2).pow(2)
            return 6371000 * 2 * asin(sqrt(v.coerceIn(0.0,1.0)))
        }
        /** Cabin °C, not last trip's switch setting or the requested HVAC target temperature. */
        fun thermal(profile: JSONObject, cabinC: Double?): Pair<Int, Int>? {
            if (!profile.optBoolean("enabled") || cabinC == null || !cabinC.isFinite() || cabinC !in -40.0..85.0) return null
            return when { cabinC <= 20 -> profile.optInt("cold_heat",1).coerceIn(0,3) to 0
                cabinC >= 26 -> 0 to profile.optInt("hot_ventilation",1).coerceIn(0,3)
                else -> 0 to 0 }
        }
    }
}

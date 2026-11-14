package io.pooly.app

import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs

/**
 * Collects stop records for pickup recommendations during a Pooly trip.
 *
 * - A stop starts when the speed drops to 1 km/h or less, and ends when it reaches 3 km/h.
 * - If a non-driver seat changes during the stop and stays changed for 1.5 s, the stop is a
 *   boarding (empty → occupied) and/or an alighting (occupied → empty).
 * - The record is produced once, when the car moves off: cell position, stop length, hour, kind.
 * - Skipped: stops before the car has first moved (getting in at home or a car park) and stops
 *   longer than 10 minutes (closer to parking than to a pickup).
 */
class StopRecorder(private val hourOf: (Long) -> Int = { at -> Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY) }) {
    data class Stop(val lat: Double, val lon: Double, val dwellS: Int, val hour: Int, val kind: String) {
        fun json(): JSONObject = JSONObject().put("lat", lat).put("lon", lon).put("dwell_s", dwellS).put("hour", hour).put("kind", kind)
    }

    private var moved = false
    private var startedAt = 0L
    private var lat = Double.NaN
    private var lon = Double.NaN
    private var boarded = false
    private var alighted = false

    fun reset() { moved = false; clearStop() }

    private fun clearStop() { startedAt = 0L; lat = Double.NaN; lon = Double.NaN; boarded = false; alighted = false }

    /** Feeds one telemetry snapshot; returns the records to send when a stop with seat changes ends. */
    fun update(ns: JSONObject, now: Long): List<Stop> {
        val motion = ns.optJSONObject("vehicleMotion")
        if (!PickupPolicy.fresh(motion, now)) return emptyList()
        val kmh = abs(motion!!.optDouble("speedMps", Double.NaN)) * 3.6
        if (!kmh.isFinite()) return emptyList()
        if (kmh >= MOVING_KMH) {
            val records = finish(now)
            moved = true
            clearStop()
            return records
        }
        if (startedAt == 0L) {
            if (kmh > STOPPED_KMH) return emptyList()
            startedAt = now
        }
        if (!lat.isFinite()) ns.optJSONObject("navigationPosition")?.takeIf { PickupPolicy.fresh(it, now, 10000) }?.let {
            lat = it.optDouble("lat", Double.NaN); lon = it.optDouble("lon", Double.NaN)
        }
        observeSeats(ns, now)
        return emptyList()
    }

    private fun observeSeats(ns: JSONObject, now: Long) {
        val rows = ns.optJSONObject("vehicleSeats") ?: return
        val driver = ns.optString("vehicleDriverSeat")
        for (seat in rows.keys()) {
            if (seat == driver) continue
            val row = rows.optJSONObject(seat) ?: continue
            if (!PickupPolicy.fresh(row, now)) continue
            val changedAt = row.optLong("changedAt")
            // Seat polling and speed polling are both ~2 s apart: accept a change right at the stop start.
            if (changedAt < startedAt - SAMPLE_SLACK_MS || now - changedAt < STABLE_MS) continue
            val occupied = row.opt("occupied"); val previous = row.opt("previous")
            if (occupied == true && previous == false) boarded = true
            if (occupied == false && previous == true) alighted = true
        }
    }

    private fun finish(now: Long): List<Stop> {
        if (startedAt == 0L || !moved || !lat.isFinite() || !lon.isFinite()) return emptyList()
        val dwell = ((now - startedAt) / 1000).toInt()
        if (dwell < 1 || dwell > MAX_STOP_S) return emptyList()
        val hour = hourOf(startedAt)
        return listOfNotNull(
            if (boarded) Stop(lat, lon, dwell, hour, "board") else null,
            if (alighted) Stop(lat, lon, dwell, hour, "alight") else null,
        )
    }

    companion object {
        const val STOPPED_KMH = 1.0
        const val MOVING_KMH = 3.0
        const val STABLE_MS = 1500L
        const val SAMPLE_SLACK_MS = 2000L
        const val MAX_STOP_S = 600
    }
}

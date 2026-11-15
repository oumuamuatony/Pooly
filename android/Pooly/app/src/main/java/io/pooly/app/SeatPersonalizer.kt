package io.pooly.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** No raw cross-model angles and no open-ended seat motor commands. Memory slots are vehicle-scoped. */
class SeatPersonalizer(private val sdk: Vehicle, private val vehicleKey: String, private val event: (JSONObject) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private fun writable(access: AccessType) = access in setOf(AccessType.WRITE, AccessType.READ_WRITE)
    private fun readable(access: AccessType) = access in setOf(AccessType.READ, AccessType.READ_WRITE)
    /** Reads the seat's heat/ventilation levels and the cabin temperature, to learn changes made in the car. */
    fun observe(seat: String, result: (JSONObject) -> Unit) {
        val area = try { SeatArea(SeatValue.valueOf(seat)) } catch (_: Exception) { return }
        val out = JSONObject().put("seat", seat)
        val seatValue = area.values.single()
        val driver = PleosRuntime.state().optString("vehicleDriverSeat")
        var pending = 5
        fun done(write: () -> Unit = {}) { ui.post { write(); pending -= 1; if (pending == 0) result(out) } }
        try {
            sdk.getSeat().getHvacSeatTemperature(area = area, onComplete = { v -> done { out.put("heat", v ?: JSONObject.NULL) } }, onFailed = { done() })
        } catch (_: Exception) { done() }
        try {
            sdk.getSeat().getHvacSeatVentilationState(area = area, onComplete = { v -> done { out.put("vent", v ?: JSONObject.NULL) } }, onFailed = { done() })
        } catch (_: Exception) { done() }
        try {
            sdk.getHVAC().checkHvacTemperatureCurrentCapabilities(onComplete = { config, areas ->
                val readArea = areas.firstOrNull { it.values.contains(area.values.single()) }
                if (!readable(config.accessType) || readArea == null) done()
                else sdk.getHVAC().getHvacTemperatureCurrent(readArea, TemperatureUnit.CELSIUS,
                    onComplete = { value -> done { out.put("cabinC", value?.toDouble() ?: JSONObject.NULL) } }, onFailed = { done() })
            }, onFailed = { done() })
        } catch (_: Exception) { done() }
        // Target temperature and fan direction of the passenger's own climate zone (never a zone shared with the driver).
        try {
            sdk.getHVAC().checkHvacTemperatureCapabilities(onComplete = { _, zones ->
                val zone = zones.keys.firstOrNull { it.values.contains(seatValue) }
                if (zone == null || zone.values.any { it.name == driver }) done { out.put("sharedZone", zone != null) }
                else sdk.getHVAC().getHvacTemperature(zone, TemperatureUnit.CELSIUS,
                    onComplete = { t -> done { out.put("targetTemp", t?.toDouble() ?: JSONObject.NULL) } }, onFailed = { done() })
            }, onFailed = { done() })
        } catch (_: Exception) { done() }
        try {
            sdk.getHVAC().checkHvacFanDirectionCapabilities(onComplete = { _, zones ->
                val zone = zones.keys.firstOrNull { it.values.contains(seatValue) }
                if (zone == null || zone.values.any { it.name == driver }) done { out.put("sharedZone", zone != null) }
                else sdk.getHVAC().getHvacFanDirection(zone,
                    onComplete = { f -> done { out.put("fanDirection", f?.name ?: JSONObject.NULL) } }, onFailed = { done() })
            }, onFailed = { done() })
        } catch (_: Exception) { done() }
    }
    private fun applyClimate(area: SeatArea, profile: JSONObject, allowed: () -> Boolean, report: (String,String) -> Unit) {
        val seatValue = area.values.single()
        val driver = PleosRuntime.state().optString("vehicleDriverSeat")
        val target = profile.optDouble("target_temp", Double.NaN)
        if (target.isFinite()) try {
            sdk.getHVAC().checkHvacTemperatureCapabilities(onComplete = { config, zones -> ui.post {
                val zone = zones.keys.firstOrNull { it.values.contains(seatValue) }
                val bounds = zone?.let { zones[it] }
                if (zone == null || bounds == null || !writable(config.accessType) || zone.values.any { it.name == driver }) report("climate","temperature-skipped")
                else if (target.toFloat() !in bounds.minValue..bounds.maxValue) report("climate","temperature-out-of-range")
                else if (allowed()) sdk.getHVAC().setHvacTemperature(zone, target.toFloat(), TemperatureUnit.CELSIUS,
                    onComplete = { _, _ -> report("climate","temperature-applied") }, onFailed = { report("climate","temperature-failed") })
            } }, onFailed = { report("climate","temperature-unavailable") })
        } catch (_: Exception) { report("climate","temperature-unavailable") }
        val fan = FanDirectionType.values().firstOrNull { it.name == profile.optString("fan_direction") && it != FanDirectionType.UNKNOWN }
        if (fan != null) try {
            sdk.getHVAC().checkHvacFanDirectionCapabilities(onComplete = { config, zones -> ui.post {
                val zone = zones.keys.firstOrNull { it.values.contains(seatValue) }
                val supported = zone?.let { zones[it] }
                if (zone == null || supported == null || fan !in supported || !writable(config.accessType) || zone.values.any { it.name == driver }) report("climate","fan-skipped")
                else if (allowed()) sdk.getHVAC().setHvacFanDirection(zone, fan,
                    onComplete = { _, _ -> report("climate","fan-applied") }, onFailed = { report("climate","fan-failed") })
            } }, onFailed = { report("climate","fan-unavailable") })
        } catch (_: Exception) { report("climate","fan-unavailable") }
    }
    fun apply(seat: String, profile: JSONObject, current: () -> Boolean) {
        val area = try { SeatArea(SeatValue.valueOf(seat)) } catch (_: Exception) { return }
        fun allowed(): Boolean {
            val ns = PleosRuntime.state(); val now = System.currentTimeMillis(); val row = ns.optJSONObject("vehicleSeats")?.optJSONObject(seat)
            val valid=current() && PickupPolicy().parked(ns, now) && seat != ns.optString("vehicleDriverSeat") &&
                row != null && PickupPolicy().fresh(row, now) && row.optBoolean("occupied")
            if (!valid) android.util.Log.i("AutoPickupGuard", "current=${current()} parked=${PickupPolicy().parked(ns,now)} freshSeat=${PickupPolicy().fresh(row,now)} occupied=${row?.optBoolean("occupied")} changedAt=${row?.optLong("changedAt")}")
            return valid
        }
        fun report(feature: String, status: String) { event(JSONObject().put("seatPersonalization", JSONObject().put("seat",seat).put("feature",feature).put("status",status).put("updatedAt",System.currentTimeMillis()))) }
        if (!profile.optBoolean("enabled")) { report("profile","disabled"); return }
        if (!allowed()) { report("profile","not-stationary-or-occupied"); return }
        val slot = profile.optJSONObject("vehicle_memories")?.optInt(vehicleKey, 0) ?: 0
        if (slot > 0) try {
            sdk.getSeat().checkSeatMemoryCapabilities(onComplete = { config, areas -> ui.post {
                val bounds = areas[area]
                if (!writable(config.accessType) || bounds == null || slot !in bounds.minValue..bounds.maxValue) report("memory","unsupported")
                else if (allowed()) {
                    // Public API has only onFailed: report requested, never claim physical completion.
                    sdk.getSeat().selectSeatMemory(area, slot, onFailed = { report("memory","failed") })
                    report("memory","requested")
                }
            } }, onFailed = { report("memory","unavailable") })
        } catch (_: Exception) { report("memory","unavailable") }
        try {
            sdk.getHVAC().checkHvacTemperatureCurrentCapabilities(onComplete = { config, areas ->
                val readArea = areas.firstOrNull { it.values.contains(area.values.single()) }
                if (!readable(config.accessType) || readArea == null) { report("thermal","temperature-unavailable"); return@checkHvacTemperatureCurrentCapabilities }
                sdk.getHVAC().getHvacTemperatureCurrent(readArea, TemperatureUnit.CELSIUS, onComplete = { value -> ui.post {
                    val levels = PickupPolicy.thermal(profile, value?.toDouble())
                    if (levels == null || !allowed()) report("thermal","temperature-unavailable-or-moved")
                    else applyThermal(area, levels.first, levels.second, ::allowed, ::report)
                } }, onFailed = { report("thermal","temperature-unavailable") })
            }, onFailed = { report("thermal","temperature-unavailable") })
        } catch (_: Exception) { report("thermal","unavailable") }
        applyClimate(area, profile, ::allowed, ::report)
    }
    private fun applyThermal(area: SeatArea, heat: Int, vent: Int, allowed: () -> Boolean, report: (String,String) -> Unit) {
        // Both features must expose this exact single seat. A shared zone must not modify another occupant.
        sdk.getSeat().checkSeatTemperatureCapabilities(onComplete = { hc, heatAreas ->
            sdk.getSeat().checkSeatVentilationCapabilities(onComplete = { vc, ventAreas -> ui.post {
                val hb = heatAreas[area]; val vb = ventAreas[area]
                if (!writable(hc.accessType) || !writable(vc.accessType) || hb == null || vb == null || 0 !in hb.minValue..hb.maxValue || 0 !in vb.minValue..vb.maxValue || heat !in hb.minValue..hb.maxValue || vent !in vb.minValue..vb.maxValue) {
                    report("thermal","unsupported"); return@post
                }
                if (!allowed()) return@post
                // Turn the opposite mode off before enabling a mode, and recheck stop/occupancy at each callback.
                if (heat > 0) sdk.getSeat().setHvacSeatVentilation(area,0,onComplete = { _, _ -> ui.post {
                    if (allowed()) sdk.getSeat().setHvacSeatTemperature(area,heat,onComplete = { _, _ -> report("thermal","applied") },onFailed = { report("thermal","failed") })
                } },onFailed = { report("thermal","failed") })
                else sdk.getSeat().setHvacSeatTemperature(area,0,onComplete = { _, _ -> ui.post {
                    if (allowed()) sdk.getSeat().setHvacSeatVentilation(area,vent,onComplete = { _, _ -> report("thermal","applied") },onFailed = { report("thermal","failed") })
                } },onFailed = { report("thermal","failed") })
            } }, onFailed = { report("thermal","unsupported") })
        }, onFailed = { report("thermal","unsupported") })
    }
}

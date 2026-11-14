package io.pooly.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Native worker survives the car WebView being hidden by OEM navigation. No physical driving commands. */
class PickupAutomation(private val base: String, private val token: String, private val aid: String, private val telemetry: () -> JSONObject = PleosRuntime::state) {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val policy = PickupPolicy()
    @Volatile private var closed = false
    private var appointment: JSONObject? = null
    private var requireProximity = true
    private var updatedAt = 0L
    private var nextState = 0L
    private var retryAt = 0L
    private var pendingRoute: JSONObject? = null
    private var routeAttempts = 0
    private var profileRequestedAt = 0L
    private var profileSeat = ""
    private val handled = mutableSetOf<String>()
    private val alightFailures = mutableMapOf<String, Int>()
    private val stopRecorder = StopRecorder()
    fun start() {
        PleosRuntime.publish(JSONObject().put("pickupAutomation",true))
        worker.scheduleWithFixedDelay({ if (!closed) try { tick() } catch (error: Exception) {
            android.util.Log.w("AutoPickupError",error.message?.take(160) ?: error.javaClass.simpleName)
            PleosRuntime.publish(JSONObject().put("pickupAutomationError",error.message?.take(160) ?: "request-failed"))
            retryAt = System.currentTimeMillis()+3000
            PleosRuntime.publish(JSONObject().put("pickupAutomationStatus","retrying"))
        } },0,500,TimeUnit.MILLISECONDS)
    }
    private fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val response = PoolyApi.request(base,path,method,body,token)
        check(response.getInt("status") in 200..299) { "API ${response.getInt("status")}: " + response.getJSONObject("data").optString("error","request-failed") }
        return response.getJSONObject("data")
    }
    private fun tick() {
        var now = System.currentTimeMillis()
        if (now < retryAt) return
        if (now >= nextState) {
            val state = request("/api/state")
            requireProximity = state.optBoolean("auto_pickup_proximity_required",true)
            PleosRuntime.publish(JSONObject().put("pickupProximityRequired",requireProximity))
            val rows = state.getJSONArray("appointments")
            appointment = (0 until rows.length()).map { rows.getJSONObject(it) }.find { it.optString("id") == aid }
            updatedAt = now; nextState = now+2500
        }
        val a = appointment ?: return
        if (!a.optBoolean("is_driver") || a.getJSONObject("trip").optString("phase") in setOf("idle","finished","cancelled")) { policy.reset(); return }
        // Stop records feed pickup recommendations; they run before any early return below.
        stopRecorder.update(telemetry(),System.currentTimeMillis()).forEach { stop ->
            // Best-effort: a failed upload never blocks pickup automation.
            try { request("/api/appointments/$aid/stops","POST",stop.json()) } catch (_: Exception) {}
        }
        // Recover a committed receipt after a lost HTTP response or Activity/process recreation.
        val members = a.getJSONArray("members")
        val boundKey = PleosRuntime.vehicle?.vehicleKey
        for (i in 0 until members.length()) {
            val member = members.getJSONObject(i)
            val binding = member.optJSONObject("seat_binding") ?: continue
            val id = binding.optString("observation_id")
            if (id.isNotEmpty() && id !in handled && binding.optString("vehicle_key") == boundKey &&
                now-binding.optLong("observed_at") in 0..120000) {
                complete(a, member, PickupPolicy.Match(aid,member.getString("id"),binding.getString("seat"),binding.getLong("occupied_at"),binding.getLong("observed_at")), id)
                return
            }
        }
        if (pendingRoute != null && routeAttempts < 3) {
            val status = PleosRuntime.state().optJSONObject("seatPersonalization")
            val settingsFinished = status?.optString("seat") == profileSeat && status.optLong("updatedAt") >= profileRequestedAt && status.optString("feature") in setOf("profile","thermal")
            // Finish bounded seat setup while stopped before replacing the OEM route.
            if (settingsFinished || now-profileRequestedAt >= 10000) navigate(pendingRoute!!)
            return
        }
        val ns = telemetry()
        // Compare time after taking the snapshot: a callback received during the read must not look future-dated.
        now = System.currentTimeMillis()
        // Drop-off does not depend on OEM guidance still being bound to this trip.
        val vehicleKey = PleosRuntime.vehicle?.vehicleKey
        if (now-updatedAt <= 6000 && vehicleKey != null) {
            val drop = policy.alight(a,ns,now,vehicleKey)
            if (drop != null && drop.id !in handled) { alight(drop,vehicleKey); return }
        }
        if (now-updatedAt > 6000 || ns.optString("navigationAid") != aid) { policy.reset(); return }
        val match = policy.update(a,ns,now,requireProximity)
        val m = policy.current(a) ?: return
        val evidence = JSONObject().put("parked",policy.parked(ns,now)).put("position",JSONObject((ns.optJSONObject("navigationPosition") ?: JSONObject()).toString()).put("name","차량 현재 위치"))
            .put("observed_at",now)
        if (match == null) return
        val vehicle = PleosRuntime.vehicle ?: return
        evidence.put("seat",match.seat).put("driver_seat",ns.getString("vehicleDriverSeat")).put("occupied_at",match.occupiedAt).put("vehicle_key",vehicle.vehicleKey)
        val result = request("/api/appointments/$aid/actions","POST",JSONObject().put("action","auto_board").put("member_id",match.memberId).put("observation_id",match.id).put("evidence",evidence))
        policy.commit(match); appointment = result; updatedAt = now; nextState = now+2500
        complete(result, m, match, match.id)
    }
    private fun complete(result: JSONObject, m: JSONObject, match: PickupPolicy.Match, receipt: String) {
        handled.add(receipt)
        profileRequestedAt = System.currentTimeMillis(); profileSeat = match.seat
        val vehicle = PleosRuntime.vehicle ?: return
        // Receipt may be replayed after a lost response; only this server-confirmed passenger gets the settings.
        try {
            val data = request("/api/appointments/$aid/seat-personalization","POST",JSONObject().put("member_id",match.memberId))
            val binding = data.getJSONObject("binding")
            if (binding.optString("observation_id") == receipt && binding.optString("seat") == match.seat && binding.optString("vehicle_key") == vehicle.vehicleKey) ui.post {
                if (!closed) vehicle.personalize(match.seat,data.getJSONObject("profile")) {
                    !closed && appointment?.getJSONObject("trip")?.optString("phase") !in setOf("finished","cancelled") &&
                        PleosRuntime.state().optJSONObject("vehicleSeats")?.optJSONObject(match.seat)?.optLong("changedAt") == match.occupiedAt
                }
            }
        } catch (_: Exception) { PleosRuntime.publish(JSONObject().put("seatPersonalization",JSONObject().put("seat",match.seat).put("feature","profile").put("updatedAt",System.currentTimeMillis()).put("status","profile-unavailable"))) }
        val next = policy.current(result)
        PleosRuntime.publish(JSONObject().put("pickupBoardingNotice",JSONObject().put("key",receipt).put("appointmentId",aid).put("observedAt",match.observedAt)
            .put("title",m.getString("name")+" 님 승차 완료").put("message",next?.let { it.getString("name")+" 님에게 이동 중" } ?: ("목적지로 이동 중 · "+result.getJSONObject("destination").getString("name")))))
        pendingRoute = result; routeAttempts = 0
    }
    private fun alight(drop: PickupPolicy.Alight, vehicleKey: String) {
        val evidence = JSONObject().put("parked",true).put("observed_at",drop.observedAt).put("seat",drop.seat)
            .put("vacated_at",drop.vacatedAt).put("vehicle_key",vehicleKey)
        val result = try {
            request("/api/appointments/$aid/actions","POST",JSONObject().put("action","auto_alight").put("member_id",drop.memberId)
                .put("observation_id",drop.id).put("evidence",evidence))
        } catch (error: Exception) {
            // A signal the server keeps rejecting is dropped after three tries instead of retrying forever.
            val failures = (alightFailures[drop.id] ?: 0) + 1
            alightFailures[drop.id] = failures
            if (failures >= 3) handled.add(drop.id)
            throw error
        }
        handled.add(drop.id); appointment = result
        val now = System.currentTimeMillis(); updatedAt = now; nextState = now+2500
        PleosRuntime.publish(JSONObject().put("pickupAlightNotice",JSONObject().put("key",drop.id).put("appointmentId",aid).put("observedAt",drop.observedAt)
            .put("title",drop.name+" 님 하차 완료").put("message","좌석이 비어 하차를 기록했어요.")))
    }
    private fun navigate(a: JSONObject) {
        routeAttempts++; retryAt = System.currentTimeMillis()+3000
        try {
            val route = request("/api/appointments/$aid/route")
            val trip = a.getJSONObject("trip"); val active = trip.optJSONArray("active_pickups") ?: JSONArray()
            val remaining = (trip.optInt("index") until active.length()).map { active.getString(it) }.toSet()
            val raw = route.getJSONArray("stops"); val stops = JSONArray()
            (0 until raw.length()).map { raw.getJSONObject(it) }.filter { it.optString("kind") != "start" && (it.optString("kind") != "pickup" || it.optString("id") in remaining) }.forEach { stops.put(it) }
            check(stops.length()>0)
            ui.post {
                if (!closed) try { PleosRuntime.navigation?.navigate(stops,false,aid) ?: error("navigation-unavailable") }
                catch (_: Exception) { PleosRuntime.publish(JSONObject().put("pickupAutomationStatus","navigation-retry-needed")) }
            }
            pendingRoute = null
        } catch (_: Exception) { PleosRuntime.publish(JSONObject().put("pickupAutomationStatus","navigation-retry-needed")) }
    }
    fun close() { closed = true; ui.removeCallbacksAndMessages(null); worker.shutdownNow(); PleosRuntime.publish(JSONObject().put("pickupAutomation",false)) }
}

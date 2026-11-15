package io.pooly.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PickupPolicyTest {
    private val t = 1000000L
    private fun appointment(phase: String = "arrived") = JSONObject("""{"id":"trip","is_driver":true,"trip":{"phase":"$phase","index":0,"active_pickups":["max","kelly"]},"members":[{"id":"max","status":"accepted","boarded_at":null,"pickup":{"lat":37.5445,"lon":127.056}},{"id":"kelly","status":"accepted","boarded_at":null,"pickup":{"lat":37.5445,"lon":127.056}}]}""")
    private fun state(now: Long = t+4000) = JSONObject("""{"navigationAid":"trip","navigationPosition":{"lat":37.5445,"lon":127.056,"updatedAt":$now},"vehicleMotion":{"parked":true,"speedMps":0,"updatedAt":$now},"vehicleDriverSeat":"ROW_1_LEFT","vehicleSeatMonitoring":"connected","vehicleSeats":{"ROW_1_RIGHT":{"occupied":true,"previous":false,"changedAt":${t+500},"updatedAt":$now}}}""")
    private fun policy(): PickupPolicy = PickupPolicy().also { assertNull(it.update(appointment(),state(t),t)) }
    @Test fun occupancyBoardsImmediatelyWithoutDoorOrReadyChoice() {
        val p=policy(); val immediate=p.update(appointment("driving"),state(t+500),t+500)!!; assertEquals("max",immediate.memberId)
        val match=p.update(appointment(),state(),t+4000)!!; assertEquals("max",match.memberId); assertEquals("ROW_1_RIGHT",match.seat)
        p.commit(match)
        val a=appointment();a.getJSONObject("trip").put("index",1)
        assertNull(p.update(a,state(),t+4100)); assertNull(p.update(a,state(t+8000),t+8000))
    }
    @Test fun ignoresOccupancyThatExistedBeforeArrivalOrWasUnknown() {
        val ns=state();ns.getJSONObject("vehicleSeats").getJSONObject("ROW_1_RIGHT").put("changedAt",t-100)
        assertNull(policy().update(appointment(),ns,t+4000))
        ns.getJSONObject("vehicleSeats").getJSONObject("ROW_1_RIGHT").remove("previous")
        assertNull(policy().update(appointment(),ns,t+4000))
    }
    @Test fun rejectsWrongRouteStaleOrFarLocation() {
        for (mode in listOf("route","stale","far","driver")) {
            val ns=state()
            when(mode) {
                "route" -> ns.put("navigationAid","another-trip")
                "stale" -> ns.getJSONObject("navigationPosition").put("updatedAt",t-20000)
                "far" -> ns.getJSONObject("navigationPosition").put("lat",37.56)
                "driver" -> ns.put("vehicleDriverSeat","ROW_1_RIGHT")
            }
            assertNull(mode,policy().update(appointment(),ns,t+4000))
        }
    }
    @Test fun ambiguousSeatsNeverChooseFirstCallback() {
        val ns=state();ns.getJSONObject("vehicleSeats").put("ROW_2_RIGHT",JSONObject(ns.getJSONObject("vehicleSeats").getJSONObject("ROW_1_RIGHT").toString()))
        assertNull(policy().update(appointment(),ns,t+4000))
    }
    @Test fun gearAndSpeedDoNotAffectBoarding() {
        val ns=state();ns.getJSONObject("vehicleMotion").put("parked",false).put("speedMps",5)
        assertNotNull(policy().update(appointment("driving"),ns,t+4000))
        ns.remove("vehicleMotion");assertNotNull(policy().update(appointment(),ns,t+4000))
    }
    @Test fun waitingLongerThanTwoMinutesStillDetectsNewSeat() {
        val ns=state(t+300000);ns.getJSONObject("vehicleSeats").getJSONObject("ROW_1_RIGHT").put("changedAt",t+300000)
        assertNotNull(policy().update(appointment(),ns,t+300000))
    }
    @Test fun demoBypassesOnlyProximityIncludingMissingGps() {
        val ns=state();ns.remove("navigationPosition")
        val p=policy();assertNull(p.update(appointment(),ns,t+4000))
        assertNotNull(policy().update(appointment(),ns,t+4000,requireProximity=false))
        ns.put("navigationAid","other")
        assertNull(policy().update(appointment(),ns,t+4000,requireProximity=false))
    }
    @Test fun seatObservedFarAwayDoesNotBecomeNewWhenLaterNear() {
        val p=policy();val ns=state()
        ns.getJSONObject("navigationPosition").put("lat",37.56)
        assertNull(p.update(appointment(),ns,t+4000))
        assertNull(p.update(appointment(),state(t+5000),t+5000))
        val fresh=state(t+6000);fresh.getJSONObject("vehicleSeats").getJSONObject("ROW_1_RIGHT").put("changedAt",t+6000)
        assertNotNull(p.update(appointment(),fresh,t+6000))
    }
    @Test fun distanceBoundaryAndMonitoringStillApply() {
        val ns=state();ns.getJSONObject("navigationPosition").put("lat",37.5445+79.0/111195)
        assertNotNull(policy().update(appointment(),ns,t+4000))
        ns.getJSONObject("navigationPosition").put("lat",37.5445+81.0/111195)
        assertNull(policy().update(appointment(),ns,t+4000))
        ns.put("vehicleSeatMonitoring","unsupported")
        assertNull(policy().update(appointment(),ns,t+4000,requireProximity=false))
    }
    @Test fun neverUsesTargetHvacOrLastTripSwitchWhenTemperatureMissing() {
        val p=JSONObject("""{"enabled":true,"cold_heat":2,"hot_ventilation":1}""")
        assertEquals(2 to 0,PickupPolicy.thermal(p,10.0));assertEquals(0 to 1,PickupPolicy.thermal(p,30.0));assertEquals(0 to 0,PickupPolicy.thermal(p,23.0))
        assertNull(PickupPolicy.thermal(p,null));assertNull(PickupPolicy.thermal(p,Double.NaN));p.put("enabled",false);assertNull(PickupPolicy.thermal(p,10.0))
    }
    @Test fun heatStopsAboveTwentyDegrees() {
        val p=JSONObject("""{"enabled":true,"cold_heat":2,"hot_ventilation":1}""")
        assertEquals(2 to 0,PickupPolicy.thermal(p,20.0));assertEquals(0 to 0,PickupPolicy.thermal(p,20.1));assertEquals(0 to 1,PickupPolicy.thermal(p,26.0))
    }
    private fun boarded(phase: String = "destination") = JSONObject("""{"id":"trip","is_driver":true,"trip":{"phase":"$phase","index":1,"active_pickups":["max"]},"members":[{"id":"max","name":"Max","status":"accepted","boarded_at":"2026-11-14T10:00:00Z","seat_binding":{"seat":"ROW_1_RIGHT","vehicle_key":"car","occupied_at":${t+500}}}]}""")
    private fun vacated(now: Long, changedAt: Long = t+10000) = JSONObject("""{"vehicleMotion":{"parked":true,"speedMps":0,"updatedAt":$now},"vehicleSeatMonitoring":"connected","vehicleSeats":{"ROW_1_RIGHT":{"occupied":false,"previous":true,"changedAt":$changedAt,"updatedAt":$now}}}""")
    @Test fun boundSeatVacatedWhileStoppedIsAlighting() {
        val drop=PickupPolicy().alight(boarded(),vacated(t+12000),t+12000,"car")!!
        assertEquals("max",drop.memberId);assertEquals("ROW_1_RIGHT",drop.seat);assertEquals(t+10000,drop.vacatedAt);assertEquals("Max",drop.name)
        assertNotNull(PickupPolicy().alight(boarded("parked"),vacated(t+12000),t+12000,"car"))
    }
    @Test fun alightingNeedsStableStoppedSameCarAndNewEdge() {
        val p=PickupPolicy()
        assertNull("debounce",p.alight(boarded(),vacated(t+10500),t+10500,"car"))
        assertNull("other car",p.alight(boarded(),vacated(t+12000),t+12000,"other"))
        assertNull("before boarding",p.alight(boarded(),vacated(t+12000,t+400),t+12000,"car"))
        assertNull("idle",p.alight(boarded("idle"),vacated(t+12000),t+12000,"car"))
        val moving=vacated(t+12000);moving.getJSONObject("vehicleMotion").put("parked",false).put("speedMps",4)
        assertNull("moving",p.alight(boarded(),moving,t+12000,"car"))
        val occupied=vacated(t+12000);occupied.getJSONObject("vehicleSeats").getJSONObject("ROW_1_RIGHT").put("occupied",true).put("previous",false)
        assertNull("occupied",p.alight(boarded(),occupied,t+12000,"car"))
        val done=boarded();done.getJSONArray("members").getJSONObject(0).put("alighted_at","2026-11-14T10:10:00Z")
        assertNull("already",p.alight(done,vacated(t+12000),t+12000,"car"))
        val stale=vacated(t+12000);stale.getJSONObject("vehicleSeats").getJSONObject("ROW_1_RIGHT").put("updatedAt",t)
        assertNull("stale",p.alight(boarded(),stale,t+12000,"car"))
    }
}

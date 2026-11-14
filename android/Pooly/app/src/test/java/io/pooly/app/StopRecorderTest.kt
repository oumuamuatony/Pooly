package io.pooly.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopRecorderTest {
    private val t = 1_800_000_000_000L
    private val seats = mutableMapOf<String, JSONObject>()

    private fun ns(now: Long, kmh: Double, lat: Double = 37.5445) = JSONObject()
        .put("vehicleMotion", JSONObject().put("speedMps", kmh / 3.6).put("parked", false).put("updatedAt", now))
        .put("navigationPosition", JSONObject().put("lat", lat).put("lon", 127.056).put("updatedAt", now))
        .put("vehicleDriverSeat", "ROW_1_LEFT")
        .put("vehicleSeats", JSONObject().apply { seats.forEach { (k, v) -> put(k, JSONObject(v.toString()).put("updatedAt", now)) } })

    private fun seat(area: String, occupied: Boolean, at: Long) {
        seats[area] = JSONObject().put("occupied", occupied).put("previous", !occupied).put("changedAt", at)
    }

    private fun recorder() = StopRecorder { 18 }

    @Test fun boardingStopIsRecordedWhenTheCarMovesOff() {
        val r = recorder()
        assertTrue(r.update(ns(t, 30.0), t).isEmpty())           // driving
        assertTrue(r.update(ns(t + 2000, 0.0), t + 2000).isEmpty()) // stop starts
        seat("ROW_1_RIGHT", true, t + 10_000)
        assertTrue(r.update(ns(t + 12_000, 0.0), t + 12_000).isEmpty())
        val out = r.update(ns(t + 32_000, 5.0), t + 32_000)
        assertEquals(listOf(StopRecorder.Stop(37.5445, 127.056, 30, 18, "board")), out)
        assertTrue("one record per stop", r.update(ns(t + 34_000, 20.0), t + 34_000).isEmpty())
    }

    @Test fun stopWithoutSeatChangeResetsAtThreeKmh() {
        val r = recorder()
        r.update(ns(t, 30.0), t); r.update(ns(t + 2000, 0.0), t + 2000)
        assertTrue(r.update(ns(t + 40_000, 2.0), t + 40_000).isEmpty())   // creeping: same stop
        assertTrue(r.update(ns(t + 42_000, 3.0), t + 42_000).isEmpty())   // red light: nothing saved
    }

    @Test fun firstStopBeforeMovingAndLongStopsAreSkipped() {
        val r = recorder()
        r.update(ns(t, 0.0), t)                         // getting in at home
        seat("ROW_1_RIGHT", true, t + 5000)
        r.update(ns(t + 7000, 0.0), t + 7000)
        assertTrue(r.update(ns(t + 20_000, 10.0), t + 20_000).isEmpty())
        r.update(ns(t + 30_000, 0.0), t + 30_000)       // a later 11-minute stop
        seat("ROW_2_LEFT", true, t + 40_000)
        r.update(ns(t + 42_000, 0.0), t + 42_000)
        assertTrue(r.update(ns(t + 30_000 + 660_000, 10.0), t + 30_000 + 660_000).isEmpty())
    }

    @Test fun alightingDriverSeatAndFlickerAreHandled() {
        val r = recorder()
        r.update(ns(t, 30.0), t); r.update(ns(t + 2000, 0.5), t + 2000)
        seat("ROW_1_LEFT", false, t + 3000)                 // driver seat is ignored
        seat("ROW_2_RIGHT", false, t + 6000)                // passenger got out
        seat("ROW_2_LEFT", true, t + 7500)                  // still settling: < 1.5 s at the last sample
        r.update(ns(t + 8000, 0.0), t + 8000)
        val out = r.update(ns(t + 9000, 4.0), t + 9000)
        assertEquals(listOf("alight"), out.map { it.kind })
        assertEquals(7, out.single().dwellS)
    }
}

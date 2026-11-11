package io.pickuptogether.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SeatPersonalizerSdkTest {
    @Test fun reportActualSeatCapabilitiesAndApplySupportedThermal() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val report=JSONObject();val latch=CountDownLatch(4)
        val sdk=Vehicle(inst.targetContext);inst.runOnMainSync{sdk.initialize()}
        sdk.getSeat().checkSeatTemperatureCapabilities(onComplete={c,a->synchronized(report){report.put("heatAccess",c.accessType.name).put("heatAreas",a.toString())};latch.countDown()},onFailed={synchronized(report){report.put("heatError",it.javaClass.simpleName)};latch.countDown()})
        sdk.getSeat().checkSeatVentilationCapabilities(onComplete={c,a->synchronized(report){report.put("ventAccess",c.accessType.name).put("ventAreas",a.toString())};latch.countDown()},onFailed={synchronized(report){report.put("ventError",it.javaClass.simpleName)};latch.countDown()})
        sdk.getSeat().checkSeatMemoryCapabilities(onComplete={c,a->synchronized(report){report.put("memoryAccess",c.accessType.name).put("memoryAreas",a.toString())};latch.countDown()},onFailed={synchronized(report){report.put("memoryError",it.javaClass.simpleName)};latch.countDown()})
        sdk.getHVAC().checkHvacTemperatureCurrentCapabilities(onComplete={c,a->synchronized(report){report.put("cabinAccess",c.accessType.name).put("cabinAreas",a.toString())};val seat=a.firstOrNull();if(seat==null)latch.countDown() else sdk.getHVAC().getHvacTemperatureCurrent(seat,TemperatureUnit.CELSIUS,onComplete={v->synchronized(report){report.put("cabinC",v?:JSONObject.NULL)};latch.countDown()},onFailed={synchronized(report){report.put("cabinError",it.javaClass.simpleName)};latch.countDown()})},onFailed={synchronized(report){report.put("cabinError",it.javaClass.simpleName)};latch.countDown()})
        assertTrue("SDK capabilities timed out",latch.await(15,TimeUnit.SECONDS))
        android.util.Log.i("AutoPickupTest","CAPABILITIES $report")
        assertTrue("Seat heat capability callback missing",report.has("heatAccess")||report.has("heatError"))
        inst.runOnMainSync{sdk.release()}
        assertFalse("Cabin permission must be configured",report.has("cabinError"))
        val applied=CountDownLatch(1);var outcome="";var observer:Int?=null
        inst.runOnMainSync {
            PleosRuntime.start(inst.targetContext);PleosRuntime.vehicle?.setJourneyActive(true)
            observer=PleosRuntime.observe { data -> data.optJSONObject("seatPersonalization")?.let { value ->
                if(value.optString("feature")=="thermal") {outcome=value.optString("status");applied.countDown()}
            } }
        }
        val readyUntil=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<readyUntil) {
            val ns=PleosRuntime.state()
            if(PickupPolicy().parked(ns,System.currentTimeMillis())&&ns.optJSONObject("vehicleSeats")?.optJSONObject("ROW_1_RIGHT")?.optBoolean("occupied")==true)break
            Thread.sleep(250)
        }
        inst.runOnMainSync { PleosRuntime.vehicle?.personalize("ROW_1_RIGHT",JSONObject().put("enabled",true).put("cold_heat",1).put("hot_ventilation",1)) { true } }
        try {
            assertTrue("Thermal apply callback missing: ${PleosRuntime.state()}",applied.await(15,TimeUnit.SECONDS))
            android.util.Log.i("AutoPickupTest","THERMAL RESULT $outcome")
            assertEquals("applied",outcome)
        } finally { inst.runOnMainSync { PleosRuntime.removeObserver(observer);PleosRuntime.vehicle?.setJourneyActive(false) } }
    }
}

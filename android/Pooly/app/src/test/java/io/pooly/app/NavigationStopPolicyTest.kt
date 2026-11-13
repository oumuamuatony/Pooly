package io.pooly.app

import org.junit.Assert.*
import org.junit.Test

class NavigationStopPolicyTest {
    @Test fun oldCancellationDuringRouteReplacementDoesNotStopNewRequest(){
        val p=NavigationStopPolicy();p.begin(1000);p.cancelled()
        assertFalse(p.observe(1200,false,false,false,true));assertFalse(p.observe(2200,false,false,false,true))
        assertFalse(p.observe(3000,true,false,true,false))
    }
    @Test fun twoStableInactiveReportsEndConfirmedGuidance(){
        val p=NavigationStopPolicy();p.begin(1000,true)
        assertFalse(p.observe(2000,false,false,true,false));assertTrue(p.observe(2800,false,false,true,false))
    }
    @Test fun aSingleTransientReportDoesNotEndGuidance(){
        val p=NavigationStopPolicy();p.begin(1000,true)
        assertFalse(p.observe(2000,false,false,true,false));assertFalse(p.observe(2500,true,false,true,false));assertFalse(p.observe(3000,false,false,true,false))
    }
    @Test fun routeCalculationAndUnknownInitialStateDoNotMeanCancellation(){
        val p=NavigationStopPolicy();p.begin(1000)
        assertFalse(p.observe(15000,false,false,false,false));assertFalse(p.observe(18000,false,false,false,false))
        p.cancelled();assertFalse(p.observe(19000,false,true,true,false));assertFalse(p.observe(20000,false,false,true,false))
    }
    @Test fun missedCancelCallbackCanBeRecoveredFromFreeDrive(){
        val p=NavigationStopPolicy();p.begin(1000)
        assertFalse(p.observe(2000,true,false,false,false));assertFalse(p.observe(5000,false,false,false,false));assertTrue(p.observe(5800,false,false,false,false))
    }
}

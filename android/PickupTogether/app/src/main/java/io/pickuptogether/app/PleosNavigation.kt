package io.pickuptogether.app

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.app.Activity
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import org.json.JSONArray
import org.json.JSONObject

class PleosNavigation(context: Context, private val event: (JSONObject) -> Unit) {
    private val appContext = context.applicationContext
    private val launchContext = context
    private val helper = NaviHelper(context)
    private val preferences = context.getSharedPreferences("together_session", Context.MODE_PRIVATE)
    private var ready = false
    private val ui = Handler(Looper.getMainLooper())
    private var requested = false
    private var boundDestination: JSONObject? = null
    private var boundWaypoints = emptyList<JSONObject>()
    private var openOnComplete = false
    private var awaitingCompletion = false
    private var generation = 0
    private var windowGeneration = 0
    private var routeDriving = false
    private var boundConfirmed = false
    private var sessionId=""
    private var requestedAt=0L
    private var lastSyncedStatus=""
    private var cancelPending=false
    private var cancelAttempts=0
    private var lastCancelAt=0L
    private var cancelNoticeSent=false
    private var otherDestination: JSONObject?=null
    private var stopCheckScheduled=false
    private val stopPolicy=NavigationStopPolicy()
    private var onConnected: (() -> Unit)? = null
    private var failure = "내비게이션 연결을 기다리고 있어요."
    private var lastDestination: JSONObject? = null
    private var selectionId: String? = null
    private var selectionBaseline: JSONObject? = null
    private var selectionChanged = false
    private var selectionExpectedName: String? = null
    private var screenGeneration = 0
    private var recentPlaces = emptyList<RecentDestinationInfo>()
    private var previewReply: ((JSONObject) -> Unit)? = null
    private var previewPoint: JSONObject? = null
    private var previewAllowsSearch = true
    private var placesReply: ((JSONObject) -> Unit)? = null
    private var placesRequest = 0
    private var tripEstimateReply: ((JSONObject) -> Unit)? = null
    private var tripEstimateCooldown = 0L
    private val guidanceRefresh = object : Runnable {
        override fun run() { if (ready && requested) { refresh(); runCatching { refreshPosition() } }; ui.postDelayed(this, 5000) }
    }
    private val listener = object : NaviHelperEventListener {
        override fun onTripInfo(tripInfo: TripInfo) = onUi {
            val reply = tripEstimateReply ?: return@onUi
            tripEstimateReply = null
            val response = JSONObject()
            if (tripInfo.eta > 0 && tripInfo.distance > 0) {
                response.put("seconds", tripInfo.eta).put("meters", tripInfo.distance)
            }
            reply(response)
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            if (currentLocationInfo.latitude.isFinite() && currentLocationInfo.longitude.isFinite())
                event(JSONObject().put("navigationPosition", JSONObject().put("lat", currentLocationInfo.latitude).put("lon", currentLocationInfo.longitude).put("heading", currentLocationInfo.heading).put("updatedAt", System.currentTimeMillis())))
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = onUi {
            if (selectionId != null) {
                selectionChanged = true; selectionExpectedName = routeStartInfo.name
                val id = selectionId
                // The OEM may publish RouteStarted before its new destination.
                listOf(400L, 1600L, 3500L).forEach { delay -> ui.postDelayed({ if (selectionId == id) helper.getDestinationInfo() }, delay) }
            }
            if (requested) { event(JSONObject().put("navigation", "searching")); refresh() }
        }
        override fun onNavigationStatus(navigationStatus: NavigationStatus) = onUi {
            android.util.Log.d("TogetherNavi", "status ${navigationStatus.name}")
            routeDriving = navigationStatus == NavigationStatus.ROUTE_DRIVE
            event(JSONObject().put("navigationStatus",navigationStatus.name))
            if (requested && routeDriving) { refresh() }
            else if(requested&&navigationStatus==NavigationStatus.FREE_DRIVE) checkStoppedSoon()
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = onUi {
            routeDriving = routeStateInfo.naviMode in setOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
            event(JSONObject().put("navigationMode", routeStateInfo.naviMode.name))
            if(requested&&routeDriving&&boundConfirmed&&!cancelPending) syncStatus("guiding")
            if(requested&&routeStateInfo.naviMode!=NaviMode.NOT_WORKING&&stopPolicy.observe(System.currentTimeMillis(),routeDriving,routeStateInfo.isRouting,boundConfirmed,awaitingCompletion)) {
                stopped(if(cancelPending)"app_ended" else "oem_stopped");return@onUi
            }
            if(requested&&!routeDriving&&!routeStateInfo.isRouting) checkStoppedSoon()
            if(cancelPending&&requested&&routeDriving) sendCancelIfDue()
            if (requested && routeDriving) { helper.getDestinationInfo(); helper.getWaypointInfo() }
        }
        override fun onRoutingCompleted() = onUi {
            if (!requested || cancelPending) return@onUi
            awaitingCompletion = false
            event(JSONObject().put("navigation", "ready").put("navigationMessage", "차량 내비게이션이 경로 탐색을 완료했어요."))
            refresh()
            if (openOnComplete) { openOnComplete = false; try { open() } catch (_: Exception) {} }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = onUi {
            if (destinationInfo.name.isNotBlank() && validPoint(destinationInfo.latitude, destinationInfo.longitude)) {
                val point = JSONObject().put("name", destinationInfo.name).put("lat", destinationInfo.latitude).put("lon", destinationInfo.longitude).put("address", "").put("updatedAt", System.currentTimeMillis())
                lastDestination = point
                event(JSONObject().put("navigationDestination", point))
                val id = selectionId
                if (id != null && (selectionExpectedName == null || selectionExpectedName == point.optString("name")) && (selectionChanged || selectionBaseline?.let { !samePoint(it, point) } == true)) {
                    selectionId = null
                    event(JSONObject().put("navigationSelection", JSONObject().put("id", id).put("state", "selected").put("place", point)))
                }
            }
            val bound = boundDestination ?: return@onUi
            // A driver may change the destination directly in the OEM navigation.
            // Do not attach that unrelated route's ETA to this appointment.
            if (kotlin.math.abs(bound.getDouble("lat") - destinationInfo.latitude) > .002 || kotlin.math.abs(bound.getDouble("lon") - destinationInfo.longitude) > .002) {
                if (boundConfirmed && !awaitingCompletion) {
                    val previous=otherDestination
                    val changedPoint=JSONObject().put("lat",destinationInfo.latitude).put("lon",destinationInfo.longitude)
                    if(previous!=null&&samePoint(previous,changedPoint))stopped("destination_changed")
                    else { otherDestination=changedPoint;val ownGeneration=generation;ui.postDelayed({if(requested&&generation==ownGeneration&&ready)helper.getDestinationInfo()},900) }
                }
                return@onUi
            }
            otherDestination=null
            boundConfirmed = true
            if(routeDriving&&!cancelPending)syncStatus("guiding")
            preferences.edit().putBoolean("navigationConfirmed", true).apply()
            if (requested) {
                val result = JSONObject().put("navigationEta", JSONObject().put("name", destinationInfo.name).put("seconds", destinationInfo.remainTime).put("meters", destinationInfo.remainDistance).put("updatedAt", System.currentTimeMillis()).put("stopId", bound.optString("id")))
                if (routeDriving) { awaitingCompletion = false; result.put("navigation", "ready");  }
                event(result)
            }
        }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = onUi {
            if (requested && boundConfirmed) event(JSONObject().put("navigationWaypoints", JSONArray(waypointInfoList.mapIndexed { index, point ->
                val result = JSONObject().put("name", point.name).put("lat", point.latitude).put("lon", point.longitude).put("seconds", point.remainTime).put("meters", point.remainDistance).put("updatedAt", System.currentTimeMillis())
                // The emulator returns zero waypoint coordinates. Bind only a
                // matching echo of our own confirmed route's waypoint sequence.
                val expected = boundWaypoints.getOrNull(index)
                if (expected != null && waypointInfoList.size == boundWaypoints.size &&
                    (point.name == expected.optString("name") || kotlin.math.abs(point.latitude - expected.getDouble("lat")) < .002 && kotlin.math.abs(point.longitude - expected.getDouble("lon")) < .002)) result.put("stopId", expected.optString("id"))
                result
            })))
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = onUi {
            val target = boundDestination ?: return@onUi
            val destination = lastDestination ?: return@onUi
            if (!requested || !boundConfirmed || cancelPending || otherDestination != null ||
                target.optString("id") !in setOf("dest", "lot") ||
                destinationArrivedInfo.name != destination.optString("name")) return@onUi
            val aid = preferences.getString("navigationAppointment", "").orEmpty()
            if (aid.isBlank()) return@onUi
            event(JSONObject().put("navigationFinalArrival", JSONObject(destination.toString())
                .put("appointmentId", aid).put("sessionId", sessionId)
                .put("stopId", target.getString("id")).put("updatedAt", System.currentTimeMillis())))
        }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) { if (requested) arrived() }
        override fun onDestinationChanged() { if (selectionId != null) selectionChanged = true; refresh() }
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = onUi {
            recentPlaces = recentDestinationInfoList
            finishPreview()
            finishPlaces()
        }
        override fun onError(errorCode: NaviErrorCode) = onUi {
            val selection=selectionId;selectionId=null
            if(selection!=null)event(JSONObject().put("navigationSelection",JSONObject().put("id",selection).put("state","error")))
            // A transport/search error is not evidence that guidance or the appointment ended.
            event(JSONObject().put("navigation","error").put("navigationMessage","차량 내비 오류: ${errorCode.name}"))
            if(requested&&ready)checkStoppedSoon()
        }
        override fun onRouteCancelled() = onUi {
            if(!requested)return@onUi
            android.util.Log.d("TogetherNavi", "route cancelled callback")
            stopPolicy.cancelled()
            checkStoppedSoon()
        }
        override fun onRouteEnded() = onUi {
            if(!requested)return@onUi
            android.util.Log.d("TogetherNavi", "route ended callback")
            stopPolicy.cancelled()
            checkStoppedSoon()
        }

    }
    init {
        ui.postDelayed(guidanceRefresh, 5000)
        helper.initialize({ ready = true; helper.addListener(listener); event(JSONObject().put("navigation", "connected").put("navigationConnected", true)); resumeBinding(); onConnected?.invoke(); onConnected = null }, { error -> failure = "Pleos 내비에 연결할 수 없어요. 차량 내비 실행 상태와 권한을 확인해 주세요."; event(JSONObject().put("navigation", "unavailable").put("navigationConnected", false).put("navigationMessage", failure)); android.util.Log.w("TogetherNavi", error.javaClass.simpleName) })
    }
    private fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == ui.looper) block() else ui.post { block() }
    }
    private fun checkStoppedSoon() {
        if(stopCheckScheduled)return
        stopCheckScheduled=true
        val ownGeneration=generation
        ui.postDelayed({stopCheckScheduled=false;if(requested&&ready&&generation==ownGeneration)helper.getRouteStateInfo()},800)
    }
    private fun syncStatus(status: String, reason: String="") {
        val aid=preferences.getString("navigationAppointment","").orEmpty()
        if(!aid.matches(Regex("[a-f0-9]{24}"))||sessionId.isBlank()||status==lastSyncedStatus)return
        lastSyncedStatus=status
        val data=JSONObject().put("appointmentId",aid).put("sessionId",sessionId).put("requestedAt",requestedAt).put("status",status).put("reason",reason).put("observedAt",System.currentTimeMillis())
        NavigationOutbox.put(preferences,data)
        event(JSONObject().put("navigationSession",data))
    }
    private fun stopped(reason: String) {
        syncStatus("stopped",reason)
        val status=if(reason=="destination_changed")"external" else "cancelled"
        forget()
        event(JSONObject().put("navigation",status).put("navigationDestination",JSONObject.NULL).put("navigationMessage",if(status=="external")"다른 목적지 안내로 전환됐어요." else "차량 내비 안내가 종료됐어요."))
    }
    fun isBoundTo(aid: String)=requested&&aid.isNotBlank()&&preferences.getString("navigationAppointment","")==aid
    fun endAppointment(aid: String): JSONObject {
        if(!isBoundTo(aid))return JSONObject().put("requested",false)
        // A route changed directly in the OEM belongs to that new navigation action.
        if(boundConfirmed&&lastDestination!=null&&boundDestination!=null&&(kotlin.math.abs(lastDestination!!.getDouble("lat")-boundDestination!!.getDouble("lat"))>.002||kotlin.math.abs(lastDestination!!.getDouble("lon")-boundDestination!!.getDouble("lon"))>.002)) {
            stopped("destination_changed");return JSONObject().put("requested",false)
        }
        return cancel()
    }
    fun whenReady(callback: () -> Unit) { if (ready) callback() else onConnected = callback }
    private fun arrived() { event(JSONObject().put("navigationArrival", System.currentTimeMillis()).put("navigationMessage", "차량 내비가 도착을 알렸어요. 정차 후 도착·탑승을 직접 확인해 주세요.")) }
    fun refresh() { if (ready) { helper.getRouteStateInfo(); helper.getDestinationInfo(); helper.getWaypointInfo() } }
    fun refreshPosition() { if (ready) helper.getCurrentLocationInfo() }
    fun estimateTrip(point: JSONObject, reply: (JSONObject) -> Unit) {
        val lat = point.getDouble("lat")
        val lon = point.getDouble("lon")
        require(validPoint(lat, lon))
        if (!ready || tripEstimateReply != null || android.os.SystemClock.elapsedRealtime() < tripEstimateCooldown) {
            reply(JSONObject())
            return
        }
        tripEstimateReply = reply
        try {
            helper.requestTrip(TripRequest(latitude = lat, longitude = lon, poiId = "", poiName = point.optString("name"), poiSubId = ""))
        } catch (_: Exception) {
            tripEstimateReply = null
            reply(JSONObject())
            return
        }
        ui.postDelayed({
            if (tripEstimateReply === reply) {
                tripEstimateReply = null
                tripEstimateCooldown = android.os.SystemClock.elapsedRealtime() + 10_000
                reply(JSONObject())
            }
        }, 8_000)
    }
    fun resumeBinding() {
        if (!ready) return
        val aid = preferences.getString("navigationAppointment", "").orEmpty()
        val destination = preferences.getString("navigationDestination", null)
        if (aid.isNotBlank() && destination != null) try {
            boundDestination = JSONObject(destination); requested = true; boundConfirmed = preferences.getBoolean("navigationConfirmed", false)
            sessionId=preferences.getString("navigationSessionId","").orEmpty().ifBlank { java.util.UUID.randomUUID().toString() }
            requestedAt=preferences.getLong("navigationRequestedAt",System.currentTimeMillis())
            preferences.edit().putString("navigationSessionId",sessionId).putLong("navigationRequestedAt",requestedAt).apply()
            lastSyncedStatus="";stopPolicy.begin(System.currentTimeMillis(),boundConfirmed);syncStatus("requested")
            val points = JSONArray(preferences.getString("navigationRequestedWaypoints", "[]")); boundWaypoints = (0 until points.length()).map { points.getJSONObject(it) }
            event(JSONObject().put("navigationAid", aid).put("navigationEta", JSONObject.NULL).put("navigationWaypoints", JSONArray())); refresh()
        } catch (_: Exception) { forget() }
    }
    fun forget(): JSONObject {
        requested = false; boundConfirmed = false; boundDestination=null; boundWaypoints = emptyList(); awaitingCompletion = false; cancelPending=false;otherDestination=null;generation++
        preferences.edit().remove("navigationAppointment").remove("navigationDestination").remove("navigationConfirmed").remove("navigationRequestedWaypoints").remove("navigationSessionId").remove("navigationRequestedAt").apply()
        event(JSONObject().put("navigationAid", JSONObject.NULL).put("navigationEta", JSONObject.NULL).put("navigationWaypoints", JSONArray()))
        return JSONObject().put("cleared", true)
    }
    fun open(): JSONObject {
        check(ready) { failure }
        helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
        // The emulator accepts the SDK screen request without raising the OEM task.
        // Bring the system's default maps activity forward as well; destination and
        // waypoint delivery still use NaviHelper, not an unrelated map URL.
        val maps = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS)
        val resolved = appContext.packageManager.resolveActivity(maps, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved != null && resolved.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
            // Reuse the initialized map task rather than creating another OEM
            // Activity and another vehicle-initialization flow.
            val screen = appContext.packageManager.getLaunchIntentForPackage(resolved.activityInfo.packageName)
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name))
            screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchContext.startActivity(screen)
            val windowRequest = ++windowGeneration
            ui.postDelayed({ if (windowRequest == windowGeneration) NavigationWindows.revealNavigation() }, 250)
            return JSONObject().put("requested", true).put("message", "차량 내비를 열었어요. 안내 경로를 확인해 주세요.")
        }
        return JSONObject().put("requested", true).put("message", "차량 내비 화면 열기를 요청했어요. 전환되지 않으면 차량의 내비 앱을 열어 주세요.")
    }
    fun cancelPendingWindowReveal() { windowGeneration++ }
    private fun validPoint(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)
    private fun samePoint(a: JSONObject, b: JSONObject) = kotlin.math.abs(a.getDouble("lat") - b.getDouble("lat")) < .0003 && kotlin.math.abs(a.getDouble("lon") - b.getDouble("lon")) < .0003

    /** Search and selection happen inside Pleos Navigation; no geocoder or map URL. */
    fun selectDestination(id: String, keyword: String): JSONObject {
        check(ready) { failure }
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(keyword.isNotBlank()) { "검색할 장소나 주소를 입력해 주세요." }
        previewReply?.invoke(JSONObject().put("superseded", true)); previewReply = null; previewPoint = null
        selectionId = id; selectionBaseline = lastDestination?.let { JSONObject(it.toString()) }; selectionChanged = false; selectionExpectedName = null
        event(JSONObject().put("navigationSelection", JSONObject().put("id", id).put("state", "searching")))
        searchPlace(keyword)
        return JSONObject().put("requested", true).put("selectionId", id)
    }
    fun cancelDestinationSelection(): JSONObject {
        val id = selectionId; selectionId = null; screenGeneration++
        event(JSONObject().put("navigationSelection", JSONObject().put("id", id ?: "").put("state", "cancelled")))
        return JSONObject().put("cancelled", true)
    }
    private fun searchPlace(keyword: String) {
        open()
        val request = ++screenGeneration
        ui.postDelayed({ if (request == screenGeneration && ready) helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword.take(120))) }, 400)
    }
    /** Place previews never request a route and never write a destination. */
    fun places(reply: (JSONObject) -> Unit) {
        check(ready) { failure }
        placesReply?.invoke(JSONObject().put("results", JSONArray()).put("superseded", true))
        placesReply = reply
        val request = ++placesRequest
        helper.getRecentDestinationInfo(); helper.getDestinationInfo()
        ui.postDelayed({ if (request == placesRequest) finishPlaces() }, 2500)
    }
    private fun finishPlaces() {
        val reply = placesReply ?: return; placesReply = null
        val results = JSONArray()
        lastDestination?.let { results.put(JSONObject(it.toString()).put("source", "pleos")) }
        recentPlaces.filter { it.name.isNotBlank() && validPoint(it.latitude, it.longitude) }.forEach {
            results.put(JSONObject().put("name", it.name).put("address", it.address).put("lat", it.latitude).put("lon", it.longitude).put("poiId", it.poiId).put("poiProvider", it.poiProvider).put("source", "pleos"))
        }
        reply(JSONObject().put("results", results).put("provider", "pleos"))
    }
    fun cancelPreview(): JSONObject {
        previewReply?.invoke(JSONObject().put("superseded",true))
        previewReply=null;previewPoint=null;screenGeneration++
        return JSONObject().put("cancelled",true)
    }
    fun previewPlace(point: JSONObject, allowSearchFallback: Boolean = true, reply: (JSONObject) -> Unit) {
        check(ready) { failure }
        require(validPoint(point.getDouble("lat"), point.getDouble("lon"))) { "장소 좌표를 확인해 주세요." }
        require(point.optString("name").isNotBlank()) { "장소 이름을 확인해 주세요." }
        screenGeneration++
        previewReply?.invoke(JSONObject().put("superseded", true))
        previewPoint = JSONObject(point.toString()); previewReply = reply
        previewAllowsSearch = allowSearchFallback
        helper.getRecentDestinationInfo()
        // Compare the request object rather than a place name: two people can
        // share a pickup, and late responses must not complete an older request.
        val request = previewPoint
        ui.postDelayed({ if (previewPoint === request) finishPreview() }, 3000)
    }
    private fun finishPreview() {
        val point = previewPoint ?: return; val reply = previewReply ?: return
        previewPoint = null; previewReply = null
        val recent = recentPlaces.firstOrNull {
            val lat = it.displayLatitude ?: it.latitude; val lon = it.displayLongitude ?: it.longitude
            val nameMatches = it.name.replace(" ", "") == point.getString("name").replace(" ", "")
            it.poiId.isNotBlank() && nameMatches && validPoint(lat, lon) && kotlin.math.abs(lat - point.getDouble("lat")) < .0001 && kotlin.math.abs(lon - point.getDouble("lon")) < .0001
        }
        // External provider IDs (for example VWorld IDs) cannot identify a Pleos POI.
        val poiId = recent?.poiId ?: if(point.optString("source")=="pleos")point.optString("poiId") else ""
        val provider = recent?.poiProvider ?: if(point.optString("source")=="pleos")point.optString("poiProvider") else ""
        if (poiId.isBlank() && !previewAllowsSearch) {
            screenGeneration++
            reply(JSONObject().put("requested", false).put("mode", "selection_only"))
            return
        }
        open()
        val request = ++screenGeneration
        val keyword = (if(point.optString("kind")=="address")point.optString("address").ifBlank { point.getString("name") } else point.getString("name")).take(120)
        ui.postDelayed({ if (request == screenGeneration && ready) {
            if (poiId.isNotBlank()) helper.requestNavigationScreen(NavigationScreenRequest.POIDetailPopup(poiId, provider))
            else helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
        } }, 400)
        val mode = if (poiId.isNotBlank()) "detail" else "search"
        event(JSONObject().put("navigationPreview", JSONObject().put("name", point.getString("name")).put("mode", mode).put("keyword",keyword).put("lat",point.getDouble("lat")).put("lon",point.getDouble("lon")).put("routeRequested", false)))
        reply(JSONObject().put("requested", true).put("mode", mode).put("message", if (mode == "detail") "내비에서 장소 상세를 열었어요." else "내비에서 장소 검색을 열었어요. 검색 결과를 누르면 위치를 볼 수 있어요."))
    }
    fun cancel(): JSONObject {
        if(!ready||!requested)return JSONObject().put("requested",false)
        if(!cancelPending){
            cancelAttempts=0;cancelNoticeSent=false;cancelPending=true
            awaitingCompletion=false;openOnComplete=false
            sendCancelIfDue()
        }
        stopPolicy.cancelled();checkStoppedSoon()
        return JSONObject().put("requested",true)
    }
    private fun sendCancelIfDue() {
        if(cancelAttempts>0&&System.currentTimeMillis()-lastCancelAt<1800)return
        if(cancelAttempts>=4){
            if(!cancelNoticeSent){
                cancelNoticeSent=true
                event(JSONObject().put("navigation","stop_unconfirmed").put("navigationMessage","내비 안내 종료가 확인되지 않았어요. 내비에서 ‘안내 종료’를 눌러 주세요."))
            }
            return
        }
        cancelAttempts++;lastCancelAt=System.currentTimeMillis()
        android.util.Log.d("TogetherNavi", "request route cancel (attempt $cancelAttempts)")
        helper.cancelRoute()
    }
    fun navigate(stops: JSONArray, foreground: Boolean = true, appointmentId: String = ""): JSONObject {
        check(ready) { failure }
        require(appointmentId.isEmpty() || appointmentId.matches(Regex("[a-f0-9]{24}")))
        require(stops.length() > 0) { "안내할 장소가 없어요." }
        for (i in 0 until stops.length()) { val p = stops.getJSONObject(i); require(p.getDouble("lat").isFinite() && p.getDouble("lat") in -90.0..90.0 && p.getDouble("lon").isFinite() && p.getDouble("lon") in -180.0..180.0) }
        // Public SDK supports FIRST and SECOND waypoint indices. For a longer
        // pickup chain, guide the next pickup and request the next leg after boarding.
        val segment = if (stops.length() > 3) listOf(stops.getJSONObject(0)) else (0 until stops.length()).map { stops.getJSONObject(it) }
        val dest = segment.last()
        if(requested)syncStatus("stopped","route_replaced")
        sessionId=java.util.UUID.randomUUID().toString();requestedAt=System.currentTimeMillis();lastSyncedStatus=""
        cancelPending=false;cancelAttempts=0;otherDestination=null;routeDriving=false;generation++
        stopPolicy.begin(requestedAt)
        boundDestination = dest; boundWaypoints = segment.dropLast(1); requested = true; openOnComplete = foreground; awaitingCompletion = true; boundConfirmed = false; routeDriving = false
        preferences.edit().putString("navigationSessionId",sessionId).putLong("navigationRequestedAt",requestedAt).putString("navigationAppointment", appointmentId).putString("navigationDestination", dest.toString()).putString("navigationRequestedWaypoints", JSONArray(boundWaypoints).toString()).putBoolean("navigationConfirmed", false).apply()
        syncStatus("requested")
        event(JSONObject().put("navigation", "requested").put("navigationAid", appointmentId).put("navigationSegmentOnly", stops.length() > 3).put("navigationEta", JSONObject.NULL).put("navigationFinalArrival", JSONObject.NULL).put("navigationWaypoints", JSONArray()))
        val route = RouteInfo(latitude = dest.getDouble("lat"), longitude = dest.getDouble("lon"), poiName = dest.optString("name"), address = dest.optString("address"), poiId = "", poiSubId = "", routeOption = RouteOption.RECOMMENDED)
        if (segment.size == 1) helper.requestRoute(route)
        else {
            val waypoints = segment.dropLast(1).mapIndexed { index, p -> RequestWaypointInfo(latitude = p.getDouble("lat"), longitude = p.getDouble("lon"), poiName = p.optString("name"), waypointIndex = if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND, address = p.optString("address"), poiId = "", poiSubId = "") }
            helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(route, waypoints))
        }
        // Some OEM versions first show a route-choice/confirmation screen.
        // Foreground it on the user's request instead of waiting for guidance to start.
        if (foreground) { openOnComplete = false; open() }
        val requestGeneration = ++generation
        ui.postDelayed({ if (requested && requestGeneration == generation && awaitingCompletion) { openOnComplete = false; event(JSONObject().put("navigation", "timeout").put("navigationMessage", "차량 내비의 경로 완료 응답이 없어요. 내비 화면에서 경로를 확인해 주세요.")) } }, 15000)
        return JSONObject().put("requested", true).put("waypoints", segment.size - 1).put("segmentOnly", stops.length() > 3).put("message", if (stops.length() > 3) "다음 픽업 장소까지 안내를 요청했어요. 탑승 후 다음 경로를 요청해 주세요." else "차량 내비에 경로 탐색을 요청했어요. 완료 응답을 기다려 주세요.")
    }
    fun close() { ui.removeCallbacksAndMessages(null); onConnected = null; placesReply = null; previewReply = null; previewPoint = null; selectionId = null; try { helper.removeListener(listener); helper.release() } catch (_: Exception) {} }
}

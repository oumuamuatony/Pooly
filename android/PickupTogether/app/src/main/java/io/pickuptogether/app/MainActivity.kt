package io.pickuptogether.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationManagerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private var apiBase = BuildConfig.API_BASE_URL
    private var navigation: PleosNavigation? = null
    private var voice: PleosVoice? = null
    private var vehicle: PleosVehicle? = null
    private var sdkObserver: Int? = null
    private var demo: DemoSupport? = null
    private var panel: NavigationPanelWindow? = null
    private var pendingPanel: String? = null
    private val expireMovingPanel = Runnable { if (panel?.mode == "moving") closePanel(false) }
    private var oneShotFused: PleosLocation? = null
    private val sdkStatus = JSONObject()
    private var restrictions: CarRestrictions? = null
    private var restricted = false
    private var foreground = false
    private var locationPermissionReply: ((Boolean) -> Unit)? = null
    private var notificationReply: ((Boolean) -> Unit)? = null
    private val isCar by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) }
    private val preferences by lazy { getSharedPreferences("together_session", MODE_PRIVATE) }
    private val origin = "https://appassets.androidplatform.net"
    private val csp = "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' data: blob:; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'self'"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavigationWindows.register(this)
        getSystemService(android.app.NotificationManager::class.java).apply {
            cancel(1515)
            deleteNotificationChannel("emulator_navigation_overlay_test_v1")
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        web = WebView(this)
        setContentView(web)
        panel = NavigationPanelWindow(this, web)
        with(web.settings) {
            javaScriptEnabled = true; domStorageEnabled = true; textZoom = 100; useWideViewPort = true
            allowFileAccess = false; allowContentAccess = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val result = assets.shouldInterceptRequest(request.url)
                if (result != null) result.responseHeaders = (result.responseHeaders ?: emptyMap()) + mapOf("Content-Security-Policy" to csp, "Referrer-Policy" to "no-referrer", "X-Content-Type-Options" to "nosniff")
                return result
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "https" && request.url.host == "appassets.androidplatform.net" && request.url.path in listOf("/assets/car.html", "/assets/phone.html")) return false
                if (request.url.scheme in listOf("https", "http")) startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
            override fun onPageFinished(view: WebView, url: String) { status(); demo?.pageReady(web, intent.getStringExtra("demoCommand")); intent.removeExtra("demoCommand") }
        }
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean { if (BuildConfig.DEBUG) android.util.Log.d("TogetherWeb", "${message.messageLevel()}: ${message.message()} @${message.lineNumber()}"); return true }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            web.loadData("<h2>Android System WebView를 업데이트해 주세요.</h2>", "text/html", "UTF-8"); return
        }
        WebViewCompat.addWebMessageListener(web, "TogetherNative", setOf(origin)) { _, message, sourceOrigin, isMainFrame, reply ->
            if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != origin) return@addWebMessageListener
            var id = ""
            try {
                val request = JSONObject(message.data ?: "{}"); id = request.getString("id")
                val payload = request.optJSONObject("payload") ?: JSONObject()
                val requestId = id
                fun done(data: JSONObject) { ui.post { try { reply.postMessage(JSONObject().put("id", requestId).put("ok", true).put("data", data).toString()) } catch (_: Exception) {} } }
                fun fail(message: String) { ui.post { try { reply.postMessage(JSONObject().put("id", requestId).put("ok", false).put("error", message).toString()) } catch (_: Exception) {} } }
                when (request.getString("action")) {
                    "api" -> {
                        val path = payload.getString("path"); val method = payload.optString("method", "GET")
                        if (restricted && (method == "PATCH" || path == "/api/contacts" || path == "/api/appointments" && method == "POST")) throw IllegalStateException("차량을 주차한 뒤 약속을 설정해 주세요.")
                        io.execute { try { done(LocalApi.request(apiBase, path, method, payload.optJSONObject("body"), payload.optString("token"))) } catch (_: Exception) { fail(if (apiBase == LocalApi.POOLY_CLOUD) "Pooly 서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요." else "개발용 서버에 연결하지 못했어요. 맥북의 서버와 USB 연결을 확인해 주세요.") } }
                    }
                    "session" -> { preferences.edit().putString("token", payload.getString("token")).putString("role", payload.getString("role")).putString("base", apiBase).apply(); done(JSONObject().put("saved", true)) }
                    "context" -> { val aid = payload.optString("appointmentId"); require(aid.isEmpty() || aid.matches(Regex("[a-f0-9]{24}"))); preferences.edit().putString("appointmentId", aid).apply(); done(JSONObject().put("saved", true)) }
                    "sdkStatus" -> done(JSONObject(sdkStatus.toString()))
                    "demoControls" -> { check(EmulatorTestController.available()); demo?.showControls(); done(JSONObject().put("opened", true)) }
                    "speak" -> { check(isCar); voice?.speak(payload.getString("text")) ?: throw IllegalStateException("차량 음성 서비스를 사용할 수 없어요."); done(JSONObject().put("requested", true)) }
                    "listen" -> { check(isCar); voice?.listen() ?: throw IllegalStateException("차량 음성 서비스를 사용할 수 없어요."); done(JSONObject().put("requested", true)) }
                    "stopVoice" -> { voice?.stop(); done(JSONObject().put("stopped", true)) }
                    "confirmVoice" -> android.app.AlertDialog.Builder(this).setTitle("음성 명령을 실행할까요?").setMessage(payload.getString("title").take(150)).setPositiveButton("실행") { _, _ -> done(JSONObject().put("confirmed", true)) }.setNegativeButton("취소") { _, _ -> done(JSONObject().put("confirmed", false)) }.setOnCancelListener { done(JSONObject().put("confirmed", false)) }.show()
                    "vehicle" -> { check(isCar); vehicle?.refresh(); done(JSONObject().put("requested", true)) }
                    "openNavigation" -> done(navigation?.open() ?: throw IllegalStateException("Pleos 내비 연결을 확인해 주세요."))
                    "selectNavigationDestination" -> {
                        check(isCar && !restricted) { "주차한 뒤 차량 내비에서 목적지를 선택해 주세요." }
                        done(navigation?.selectDestination(payload.getString("selectionId"), payload.optString("keyword")) ?: throw IllegalStateException("Pleos 내비 연결을 확인해 주세요."))
                    }
                    "cancelDestinationSelection" -> done(navigation?.cancelDestinationSelection() ?: JSONObject().put("cancelled", true))
                    "cancelNavigationPreview" -> done(navigation?.cancelPreview() ?: JSONObject().put("cancelled", true))
                    "estimateNavigationTrip" -> {
                        val navi = navigation
                        if (navi == null) done(JSONObject())
                        else navi.estimateTrip(payload.getJSONObject("destination"), ::done)
                    }
                    "previewNavigationPlace" -> {
                        check(isCar && !restricted) { "주차한 뒤 장소를 확인해 주세요." }
                        val navi = navigation ?: throw IllegalStateException("Pleos 내비 연결을 확인해 주세요.")
                        val pickupPanel = payload.optBoolean("pickupPanel")
                        check(!pickupPanel || Settings.canDrawOverlays(this)) { "다른 앱 위에 표시 권한을 허용해 주세요." }
                        val place = payload.getJSONObject("place")
                        if (pickupPanel && place.optString("name") == "선택한 픽업 위치") {
                            showPanel("pickup")
                            done(JSONObject().put("requested", true).put("mode", "coordinates"))
                        } else navi.previewPlace(place, payload.optBoolean("allowSearchFallback", true)) { result ->
                            try {
                                if (pickupPanel && result.optBoolean("requested")) showPanel("pickup", openNavigation = false)
                                done(result)
                            } catch (error: Exception) { fail(error.message ?: "픽업 위치 창을 열지 못했어요.") }
                        }
                    }
                    "showNavigationPanel" -> {
                        check(isCar) { "Pleos 차량에서 사용할 수 있어요." }
                        val mode = payload.getString("mode"); require(mode in setOf("create", "moving"))
                        check(mode != "create" || !restricted) { "주차한 뒤 일정을 만들어 주세요." }
                        if (!Settings.canDrawOverlays(this)) {
                            val settings = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                            check(settings.resolveActivity(packageManager) != null) { "이 차량에는 표시 권한 설정 화면이 없어요. 앱 설치 단계에서 다른 앱 위에 표시 권한이 필요해요." }
                            pendingPanel = mode
                            startActivity(settings)
                            done(JSONObject().put("permissionRequired", true))
                        } else { showPanel(mode); done(JSONObject().put("shown", true).put("mode", mode)) }
                    }
                    "closeNavigationPanel" -> { closePanel(payload.optBoolean("returnToApp", true)); done(JSONObject().put("closed", true)) }
                    "dialPickup" -> {
                        val number = payload.optString("number").take(32)
                        require(number.matches(Regex("[+0-9 ()-]*")))
                        val dial = Intent(Intent.ACTION_DIAL).apply { if (number.isNotBlank()) data = Uri.fromParts("tel", number, null) }
                        check(dial.resolveActivity(packageManager) != null) { "연결된 휴대폰이나 차량 전화 앱을 확인해 주세요." }
                        closePanel(false); startActivity(dial)
                        done(JSONObject().put("opened", true).put("numberKnown", number.isNotBlank()))
                    }
                    "navigationPlaces" -> {
                        check(isCar && !restricted) { "주차한 뒤 장소를 선택해 주세요." }
                        val navi = navigation ?: throw IllegalStateException("Pleos 내비 연결을 확인해 주세요.")
                        navi.places(::done)
                    }
                    "refreshNavigation" -> { navigation?.refresh(); done(JSONObject().put("requested", true)) }
                    "location" -> ensureLocation { allowed -> if (!allowed) fail("위치 권한을 허용하지 않았어요. 지도에서 장소를 선택할 수도 있어요.") else locate(::done, ::fail) }
                    "notifications" -> {
                        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationReply = { allowed -> status(); done(JSONObject().put("granted", allowed)) }
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
                        } else done(JSONObject().put("granted", NotificationManagerCompat.from(this).areNotificationsEnabled()))
                    }
                    "share" -> { check(!restricted) { "주차한 뒤 초대 링크를 공유해 주세요." }; startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, payload.getString("text").take(3000)), "약속 초대 공유")); done(JSONObject().put("shared", true)) }
                    "tracking" -> {
                        if (!payload.optBoolean("enabled")) { stopService(Intent(this, JourneyService::class.java)); done(JSONObject().put("enabled", false)) }
                        else ensureLocation { allowed ->
                            if (!allowed) fail("위치 권한을 허용해야 공유할 수 있어요.")
                            else { val aid = payload.getString("appointmentId"); require(aid.matches(Regex("[a-f0-9]{24}"))); startForegroundService(Intent(this, JourneyService::class.java).putExtra("appointmentId", aid).putExtra("base", apiBase).putExtra("token", preferences.getString("token", ""))); done(JSONObject().put("enabled", true)); status() }
                        }
                    }
                    "endNavigation" -> {
                        val aid=payload.getString("appointmentId");require(aid.matches(Regex("[a-f0-9]{24}")))
                        done(navigation?.endAppointment(aid) ?: JSONObject().put("requested",false))
                    }
                    "cancelNavigation" -> done(navigation?.cancel() ?: JSONObject().put("requested", false))
                    "forgetNavigation" -> done(navigation?.forget() ?: JSONObject().put("cleared", true))
                    "navigate" -> {
                        check(isCar) { "Pleos 차량에서 사용할 수 있어요." }
                        val needed = listOf("android.car.permission.CAR_SPEED", "pleos.car.permission.CAR_SEATS", "pleos.car.permission.CAR_CLIMATE", "pleos.car.permission.CONTROL_CAR_SEATS").filter { permission ->
                            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED && runCatching { packageManager.getPermissionInfo(permission,0).protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false)
                        }
                        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(),103)
                        val aid = payload.optString("appointmentId")
                        val result = navigation?.navigate(payload.getJSONArray("stops"), payload.optBoolean("foreground", true), aid) ?: throw IllegalStateException("Pleos 내비 연결을 확인해 주세요.")
                        if (aid.isNotBlank()) startForegroundService(Intent(this, GuidanceService::class.java).putExtra("appointmentId", aid))
                        done(result)
                    }
                    else -> fail("지원하지 않는 기능이에요.")
                }
            } catch (error: Exception) { try { reply.postMessage(JSONObject().put("id", id).put("ok", false).put("error", error.message ?: "처리하지 못했어요.").toString()) } catch (_: Exception) {} }
        }
        if (isCar) {
            PleosRuntime.start(applicationContext)
            navigation = PleosRuntime.navigation; voice = PleosRuntime.voice; vehicle = PleosRuntime.vehicle
            sdkObserver = PleosRuntime.observe(::event)
            val boundAid=preferences.getString("navigationAppointment","").orEmpty()
            val syncAid = boundAid.ifBlank {
                NavigationOutbox.pending(preferences).lastOrNull()?.optString("appointmentId").orEmpty()
            }
            if(syncAid.matches(Regex("[a-f0-9]{24}")))startForegroundService(Intent(this,GuidanceService::class.java).putExtra("appointmentId",syncAid))
            try { restrictions = CarRestrictions(this) { value -> restricted = value; if (value) { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM); if (panel?.mode == "create") closePanel(false) } else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM); status() } } catch (_: Throwable) { restricted = true }
        }
        if (EmulatorTestController.available()) demo = DemoSupport(this, navigation) { JSONObject(sdkStatus.toString()) }.also { it.start() }
        open(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); closePanel(false); navigation?.cancelPendingWindowReveal(); NavigationWindows.restore(this); foreground = true; web.onResume(); setIntent(intent)
        val differentBase = BuildConfig.DEBUG && intent.hasExtra("apiUrl") && intent.getStringExtra("apiUrl")?.let { LocalApi.allowedBase(it) != apiBase } == true
        if (intent.action == Intent.ACTION_VIEW || differentBase || intent.hasExtra("role")) open(intent)
        else { status(); web.evaluateJavascript("if(window.Together)Together.refresh();", null); demo?.pageReady(web, intent.getStringExtra("demoCommand")); intent.removeExtra("demoCommand") }
    }
    private fun open(intent: Intent) {
        apiBase = BuildConfig.API_BASE_URL
        if (BuildConfig.DEBUG && intent.action != Intent.ACTION_VIEW) intent.getStringExtra("apiUrl")?.let { apiBase = LocalApi.allowedBase(it) }
        val deepToken = intent.data?.takeIf { it.scheme == "togethermove" && it.host == "invite" }?.getQueryParameter("token")?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,600}")) }
        val role = if (deepToken != null || BuildConfig.DEBUG && intent.getStringExtra("role") == "phone" || !isCar) "phone" else "car"
        val query = if (deepToken != null) "?invite=${Uri.encode(deepToken)}" else ""
        web.loadUrl("$origin/assets/$role.html$query")
    }
    private fun status() { event(JSONObject(sdkStatus.toString()).put("foreground", foreground || panel?.visible == true).put("navigationPanel", panel?.mode ?: "").put("panelBackgroundBlur", panel?.backgroundBlurApplied == true).put("overlayPermission", Settings.canDrawOverlays(this)).put("restricted", restricted).put("locationPermission", hasLocation()).put("fusedPermission", checkSelfPermission("pleos.car.permission.FUSED_LOCATION") == PackageManager.PERMISSION_GRANTED).put("notifications", NotificationManagerCompat.from(this).areNotificationsEnabled()).put("automotive", isCar).put("apiPort", java.net.URI(apiBase).port)) }
    private fun event(data: JSONObject) { ui.post { data.keys().forEach { key -> sdkStatus.put(key, data.get(key)) }; if (!isFinishing && ::web.isInitialized) web.evaluateJavascript("window.dispatchEvent(new CustomEvent('together:native',{detail:$data}));", null) } }
    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun ensureLocation(callback: (Boolean) -> Unit) {
        val fusedNeeded = isCar && checkSelfPermission("pleos.car.permission.FUSED_LOCATION") != PackageManager.PERMISSION_GRANTED
        if (hasLocation() && !fusedNeeded) callback(true)
        else if (locationPermissionReply != null) callback(false)
        else {
            locationPermissionReply = callback
            val needed = mutableListOf<String>()
            if (!hasLocation()) needed += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fusedNeeded) needed += "pleos.car.permission.FUSED_LOCATION"
            requestPermissions(needed.toTypedArray(), 101)
        }
    }
    @Suppress("MissingPermission")
    private fun locate(done: (JSONObject) -> Unit, fail: (String) -> Unit) {
        if (isCar) {
            oneShotFused?.close()
            val fused = PleosLocation(this, ::event); oneShotFused = fused
            var finished = false
            fun fallback() { ui.post { if (!finished) { finished = true; fused.close(); if (oneShotFused === fused) oneShotFused = null; event(JSONObject().put("locationSource", "android-fallback")); locateAndroid(done, fail) } } }
            val supported = fused.start({ point -> ui.post { if (!finished) { finished = true; fused.close(); if (oneShotFused === fused) oneShotFused = null; event(JSONObject().put("locationSource", "pleos-fused")); done(point) } } }, ::fallback)
            if (!supported) fallback() else ui.postDelayed({ fallback() }, 5000)
        } else locateAndroid(done, fail)
    }
    @Suppress("MissingPermission")
    private fun locateAndroid(done: (JSONObject) -> Unit, fail: (String) -> Unit) {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        if (providers.isEmpty()) { fail("기기의 위치 기능을 켜 주세요."); return }
        val recent = providers.mapNotNull { manager.getLastKnownLocation(it) }.filter { System.currentTimeMillis() - it.time < 60000 && it.accuracy <= 100 }.maxByOrNull { it.time }
        if (recent != null) { done(point(recent)); return }
        var finished = false
        lateinit var listener: LocationListener
        fun finish(location: Location?) { if (finished) return; finished = true; manager.removeUpdates(listener); if (location == null) fail("현재 위치를 확인하지 못했어요. 지도에서 장소를 선택해 주세요.") else done(point(location)) }
        listener = LocationListener { location -> if (location.accuracy <= 100) finish(location) }
        providers.forEach { manager.requestLocationUpdates(it, 1000, 0f, listener, Looper.getMainLooper()) }
        ui.postDelayed({ finish(null) }, 14000)
    }
    private fun point(location: Location) = JSONObject().put("lat", location.latitude).put("lon", location.longitude).put("accuracy", location.accuracy.toDouble()).put("mock", location.isFromMockProvider || android.os.Build.MODEL.contains("emulator", true)).put("name", "현재 위치").put("address", "")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 101) { val callback = locationPermissionReply; locationPermissionReply = null; callback?.invoke(hasLocation()) }
        if (code == 102) { val callback = notificationReply; notificationReply = null; callback?.invoke(NotificationManagerCompat.from(this).areNotificationsEnabled()) }
        status()
    }
    private fun showPanel(mode: String, openNavigation: Boolean = true) {
        check(mode == "moving" || !restricted) { "주차한 뒤 화면을 열어 주세요." }
        check(sdkStatus.optBoolean("navigationConnected")) { "Pleos 내비 연결을 기다려 주세요." }
        if (openNavigation) navigation?.cancelPendingWindowReveal()
        ui.removeCallbacks(expireMovingPanel)
        panel?.show(mode)
        if (mode == "moving") ui.postDelayed(expireMovingPanel, 7000)
        try { if (openNavigation) navigation?.open() } catch (error: Exception) { panel?.close(); throw error }
        status()
    }
    private fun closePanel(returnToApp: Boolean) {
        pendingPanel = null
        ui.removeCallbacks(expireMovingPanel)
        if (panel?.visible != true) return
        if (panel?.mode in setOf("create", "pickup")) navigation?.cancelPreview()
        panel?.close(); status()
        if (returnToApp) {
            navigation?.cancelPendingWindowReveal(); NavigationWindows.restore(this)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
    private fun keepWebActive() = panel?.visible == true || !sdkStatus.isNull("navigationAid") && sdkStatus.optString("navigationAid").isNotBlank()
    fun onNavigationWindowHidden() { foreground = false; voice?.stop(); if (::web.isInitialized) { if (keepWebActive()) web.onResume() else web.onPause() }; status() }
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity && NavigationWindows.isHidden(this)) {
            closePanel(false); navigation?.cancelPendingWindowReveal(); NavigationWindows.restore(this); foreground = true
            if (::web.isInitialized) { web.onResume(); status(); web.evaluateJavascript("if(window.Together)Together.refresh();", null) }
        }
    }
    override fun onResume() { super.onResume(); NavigationWindows.restore(this); foreground = true; navigation?.resumeBinding(); if (::web.isInitialized) { web.onResume(); status(); web.evaluateJavascript("if(window.Together)Together.refresh();", null) }; pendingPanel?.let { mode -> pendingPanel = null; if (Settings.canDrawOverlays(this)) ui.post { try { showPanel(mode) } catch (error: Exception) { event(JSONObject().put("panelError", error.message)) } } } }
    override fun onPause() { foreground = !NavigationWindows.isHidden(this) && !isFinishing; voice?.stop(); status(); if (::web.isInitialized && !foreground && !keepWebActive()) web.onPause(); super.onPause() }
    override fun onStop() { foreground = false; status(); if (::web.isInitialized && !keepWebActive()) web.onPause(); super.onStop() }
    @Deprecated("Activity back callback")
    override fun onBackPressed() { if (::web.isInitialized) web.evaluateJavascript("window.dispatchEvent(new CustomEvent('together:back'))", null) else super.onBackPressed() }
    override fun onDestroy() { panel?.close(); demo?.close(); PleosRuntime.removeObserver(sdkObserver); NavigationWindows.unregister(this); oneShotFused?.close(); restrictions?.close(); io.shutdownNow(); ui.removeCallbacksAndMessages(null); if (::web.isInitialized) web.destroy(); super.onDestroy() }
}

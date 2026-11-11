package io.pickuptogether.app

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the native car app and its emulator controls, without opening a browser. */
@RunWith(AndroidJUnit4::class)
class InEmulatorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = "http://127.0.0.1:8766"
    private fun state() = LocalApi.request(base, "/api/demo/state", "GET").getJSONObject("data")
    private fun awaitState(predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + 35000
        var current = state()
        while (System.currentTimeMillis() < deadline) {
            current = state()
            if (predicate(current)) return current
            Thread.sleep(350)
        }
        fail("Emulator state timed out: phase=" + current.getJSONObject("appointment").getJSONObject("trip").getString("phase") + ", running=" + current.optBoolean("running"))
        return current
    }
    private fun evaluate(scenario: ActivityScenario<MainActivity>, code: String): String {
        val latch = CountDownLatch(1); var result = "null"
        scenario.onActivity { activity ->
            (activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as WebView).evaluateJavascript(code) { value -> result = value; latch.countDown() }
        }
        check(latch.await(8, TimeUnit.SECONDS))
        return result
    }
    private fun awaitWeb(scenario: ActivityScenario<MainActivity>, expression: String) {
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            if (evaluate(scenario, expression) == "true") return
            Thread.sleep(300)
        }
        fail("Car UI timed out: $expression")
    }
    private fun command(value: String) {
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).putExtra("demoCommand", value).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            val bytes = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
            File(instrumentation.targetContext.cacheDir, "$name.png.b64").writeText(android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP))
        }
    }
    @Test fun carAppReplaysAndFriendsRespondWithoutDashboard() {
        assumeTrue(EmulatorTestController.available())
        val previous = state().getJSONObject("appointment").getString("id")
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("demoCommand", "newTest").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val fresh = awaitState { it.getJSONObject("appointment").getString("id") != previous && context.getSharedPreferences("together_session", 0).getBoolean("emulatorTestEnabled", false) }
            val aid = fresh.getJSONObject("appointment").getString("id")
            awaitWeb(scenario, "!!(window.Together&&Together.active()&&Together.active().id==='$aid'&&!Together.app.loading&&document.getElementById('demo-label'))")
            val deadline = System.currentTimeMillis() + 8000
            var dismissed = false
            while (!dismissed && System.currentTimeMillis() < deadline) {
                val buttons = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("앱으로 돌아가기")
                dismissed = buttons?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!dismissed) Thread.sleep(300)
            }
            assertTrue("Native emulator test dialog must be usable", dismissed)
            evaluate(scenario, "Together.carComponent.setState({screen:'detail',sheet:null});true")
            awaitWeb(scenario, "Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()==='출발하기')")
            evaluate(scenario, "Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='출발하기').click();true")
            val driving = awaitState { value ->
                val members = value.getJSONObject("appointment").getJSONArray("members")
                value.optBoolean("running") && (0 until members.length()).all { members.getJSONObject(it).optString("ready") == "walking" }
            }
            val start = driving.getJSONObject("position")
            val moved = awaitState { value ->
                val a = value.getJSONObject("appointment")
                val point = value.getJSONObject("telemetry").optJSONObject("fusedPosition")
                point != null && kotlin.math.abs(point.optDouble("lat") - start.getDouble("lat")) > .0001 && a.getJSONArray("track").length() >= 2 && a.optDouble("distance_m") > 20
            }
            assertTrue(moved.getJSONObject("telemetry").getJSONObject("fusedPosition").optBoolean("mock"))
            command("pauseTest")
            val paused = awaitState { !it.optBoolean("running") }
            val pausedPoint = paused.getJSONObject("position").toString()
            Thread.sleep(2500)
            assertEquals(pausedPoint, state().getJSONObject("position").toString())
            capture("in-emulator-paused")
            // The driver confirms arrival; the virtual passenger must respond
            // automatically, while driver boarding remains a separate action.
            evaluate(scenario, "Together.refresh(true).then(()=>Together.action('arrive'));true")
            awaitState { value ->
                val a = value.getJSONObject("appointment")
                a.getJSONObject("trip").optString("phase") == "arrived" && !a.getJSONArray("members").getJSONObject(0).isNull("boarding_confirmed_at")
            }
            assertEquals(0, state().getJSONObject("appointment").getJSONObject("trip").getJSONArray("boarded").length())
            evaluate(scenario, "Together.refresh(true).then(()=>Together.action('board'));true")
            awaitState { it.getInt("leg") == 1 }
            command("resumeTest")
            val resumed = awaitState { it.optBoolean("running") && it.getInt("leg") == 1 }
            assertEquals(1, resumed.getJSONObject("appointment").getJSONObject("trip").getJSONArray("boarded").length())
            command("pauseTest")
            awaitState { !it.optBoolean("running") }
            File(context.cacheDir, "in-emulator-result.json").writeText(JSONObject().put("native_controls", true).put("auto_motion", true).put("auto_friend_response", true).put("manual_driver_boarding", true).put("pause_resume", true).put("fused_source", moved.getJSONObject("telemetry").getJSONObject("fusedPosition").getString("source")).toString(2))
        }
    }
}

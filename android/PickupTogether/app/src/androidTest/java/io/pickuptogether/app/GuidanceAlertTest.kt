package io.pickuptogether.app

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The retired 15-second test alert must never return. Necessary panels expire. */
@RunWith(AndroidJUnit4::class)
class GuidanceAlertTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun evaluate(scenario: ActivityScenario<MainActivity>, code: String): String {
        val latch = CountDownLatch(1); var result = "null"
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("web").apply { isAccessible = true }
            (field.get(activity) as WebView).evaluateJavascript(code) { result = it; latch.countDown() }
        }
        check(latch.await(8, TimeUnit.SECONDS)); return result
    }
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(150) }
        fail(message)
    }
    @Test fun necessaryPanelClosesAfterSevenSecondsWithoutLegacyTestNotification() {
        assumeTrue(EmulatorTestController.available())
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            awaitCondition("UI or native navigation unavailable") { evaluate(scenario, "!!(window.Together&&Together.carComponent&&Together.app.nativeStatus.navigationConnected)") == "true" }
            assertEquals("\"\"", evaluate(scenario, "Together.app.nativeStatus.navigationPanel||''"))
            val manager = context.getSystemService(NotificationManager::class.java)
            assertFalse(manager.activeNotifications.any { it.id == 1515 })
            val started = SystemClock.elapsedRealtime()
            evaluate(scenario, "Together.carComponent.setState({screen:'moving',form:null});Together.native('showNavigationPanel',{mode:'moving'});true")
            awaitCondition("Necessary panel did not appear") { evaluate(scenario, "Together.app.nativeStatus.navigationPanel==='moving'") == "true" }
            assertEquals("true", evaluate(scenario, "!!document.querySelector('.notice-progress i')"))
            Thread.sleep(1200)
            assertEquals("true", evaluate(scenario, "Together.app.nativeStatus.navigationPanel==='moving'"))
            awaitCondition("Panel did not expire") { evaluate(scenario, "!Together.app.nativeStatus.navigationPanel") == "true" }
            assertTrue(SystemClock.elapsedRealtime() - started >= 6800)
            assertFalse(manager.activeNotifications.any { it.id == 1515 })
        }
    }
}

package io.pickuptogether.app

import android.content.Intent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The browser joins the invitation on a disposable Flask instance during the test. */
@RunWith(AndroidJUnit4::class)
class FlaskWebLinkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val webField = MainActivity::class.java.getDeclaredField("web").apply { isAccessible = true }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        scenario.onActivity { activity ->
            (webField.get(activity) as WebView).evaluateJavascript(script) { result = it; done.countDown() }
        }
        check(done.await(8, TimeUnit.SECONDS))
        return result
    }

    private fun await(scenario: ActivityScenario<MainActivity>, condition: String, timeoutMs: Long = 25_000) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (evaluate(scenario, condition) == "true") return
            Thread.sleep(350)
        }
        fail("Condition timed out: $condition")
    }

    @Test fun browserPickupAndVehicleDepartureShareOneFlaskAppointment() {
        val launch = Intent(context, MainActivity::class.java)
            .putExtra("apiUrl", "http://127.0.0.1:59583")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            await(scenario, "!!(window.Together&&Together.app.user&&!Together.app.loading&&Together.app.nativeStatus.navigationConnected)")
            val meeting = Instant.now().plusSeconds(7200)
            evaluate(scenario, """
                Together.addContact('웹 테스트').then(friend=>Together.create({
                    name:'Flask 연동 테스트',meeting_at:'$meeting',
                    origin:Together.app.places[0],destination:Together.app.places[1],
                    members:[{id:friend.id,mode:'pickup'}]
                })).then(a=>window.qaFlaskInvite=Together.inviteUrl(a.members[0]));
                true
            """.trimIndent())
            await(scenario, "typeof window.qaFlaskInvite==='string'")
            context.filesDir.resolve("flask-test-invite").writeText(JSONTokener(evaluate(scenario, "window.qaFlaskInvite")).nextValue() as String)
            await(scenario, "Together.active()?.members[0]?.ready==='ready'", 85_000)
            assertEquals("true", evaluate(scenario, "Together.active().members[0].pickup.description==='웹에서 입력한 출구'"))
            evaluate(scenario, """
                (async()=>{
                    await Together.action('depart');
                    await Together.action('arrive');
                    await Together.action('board');
                    await Together.action('park');
                    await Together.action('finish');
                })();
                true
            """.trimIndent())
            await(scenario, "Together.active()?.trip.phase==='finished'")
        }
    }
}

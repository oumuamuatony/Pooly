package io.pickuptogether.app

import android.content.Intent
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses a separate app identity and disposable API; never changes personal appointments. */
@RunWith(AndroidJUnit4::class)
class SearchPanelKeyboardTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun web(a: MainActivity) = MainActivity::class.java.getDeclaredField("web").let { it.isAccessible=true;it.get(a) as WebView }
    private fun panel(a: MainActivity) = MainActivity::class.java.getDeclaredField("panel").let { it.isAccessible=true;it.get(a) as NavigationPanelWindow }
    private fun eval(s: ActivityScenario<MainActivity>, code: String): String {
        val ready=CountDownLatch(1);var value="null"
        s.onActivity { a->web(a).evaluateJavascript(code){value=it;ready.countDown()} }
        check(ready.await(8,TimeUnit.SECONDS));return value
    }
    private fun waitFor(s: ActivityScenario<MainActivity>, code: String, seconds: Int=25) {
        val until=System.currentTimeMillis()+seconds*1000
        while(System.currentTimeMillis()<until){if(eval(s,code)=="true")return;Thread.sleep(200)}
        fail("Timed out: $code; "+eval(s,"document.body.innerText.slice(-900)"))
    }
    private fun bounds(s: ActivityScenario<MainActivity>): List<Int> {
        var result=listOf<Int>();s.onActivity { a->val v=web(a);val xy=IntArray(2);v.getLocationOnScreen(xy);result=listOf(xy[0],xy[1],v.width,v.height) };return result
    }
    private fun capture(name: String) {
        val dir=inst.targetContext.filesDir
        val bmp=inst.uiAutomation.takeScreenshot();assertNotNull("Screenshot not available",bmp)
        java.io.File(dir,name).outputStream().use { bmp!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        android.util.Log.i("PoolySearchTest","CAPTURE $name at ${dir.absolutePath}")
    }
    @Test fun glassSearchKeepsBoundsWhenRealImeOpensAndSelectsLiveVworldPlace() {
        ActivityScenario.launch<MainActivity>(Intent(inst.targetContext,MainActivity::class.java).putExtra("apiUrl","http://127.0.0.1:59583").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { s->
            try {
                waitFor(s,"!!(window.Together&&Together.app.user&&!Together.app.loading&&Together.carComponent)")
                val host = bounds(s)
                eval(s,"Together.addContact('Max').then(()=>window.qaFriendReady=true);true")
                waitFor(s,"window.qaFriendReady===true")
                eval(s,"Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='일정 만들기').click();true")
                waitFor(s,"document.documentElement.dataset.navigationPanel==='create'")
                eval(s,"document.querySelector('.add-portrait').click();true")
                waitFor(s,"!!document.querySelector('.friend-picker .friend-choice')")
                assertEquals("true",eval(s,"!document.querySelector('.friend-picker').innerText.includes('친구 관리')"))
                eval(s,"document.querySelector('.friend-picker .friend-choice').click();true")
                eval(s,"document.querySelector('.friend-picker .sf .bp').click();true")
                waitFor(s,"document.querySelector('.member-name')?.textContent==='Max'")
                val creationBounds=bounds(s)
                eval(s,"Together.carComponent.openDestinationSearch();true")
                waitFor(s,"!!document.getElementById('placeQ')")
                Thread.sleep(500)
                val before=bounds(s)
                assertEquals("Search and creation must share window bounds",creationBounds,before)
                assertEquals(680.0/420.0, before[3].toDouble()/before[2], .005)
                assertTrue("Search panel should start at the left edge of the navigation pane", before[0]-host[0] in 0..30)
                assertTrue("Search panel should cover the built-in search at the bottom", (host[1]+host[3])-(before[1]+before[3]) in 0..30)
                val layout=eval(s,"JSON.stringify({w:document.getElementById('dc-root').style.width,h:document.getElementById('dc-root').style.height,z:document.getElementById('dc-root').style.zoom})")
                eval(s,"document.getElementById('placeQ').focus();true")
                s.onActivity { a->web(a).requestFocus();a.getSystemService(InputMethodManager::class.java).showSoftInput(web(a),InputMethodManager.SHOW_IMPLICIT) }
                var shown=false;val until=System.currentTimeMillis()+8000
                while(System.currentTimeMillis()<until){s.onActivity { a->shown=web(a).rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true };if(shown)break;Thread.sleep(200)}
                assertTrue("The real software keyboard was not visible",shown)
                Thread.sleep(500)
                assertEquals("Native glass window moved/resized",before,bounds(s))
                assertEquals("Web content was rescaled",layout,eval(s,"JSON.stringify({w:document.getElementById('dc-root').style.width,h:document.getElementById('dc-root').style.height,z:document.getElementById('dc-root').style.zoom})"))
                capture("keyboard-fixed.png")
                eval(s,"Together.carComponent.onQ('성수역');Together.carComponent.searchDestinations();true")
                waitFor(s,"Together.carComponent.state.destinationProvider==='vworld'&&document.querySelectorAll('.place-result').length>0")
                assertEquals("true",eval(s,"!document.querySelector('.search-source')&&!document.querySelector('.place-pin')&&!document.querySelector('.place-result em')&&!document.querySelector('.search-panel').innerText.includes('브이월드')"))
                val destinationBefore=eval(s,"JSON.stringify((()=>{var p=Together.app.nativeStatus.navigationDestination;return p?[p.name,p.lat,p.lon]:null;})())")
                val routeBefore=inst.targetContext.getSharedPreferences("together_session",0).getString("navigationDestination",null)
                eval(s,"(()=>{const native=Together.native;window.qaPreviewCalls=0;Together.native=function(action,payload){const response=native(action,payload);if(action==='previewNavigationPlace'){window.qaPreviewCalls++;response.then(value=>window.qaPreview=value);}return response;};return true;})()")
                eval(s,"document.querySelector('.place-result').click();true")
                waitFor(s,"!!Together.carComponent.state.destinationSelection")
                waitFor(s,"window.qaPreview?.requested===true&&['search','detail'].includes(window.qaPreview.mode)&&Together.carComponent.state.screen==='search'")
                eval(s,"window.dispatchEvent(new CustomEvent('together:native',{detail:{navigationMessage:'차량 내비 안내가 종료됐어요.'}}));true")
                assertEquals("No toast may cover destination results", "true", eval(s,"!document.querySelector('.toast')&&!Together.carComponent.state.toast"))
                Thread.sleep(900)
                assertEquals("Preview must keep the search panel in place",before,bounds(s))
                assertEquals("Preview must not replace the guidance destination",routeBefore,inst.targetContext.getSharedPreferences("together_session",0).getString("navigationDestination",null))
                assertEquals(destinationBefore,eval(s,"JSON.stringify((()=>{var p=Together.app.nativeStatus.navigationDestination;return p?[p.name,p.lat,p.lon]:null;})())"))
                capture("destination-preview.png")
                eval(s,"Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='목적지로 설정').click();true")
                waitFor(s,"Together.carComponent.state.screen==='create'&&Together.carComponent.state.form.destination_place.source==='vworld'")
                assertEquals("Confirming the selection must keep the creation panel in place",creationBounds,bounds(s))
                assertEquals("1", eval(s,"window.qaPreviewCalls"))
                val point=eval(s,"JSON.stringify(Together.carComponent.state.form.destination_place)")
                android.util.Log.i("PoolySearchTest","PASS keyboard bounds=$before unchanged, VWorld selection=$point")
                capture("vworld-selected.png")
            } finally { s.onActivity { a->PleosRuntime.navigation?.cancelPreview();a.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(web(a).windowToken,0);panel(a).close() } }
        }
    }
}

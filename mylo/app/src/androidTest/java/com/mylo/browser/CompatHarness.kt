package com.mylo.browser

import android.graphics.Rect
import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.mylo.browser.web.PageNotice
import com.mylo.browser.web.TabEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Rule

/**
 * Drives Mylo like a person on a real device: real taps (UiAutomator touches at a page element's screen
 * position, so pages see genuine user gestures), Mylo's own buttons and dialogs, Android's permission
 * dialogs. Page state is read with JavaScript only to check results. Evidence (screenshots + JSON) goes to
 * test-artifacts/compat/<case>/.
 *
 * Compose's test clock only moves when a test advances it, so every wait here pumps frames.
 */
abstract class CompatHarness {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    protected val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    protected val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    protected val context get() = instrumentation.targetContext
    protected val session: BrowserSession get() = main { ViewModelProvider(compose.activity)[BrowserSession::class.java] }
    protected val engine: TabEngine get() = session.engine
    protected val store: BrowserStore get() = session.store

    inner class Case(val dir: File) {
        val evidence = JSONObject()
        private var shots = 0
        fun shot(label: String) {
            pump(700)
            device.takeScreenshot(File(dir, String.format("%02d-%s.png", ++shots, label)))
        }
        fun note(key: String, value: Any?) { evidence.put(key, value ?: JSONObject.NULL) }
        fun step(text: String) { evidence.append("steps", text) }
    }

    protected fun case(name: String, body: Case.() -> Unit) {
        val dir = File(context.getExternalFilesDir(null), "test-artifacts/compat/$name").apply { deleteRecursively(); mkdirs() }
        val case = Case(dir)
        case.note("case", name)
        try {
            case.body()
            case.note("verified", true)
        } catch (failure: Throwable) {
            case.note("verified", false)
            case.note("failure", failure.message ?: failure.javaClass.simpleName)
            runCatching { case.note("state", tabsState()) }
            runCatching { device.takeScreenshot(File(dir, "99-failure.png")) }
            throw failure
        } finally {
            File(dir, "evidence.json").writeText(case.evidence.toString(2))
        }
    }

    // --- time ----------------------------------------------------------------------------------------

    protected fun pump(millis: Long) {
        var left = millis
        while (left > 0) { compose.mainClock.advanceTimeBy(50); SystemClock.sleep(50); left -= 50 }
    }

    protected fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            pump(200)
        }
        if (!condition()) throw AssertionError("Timed out waiting for $what. State: ${tabsState()}")
    }

    protected fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    // --- Mylo ----------------------------------------------------------------------------------------

    protected fun visibleTab(): Long? = main { engine.visibleTab }
    protected fun tabIds(): List<Long> = main { store.tabs.map { it.id } }
    protected fun openerOf(tabId: Long): Long? = main { store.tabs.firstOrNull { it.id == tabId }?.openerId }
    protected fun notice(tabId: Long? = visibleTab()): PageNotice? = main { tabId?.let { engine.page(it).notice } }
    protected fun pageUrl(tabId: Long? = visibleTab()): String = main { tabId?.let { engine.webViewIfLive(it)?.url }.orEmpty() }

    protected fun tabsState(): JSONObject = main {
        JSONObject().put("visibleTab", engine.visibleTab).put("tabs", JSONArray().apply {
            store.tabs.forEach { tab ->
                val page = engine.page(tab.id)
                put(JSONObject().put("id", tab.id).put("opener", tab.openerId).put("url", engine.webViewIfLive(tab.id)?.url ?: tab.url)
                    .put("title", page.title).put("notice", page.notice?.toString()).put("error", page.error))
            }
        }).put("prompts", JSONArray(engine.prompts.map { it.javaClass.simpleName }))
            .put("fullscreen", engine.fullscreen != null)
    }

    /** Home's own search box, exactly as a person submits an address or search. */
    protected fun openFromHome(input: String) {
        tapNav("Home", optional = true)
        pump(500)
        compose.onNodeWithContentDescription(SEARCH_FIELD).performClick()
        compose.onNodeWithTag(SEARCH_INPUT).assertIsFocused()
        compose.onNodeWithTag(SEARCH_INPUT).performTextInput(input)
        compose.onNodeWithTag(SEARCH_INPUT).performImeAction()
        pump(500)
    }

    /** The same navigation Mylo's address bar performs, for later steps within a case. */
    protected fun go(url: String) {
        main {
            val tab = engine.visibleTab ?: error("No tab on screen")
            store.updateTab(tab, url, url)
            engine.load(tab, url)
        }
        pump(400)
    }

    protected fun waitForPage(timeoutMs: Long = 30_000, matches: (String) -> Boolean): String {
        var url = ""
        waitFor("a page matching the expected address (last $url)", timeoutMs) {
            url = pageUrl()
            val loaded = main { visibleTab()?.let { engine.webViewIfLive(it)?.progress } } == 100
            matches(url) && loaded && js("document.readyState") == "complete"
        }
        return url
    }

    // --- pages ---------------------------------------------------------------------------------------

    /** Runs [script] in the tab's page and returns its result (strings unquoted). */
    protected fun js(script: String, tabId: Long? = null): String? {
        val latch = CountDownLatch(1)
        var raw: String? = null
        instrumentation.runOnMainSync {
            val view = (tabId ?: engine.visibleTab)?.let { engine.webViewIfLive(it) }
            if (view == null) latch.countDown() else view.evaluateJavascript(script) { raw = it; latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
        val value = raw ?: return null
        if (value == "null") return null
        return runCatching { JSONTokener(value).nextValue() }.getOrNull()?.let { if (it is String) it else value } ?: value
    }

    protected fun textOf(id: String, tabId: Long? = null): String =
        js("(function(){var e=document.getElementById('$id');return e?e.textContent:''})()", tabId).orEmpty()

    protected fun waitForText(id: String, timeoutMs: Long = 20_000, tabId: Long? = null, predicate: (String) -> Boolean): String {
        var text = ""
        waitFor("#$id to show the expected result (last '$text')", timeoutMs) { text = textOf(id, tabId); predicate(text) }
        return text
    }

    /** A real touch on the element [expression] finds (scrolled into view first). */
    protected fun tapElement(expression: String) {
        val found = js("(function(){var e=$expression;if(!e)return null;e.scrollIntoView({block:'center',inline:'center'});return 'ok'})()")
        if (found != "ok") throw AssertionError("No page element for $expression on ${pageUrl()}")
        pump(500)
        val rect = JSONArray(js("(function(){var e=$expression;var r=e.getBoundingClientRect();return JSON.stringify([r.left+r.width/2,r.top+r.height/2,window.innerWidth])})()")
            ?: throw AssertionError("Element $expression disappeared"))
        val (x, y) = main {
            val view: WebView = engine.webViewIfLive(engine.visibleTab!!)!!
            val location = IntArray(2).also(view::getLocationOnScreen)
            val scale = view.width / rect.getDouble(2)
            (location[0] + rect.getDouble(0) * scale).toInt() to (location[1] + rect.getDouble(1) * scale).toInt()
        }
        device.click(x, y)
        pump(400)
    }

    protected fun tap(id: String) = tapElement("document.getElementById('$id')")

    /** Finds a visible link or button by its words, the way a person scans a page. */
    protected fun findByText(pattern: String): String? = js("""(function(){
        var re=new RegExp(${JSONObject.quote(pattern)},'i');
        var nodes=[].slice.call(document.querySelectorAll('a,button,[role=button],[role=link],input[type=submit],input[type=button]'));
        function visible(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>8&&r.height>8&&s.visibility!='hidden'&&s.display!='none'&&s.opacity!='0';}
        function label(e){return ((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.value||'')).replace(/\s+/g,' ').trim();}
        var hits=nodes.filter(function(e){return visible(e)&&re.test(label(e));});
        hits.sort(function(a,b){return label(a).length-label(b).length;});
        document.querySelectorAll('[data-mylo-target]').forEach(function(e){e.removeAttribute('data-mylo-target');});
        if(!hits.length)return null;
        hits[0].setAttribute('data-mylo-target','1');
        return label(hits[0]).slice(0,120);
    })()""")

    protected fun tapFound() = tapElement("document.querySelector('[data-mylo-target]')")

    // --- native UI -----------------------------------------------------------------------------------

    protected fun findNative(selector: BySelector, timeoutMs: Long = 10_000): UiObject2? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            device.findObject(selector)?.let { return it }
            pump(200)
        }
        return device.findObject(selector)
    }

    /** Mylo's own text: an exact label, or a row whose merged text starts with it (cards, list rows). */
    protected fun tapText(text: String, timeoutMs: Long = 10_000, optional: Boolean = false): Boolean {
        val deadline = SystemClock.elapsedRealtime() + if (optional) 1_500 else timeoutMs
        var found: UiObject2? = null
        while (found == null && SystemClock.elapsedRealtime() < deadline) {
            found = device.findObject(By.text(text)) ?: device.findObject(By.textStartsWith(text))
            if (found == null) pump(200)
        }
        if (found == null) { if (optional) return false; throw AssertionError("No \"$text\" on screen. State: ${tabsState()}") }
        found.click()
        pump(500)
        return true
    }

    /** The bottom navigation bar (so a page's own "Home" link is never mistaken for it). */
    protected fun tapNav(label: String, optional: Boolean = false): Boolean {
        val deadline = SystemClock.elapsedRealtime() + if (optional) 1_500 else 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            device.findObjects(By.text(label)).firstOrNull { it.visibleBounds.top > device.displayHeight * 0.8 }?.let {
                it.click(); pump(600); return true
            }
            pump(200)
        }
        if (optional) return false
        throw AssertionError("No \"$label\" in the bottom bar")
    }

    protected fun tapDesc(description: String, timeoutMs: Long = 10_000) {
        val found = findNative(By.desc(description), timeoutMs) ?: throw AssertionError("No \"$description\" button on screen")
        found.click()
        pump(500)
    }

    /** Android's own runtime-permission dialog: "While using the app". */
    protected fun allowInAndroidDialog(case: Case, label: String) {
        val button = findNative(By.res(Pattern.compile(".*:id/permission_allow_(foreground_only_)?button")), 10_000)
            ?: throw AssertionError("Android's permission dialog did not appear")
        case.shot("$label-android-permission")
        button.click()
        pump(800)
    }

    protected fun bounds(obj: UiObject2): Rect = obj.visibleBounds

    companion object {
        const val APP = "http://localhost:8080"
        const val SIGN_IN_SERVER = "http://127.0.0.1:8081"
        const val SEARCH_FIELD = "Search or enter address"
        const val SEARCH_INPUT = "search-input"
    }
}

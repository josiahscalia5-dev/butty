package com.mylo.browser

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.KeyEvent
import android.webkit.CookieManager
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real app + real IME, with no Compose test clock or WebView idling resource. */
@RunWith(AndroidJUnit4::class)
class SettingsSearchFlowTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val evidenceDir get() = File(instrumentation.targetContext.getExternalFilesDir(null),
        "test-artifacts/settings-search").apply { mkdirs() }

    @Test fun google() = verify(SearchProvider.GOOGLE, "https://www.google.com/search?q=Facebook")
    @Test fun brave() = verify(SearchProvider.BRAVE, "https://search.brave.com/search?q=Facebook")
    @Test fun duckduckgo() = verify(SearchProvider.DUCKDUCKGO, "https://duckduckgo.com/?q=Facebook")
    @Test fun bing() = verify(SearchProvider.BING, "https://www.bing.com/search?q=Facebook")
    @Test fun yahoo() = verify(SearchProvider.YAHOO, "https://search.yahoo.com/search?p=Facebook")
    @Test fun startpage() = verify(SearchProvider.STARTPAGE, "https://www.startpage.com/sp/search?query=Facebook")
    @Test fun domainOpensDirectly() = verify(SearchProvider.BING, "https://example.com", "example.com")

    @Test fun browserBackForward() {
        val folder = File(evidenceDir, "toolbar-navigation").apply { mkdirs() }
        val report = JSONObject().put("verified", false)
        try {
            submitHome("example.com", folder, report)
            waitForNavigation()
            assertChrome()
            assertFalse("Forward starts disabled", node(By.desc("Forward")).isEnabled)
            val next = "https://example.com/?mylo=toolbar-navigation-long-address-check"
            node(By.desc("Browser address")).click()
            device.pressKeyCode(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            device.executeShellCommand("input text '$next'")
            node(By.text(next).pkg("com.mylo.browser"))
            capture(folder, "05-edit-long-address.png")
            node(By.desc("Go")).click()
            waitForUrl(next)
            waitUntil("WebView must retain Back history", 5_000) { browserState().optBoolean("canGoBack") }
            capture(folder, "06-long-address.png")
            node(By.desc("Back")).click()
            waitForUrl("https://example.com/")
            waitUntil("Forward must become enabled", 5_000) { node(By.desc("Forward")).isEnabled }
            node(By.desc("Forward")).click()
            waitForUrl(next)
            activityRule.scenario.onActivity {
                assertEquals("Address navigation reuses the tab", 1, ViewModelProvider(it)[BrowserSession::class.java].store.tabs.size)
            }
            assertChrome()
            capture(folder, "07-forward.png")
            device.executeShellCommand("wm size 990x1760") // 360 x 640 dp, same density.
            node(By.desc("Browser address"))
            assertChrome()
            capture(folder, "08-toolbar-360-portrait.png")
            report.put("verified", true).put("backAndForward", true).put("currentTabReused", true)
                .put("portraitWidthsDp", "393, 360")
        } catch (failure: Throwable) {
            report.put("failure", failure.toString())
            runCatching { capture(folder, "failure.png") }
            runCatching { device.dumpWindowHierarchy(File(folder, "failure-ui.xml")) }
            throw failure
        } finally {
            device.executeShellCommand("wm size 1080x2340")
            File(folder, "evidence.json").writeText(report.toString(2))
        }
    }

    @Test fun shieldState() {
        // Verify Android's transport classification without inventing a VPN connection.
        assertFalse(hasVpnTransport(null))
        assertFalse(hasVpnTransport(NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)))
        assertTrue(hasVpnTransport(NetworkCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_VPN)))
        var active = false
        activityRule.scenario.onActivity {
            val manager = it.getSystemService(ConnectivityManager::class.java)
            active = manager.allNetworks.any { network -> hasVpnTransport(manager.getNetworkCapabilities(network)) }
        }
        node(By.text("Mylo Shield"))
        node(By.text(if (active) "VPN connected" else "Not connected"))
        assertFalse("No invented endpoint", device.hasObject(By.text("Singapore")))
        val folder = File(evidenceDir, "shield").apply { mkdirs() }
        capture(folder, "01-home-shield.png")
        node(By.text(if (active) "Manage" else "Set up")).click()
        node(By.textContains("Mylo does not include a VPN service"))
        node(By.text("Open Android VPN settings"))
        capture(folder, "02-shield-setup.png")
        File(folder, "evidence.json").writeText(JSONObject().put("verified", true)
            .put("actualAndroidVpnActive", active).put("transportClassificationChecked", true)
            .put("vpnTunnelEstablishedByTest", false)
            .put("limitation", "No installed VPN provider/server is available; a connected tunnel and network-callback transition were not exercised.")
            .toString(2))
    }

    private fun browserState(): JSONObject {
        val state = JSONObject()
        activityRule.scenario.onActivity { activity ->
            findWebView(activity.window.decorView)?.let { view ->
                state.put("url", view.url).put("canGoBack", view.canGoBack()).put("canGoForward", view.canGoForward())
            }
        }
        return state
    }

    private fun waitForUrl(url: String) = waitUntil("Expected WebView URL $url", 12_000) {
        browserState().optString("url") == url
    }

    private fun assertChrome() {
        listOf("Back", "Forward", "Browser address", "Reload", "Bookmark this page").forEach {
            val bounds = node(By.desc(it)).visibleBounds
            assertTrue("$it must fit portrait width", bounds.width() > 0 && bounds.left >= 0 && bounds.right <= device.displayWidth)
        }
        listOf("Home", "Tabs", "Mylo").forEach { node(By.text(it).pkg("com.mylo.browser")) }
    }

    // Called in a fresh instrumentation process by the shell script, after Yahoo was
    // chosen using Settings in the previous process. This tests a real app restart.
    @Test fun savedDefaultSurvivesProcessRestart() {
        assertEquals(SearchProvider.YAHOO, BrowserStore(instrumentation.targetContext).provider)
        submitHome("Facebook", File(evidenceDir, "restart").apply { mkdirs() }, JSONObject())
        assertEquals("https://search.yahoo.com/search?p=Facebook", waitForNavigation())
    }

    private fun verify(provider: SearchProvider, expectedUrl: String, input: String = "Facebook") {
        val folder = File(evidenceDir, if (input == "Facebook") provider.name.lowercase() else "direct-domain")
            .apply { mkdirs() }
        val report = JSONObject().put("provider", provider.name).put("input", input)
            .put("expectedUrl", expectedUrl).put("routingVerified", false)
        try {
            if (provider == SearchProvider.YAHOO) {
                File(evidenceDir, "yahoo-network-ready").delete()
                File(evidenceDir, "yahoo-network-complete").delete()
                activityRule.scenario.onActivity {
                    WebView.setWebContentsDebuggingEnabled(true) // Instrumentation only, for passive CDP evidence.
                }
            }
            node(By.desc("Settings")).click()
            node(By.text("Mylo Settings"))
            node(By.text("Default search provider"))
            var choice = device.findObject(By.text(provider.displayName))
            repeat(2) {
                if (choice == null) {
                    device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .7f)
                    choice = device.findObject(By.text(provider.displayName))
                }
            }
            assertNotNull("Provider must be selectable in Settings", choice)
            choice!!.click()
            activityRule.scenario.onActivity { activity ->
                assertEquals(provider, ViewModelProvider(activity)[BrowserSession::class.java].store.provider)
                assertEquals(provider, BrowserStore(activity).provider)
            }
            capture(folder, "01-settings.png")
            node(By.desc("Close Mylo Settings")).click()
            submitHome(input, folder, report)
            val actual = waitForNavigation()
            report.put("actualWebViewUrl", actual)
            assertEquals("Saved Settings provider must match the actual WebView request",
                expectedUrl, if (input == "example.com") actual.removeSuffix("/") else actual)
            report.put("routingVerified", true)
            // Observe the live response once. Consent/CAPTCHA/network failures are
            // reported separately from routing; never substitute a result document.
            val baseline = observeLivePage(expectedUrl)
            report.put("livePage", baseline)
            if (provider == SearchProvider.YAHOO) {
                val settings = JSONObject()
                activityRule.scenario.onActivity { activity ->
                    findWebView(activity.window.decorView)?.let { view ->
                        settings.put("javaScriptEnabled", view.settings.javaScriptEnabled)
                            .put("domStorageEnabled", view.settings.domStorageEnabled)
                            .put("acceptCookies", CookieManager.getInstance().acceptCookie())
                            .put("acceptThirdPartyCookies", CookieManager.getInstance().acceptThirdPartyCookies(view))
                            .put("defaultMobileUserAgent", view.settings.userAgentString == android.webkit.WebSettings.getDefaultUserAgent(activity))
                            .put("webViewPackage", WebView.getCurrentWebViewPackage()?.packageName)
                            .put("webViewVersion", WebView.getCurrentWebViewPackage()?.versionName)
                    }
                }
                report.put("webViewSettings", settings)
                capture(folder, "04-yahoo-original-url-baseline.png")
                // One diagnostic reload only, after CDP attaches to the real WebView.
                // Challenges get passive observation instead; no bypass or provider fallback.
                val body = baseline.optString("body").lowercase()
                val challenge = listOf("captcha", "not a bot", "unusual traffic", "verify you are human").any(body::contains)
                File(evidenceDir, "yahoo-network-ready").writeText(if (challenge) "observe-only" else "reload-once")
                val deadline = SystemClock.elapsedRealtime() + 20_000
                val complete = File(evidenceDir, "yahoo-network-complete")
                while (!complete.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
                report.put("networkTraceCompleted", complete.exists())
                report.put("afterNetworkObservation", observeLivePage(expectedUrl))
            }
            capture(folder, "04-provider-webview.png")
        } catch (failure: Throwable) {
            report.put("failure", failure.message ?: failure.javaClass.simpleName)
            runCatching { capture(folder, "failure.png") }
            runCatching { device.dumpWindowHierarchy(File(folder, "failure-ui.xml")) }
            throw failure
        } finally {
            File(folder, "evidence.json").writeText(report.toString(2))
        }
    }

    private fun submitHome(input: String, folder: File, report: JSONObject) {
        val field = node(By.desc("Search or enter address"))
        capture(folder, "02-home.png")
        field.click()
        var imeBottom = 0
        waitUntil("Android keyboard must open", 5_000) {
            var visible = false
            activityRule.scenario.onActivity {
                val insets = ViewCompat.getRootWindowInsets(it.window.decorView)
                visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            }
            visible
        }
        // The original artwork and original field must both remain on screen.
        assertNotNull("Home corgi must remain visible while typing", device.findObject(By.descContains("A corgi on the moon")))
        assertTrue("Home input must be above the IME", field.visibleBounds.bottom <= device.displayHeight - imeBottom)
        // The content-description node wraps the actual Compose editor, so
        // ACTION_SET_TEXT on that wrapper is ignored. Type through Android's
        // input dispatcher into the focused editor instead (fixed test data only).
        require(input == "Facebook" || input == "example.com")
        device.executeShellCommand("input text $input")
        node(By.text(input).pkg("com.mylo.browser"))
        report.put("homeVisibleWithKeyboard", true).put("typedInHomeField", true)
        capture(folder, "03-home-query-keyboard.png")
        // Tap the real Android soft-keyboard Search key, not a synthetic app button.
        node(By.desc("Search")).click()
        report.put("submittedWithAndroidKeyboard", true)
    }

    private fun waitForNavigation(): String {
        var url = ""
        waitUntil("Home keyboard Search must create a WebView navigation", 12_000) {
            activityRule.scenario.onActivity { activity ->
                findWebView(activity.window.decorView)?.let { view ->
                    // originalUrl preserves the actual submitted provider URL across redirects.
                    url = view.originalUrl.orEmpty().ifBlank { view.url.orEmpty() }
                }
            }
            url.startsWith("https://")
        }
        return url
    }

    private fun observeLivePage(expectedUrl: String): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var page = JSONObject()
        do {
            val latch = CountDownLatch(1)
            activityRule.scenario.onActivity { activity ->
                val webView = findWebView(activity.window.decorView)
                if (webView == null) latch.countDown() else webView.evaluateJavascript(
                    """JSON.stringify({url:location.href,title:document.title,ready:document.readyState,
                        body:document.body?document.body.innerText.slice(0,12000):'',
                        userAgent:navigator.userAgent,links:document.querySelectorAll('a[href]').length})"""
                ) { raw ->
                    runCatching { page = JSONObject(JSONTokener(raw).nextValue() as String) }
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            if (page.optString("ready") == "complete" && page.optString("body").length > 80) break
            SystemClock.sleep(200)
        } while (SystemClock.elapsedRealtime() < deadline)
        val body = page.optString("body").lowercase()
        val url = page.optString("url")
        val host = Uri.parse(url).host.orEmpty()
        val expectedHost = Uri.parse(expectedUrl).host.orEmpty().removePrefix("www.")
        val blocked = listOf("captcha", "unusual traffic", "verify you are human", "verify you're",
            "before you continue", "access denied", "bots use duckduckgo", "confirm you're a human",
            "webpage not available", "err_", "checking your browser", "robot", "temporary problems searching for web pages").any {
                body.contains(it) || url.contains(it, ignoreCase = true)
            }
        page.put("observation", when {
            blocked -> "external_provider_challenge_consent_or_network_error"
            !(host == expectedHost || host.endsWith(".$expectedHost")) -> "external_redirect_or_document_not_ready"
            page.optString("ready") == "complete" && body.contains("facebook") && page.optInt("links") > 5 -> "live_provider_document_received"
            else -> "live_results_not_confirmed_within_8_seconds"
        })
        return page
    }

    private fun node(selector: androidx.test.uiautomator.BySelector): UiObject2 {
        device.wait(Until.findObject(selector), 5_000)?.let { return it }
        // Only dismiss this known emulator launcher failure. Never hide a Mylo
        // crash/ANR, consent page, or search-provider challenge.
        if (device.hasObject(By.text("Pixel Launcher isn't responding"))) {
            capture(evidenceDir, "emulator-launcher-anr.png")
            device.findObject(By.text("Close app"))?.click()
            device.wait(Until.findObject(selector), 5_000)?.let { return it }
        }
        throw AssertionError("Missing Android UI element: $selector")
    }

    private fun waitUntil(message: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun capture(folder: File, name: String) {
        assertTrue("Device screenshot failed", device.takeScreenshot(File(folder, name)))
    }
}

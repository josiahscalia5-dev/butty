package com.mylo.browser

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests against the real app. The live test deliberately has no fixture,
 * proxy, fake result document, or replacement WebViewClient. Run it separately
 * from APK assembly because a provider can deny a CI/emulator network address.
 */
@RunWith(AndroidJUnit4::class)
class LiveSearchFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val artifacts: File
        get() = File(instrumentation.targetContext.getExternalFilesDir(null), "test-artifacts")
            .apply { mkdirs() }

    @Test
    fun searchInputFocusKeyboardAndProviderPersistence() {
        SearchProvider.entries.forEach { provider ->
            chooseProvider(provider)
            compose.runOnIdle { assertEquals(provider, BrowserStore(compose.activity).provider) }
        }
        openSearch()
        compose.onNodeWithTag(SEARCH_INPUT).performTextInput(QUERY)
        compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals(QUERY).assertIsFocused()
        compose.onNodeWithTag("home-header").assertIsDisplayed()
        compose.onNodeWithTag(SEARCH_MODE).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(SearchProvider.STARTPAGE, BrowserStore(compose.activity).provider) }
        compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals(QUERY)
    }

    @Test
    fun googleSearchLoadsRealResultsAndBackReturnsHome() {
        val evidence = File(artifacts, "live-search-evidence.json")
        artifacts.listFiles()?.filter { it.name.matches(Regex("0[1-9]-.*\\.png")) }?.forEach { it.delete() }
        evidence.writeText(JSONObject().put("verified", false).put("status", "Started").toString(2))
        try {
            compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
            capture("01-home.png")
            openSearch()
            capture("02-search-input-and-keyboard.png")
            chooseProvider(SearchProvider.GOOGLE, "03-settings.png")
            openSearch()
            compose.onNodeWithTag(SEARCH_INPUT).performTextInput(QUERY)
            capture("04-query-and-keyboard.png")
            compose.onNodeWithTag(SEARCH_INPUT).performImeAction()
            compose.onNodeWithTag(SEARCH_MODE).assertDoesNotExist()

            val result = waitForRealGoogleResults()
            assertBrowserControls()
            var firstTabId = 0L
            compose.runOnIdle {
                assertEquals("First search must use exactly one tab", 1, store().tabs.size)
                firstTabId = store().tabs.single().id
                assertEquals(SearchProvider.GOOGLE, store().provider)
            }
            capture("05-real-google-results.png")
            // Exercise a link supplied by Google's actual document, without replacing
            // the WebViewClient, injecting results, or synthesizing a destination.
            clickRealResultLink()
            val linkedPage = waitForExternalPage()
            capture("06-opened-result.png")
            compose.onNodeWithContentDescription("Back").performClick()
            waitForRealGoogleResults()
            compose.onNodeWithContentDescription("Forward").assertIsEnabled().performClick()
            waitForExternalPage()
            compose.onNodeWithContentDescription("Back").performClick()
            waitForRealGoogleResults()

            // Returning through the initial search lands on native Home.
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
            capture("07-back-to-home.png")
            openSearch()
            submitInput(QUERY)
            waitForRealGoogleResults()
            compose.runOnIdle {
                assertEquals(1, store().tabs.size)
                assertEquals(firstTabId, store().tabs.single().id)
            }
            evidence.writeText(result.put("verified", true)
                .put("flow", "Home Settings → save Google → Home input and Android keyboard → query → real mobile Google results")
                .put("openedResult", linkedPage)
                .put("backAndForwardVerified", true)
                .put("currentTabReused", true).toString(2))
        } catch (failure: Throwable) {
            captureFailure("live-search-failure.png")
            evidence.writeText(JSONObject().put("verified", false)
                .put("failure", failure.message ?: failure.javaClass.simpleName)
                .put("note", "No simulated search results were substituted.").toString(2))
            throw failure
        }
    }

    @Test
    fun allRealSearchProvidersLoadInsideCurrentTab() {
        val report = JSONArray()
        val failures = mutableListOf<String>()
        var currentTabId: Long? = null
        SearchProvider.entries.forEach { provider ->
            val filename = "provider-${provider.name.lowercase()}"
            val evidence = JSONObject().put("provider", provider.displayName).put("query", QUERY)
                .put("verified", false).put("savedDefault", provider.displayName)
                .put("temporarySelection", false)
            File(artifacts, "$filename.png").delete()
            try {
                // Recover to native Home after a denied provider; select through the same UI a user sees.
                recoverToHome()
                openSearch()
                chooseProvider(provider)
                openSearch()
                submitInput(QUERY)
                compose.waitUntil(5_000) { currentWebView() != null }
                compose.runOnIdle {
                    assertEquals("Settings must save the selected default", provider, store().provider)
                    assertEquals(provider, BrowserStore(compose.activity.application).provider)
                    assertEquals("Changing search engines must reuse the current tab", 1, store().tabs.size)
                    val id = store().tabs.single().id
                    if (currentTabId == null) currentTabId = id else assertEquals(currentTabId, id)
                }
                val page = waitForRealProviderResults(provider, 30_000)
                assertBrowserControls()
                capture("$filename.png")
                compose.runOnIdle {
                    assertEquals(provider, BrowserStore(compose.activity.application).provider)
                }
                evidence.put("verified", true).put("document", page).put("savedDefaultUnchanged", true)
                compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
                evidence.put("homeControlReturnedHome", true)
            } catch (failure: Throwable) {
                val reason = failure.message ?: failure.javaClass.simpleName
                captureFailure("$filename-failure.png")
                evidence.put("verified", false).put("failure", reason)
                failures.add("${provider.displayName}: $reason")
            }
            report.put(evidence)
            File(artifacts, "$filename.json").writeText(evidence.toString(2))
            File(artifacts, "all-providers-evidence.json").writeText(JSONObject()
                .put("allVerified", failures.isEmpty() && report.length() == SearchProvider.entries.size)
                .put("providers", report).toString(2))
        }
        assertTrue("Live provider checks failed or were blocked:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun directUrlsBackForwardAndHomeReuseCurrentTab() {
        val evidence = JSONObject().put("verified", false)
        val report = File(artifacts, "direct-url-navigation-evidence.json")
        try {
            openSearch()
            submitInput("https://example.com")
            val first = waitForExampleDomain("example.com")
            var tabId = 0L
            compose.runOnIdle {
                assertEquals(1, store().tabs.size)
                tabId = store().tabs.single().id
            }
            evidence.put("directUrl", first)
            capture("navigation-01-direct-url.png")
            navigateAddress("https://example.org")
            evidence.put("secondUrl", waitForExampleDomain("example.org"))
            compose.runOnIdle { assertEquals(tabId, store().tabs.single().id) }
            compose.onNodeWithContentDescription("Back").performClick()
            evidence.put("back", waitForExampleDomain("example.com"))
            compose.onNodeWithContentDescription("Forward").assertIsEnabled().performClick()
            evidence.put("forward", waitForExampleDomain("example.org"))
            capture("navigation-02-forward.png")
            compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
            openSearch()
            submitInput("example.com")
            evidence.put("bareAddressFromHome", waitForExampleDomain("example.com"))
            compose.runOnIdle {
                assertEquals("Home URL submission must keep the current tab", 1, store().tabs.size)
                assertEquals(tabId, store().tabs.single().id)
            }
            compose.onNodeWithContentDescription("Back").performClick()
            evidence.put("backAfterHomeSubmission", waitForExampleDomain("example.org"))
            compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
            evidence.put("earlierHistoryPreserved", true).put("currentTabReused", true).put("verified", true)
        } catch (failure: Throwable) {
            captureFailure("direct-url-navigation-failure.png")
            evidence.put("failure", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            report.writeText(evidence.toString(2))
        }
    }

    @Test
    fun switchingTabsPreservesEachBackForwardHistory() {
        val report = File(artifacts, "per-tab-navigation-evidence.json")
        val evidence = JSONObject().put("verified", false)
        try {
            openSearch()
            submitInput("example.com")
            waitForExampleDomain("example.com")
            navigateAddress("example.org")
            val firstTabLastPage = waitForExampleDomain("example.org").getString("url")
            compose.onNodeWithText("Tabs", useUnmergedTree = true).performClick()
            compose.onNodeWithText("New tab").performClick()
            compose.onNodeWithTag(SEARCH_INPUT).assertIsFocused()
            assertKeyboardVisible()
            submitInput("example.net")
            waitForExampleDomain("example.net")
            navigateAddress("example.com")
            val secondTabLastPage = waitForExampleDomain("example.com").getString("url")
            compose.runOnIdle { assertEquals(2, store().tabs.size) }

            selectTab(firstTabLastPage)
            waitForExampleDomain("example.org")
            compose.onNodeWithContentDescription("Back").performClick()
            waitForExampleDomain("example.com")
            // Both tab rows now show example.com; the second row is the second tab.
            // Switch away while the first tab has a Forward entry to preserve both sides.
            selectTab(secondTabLastPage, index = 1)
            waitForExampleDomain("example.com")
            compose.onNodeWithContentDescription("Back").performClick()
            waitForExampleDomain("example.net")
            compose.onNodeWithContentDescription("Forward").assertIsEnabled().performClick()
            waitForExampleDomain("example.com")
            // Tab rows show the exact current URL. At this point both tabs show
            // example.com, so select the first row by its position in the tab model.
            selectTab(secondTabLastPage)
            waitForExampleDomain("example.com")
            compose.onNodeWithContentDescription("Forward").assertIsEnabled().performClick()
            waitForExampleDomain("example.org")
            compose.runOnIdle { assertEquals(2, store().tabs.size) }
            capture("navigation-03-independent-tabs.png")
            evidence.put("verified", true).put("tabCount", 2)
                .put("firstTab", "example.com → example.org")
                .put("secondTab", "example.net → example.com")
                .put("independentBackAndForward", true)
        } catch (failure: Throwable) {
            captureFailure("per-tab-navigation-failure.png")
            evidence.put("failure", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            report.writeText(evidence.toString(2))
        }
    }

    private fun openSearch() {
        compose.onNodeWithContentDescription(SEARCH_FIELD).performClick()
        compose.onNodeWithTag(SEARCH_MODE).assertDoesNotExist()
        compose.onNodeWithTag(SEARCH_INPUT).assertIsDisplayed().assertIsFocused()
        assertKeyboardVisible()
    }

    private fun recoverToHome() {
        device.pressBack()
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
    }

    private fun submitInput(value: String) {
        compose.onNodeWithTag(SEARCH_INPUT).performTextReplacement(value)
        compose.onNodeWithTag(SEARCH_INPUT).performImeAction()
        // Flush the state change that replaces native input with the WebView
        // before polling Android views outside the Compose test clock.
        compose.onNodeWithTag(SEARCH_MODE).assertDoesNotExist()
        compose.waitForIdle()
    }

    private fun chooseProvider(provider: SearchProvider, screenshot: String? = null) {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("default-provider-${provider.name}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(provider, BrowserStore(compose.activity).provider) }
        screenshot?.let(::capture)
        compose.onNodeWithContentDescription("Close Mylo Settings").performClick()
    }

    private fun assertKeyboardVisible() {
        compose.waitUntil(5_000) {
            var visible = false
            instrumentation.runOnMainSync {
                visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
    }

    private fun assertBrowserControls() {
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Forward").assertIsDisplayed()
        listOf("Home", "Tabs", "Mylo").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    private fun navigateAddress(value: String) {
        compose.onNodeWithContentDescription("Browser address").performClick().performTextReplacement(value)
        compose.onNodeWithContentDescription("Browser address").performImeAction()
        compose.waitForIdle()
    }

    private fun selectTab(url: String, index: Int = 0) {
        compose.onNodeWithText("Tabs", useUnmergedTree = true).performClick()
        // The address field behind the sheet may contain the same URL. Count only
        // tab rows so duplicate URLs still select the intended tab by model order.
        compose.onAllNodes(hasText(url) and hasAnyAncestor(hasTestTag("mylo-panel-tabs")),
            useUnmergedTree = true)[index].performClick()
        // Tab selection changes Compose state before replacing the native WebView.
        // Drain that change before raw Android-view polling can observe the old tab.
        compose.waitForIdle()
        compose.onNodeWithText("Your tabs").assertDoesNotExist()
        compose.onNodeWithContentDescription("Browser address").assertIsDisplayed()
    }

    private fun captureFailure(name: String) {
        runCatching { device.takeScreenshot(File(artifacts, name)) }
    }

    private fun store(): BrowserStore = ViewModelProvider(compose.activity)[BrowserSession::class.java].store

    private fun capture(name: String) {
        compose.waitForIdle()
        assertTrue("Could not save device screenshot $name", device.takeScreenshot(File(artifacts, name)))
        // Leave each milestone visible long enough to follow in the device recording.
        SystemClock.sleep(800)
    }

    private fun currentWebView(): WebView? {
        var result: WebView? = null
        instrumentation.runOnMainSync { result = findWebView(compose.activity.window.decorView) }
        return result
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun waitForRealGoogleResults(): JSONObject = waitForRealProviderResults(SearchProvider.GOOGLE, 45_000)

    private fun waitForRealProviderResults(provider: SearchProvider, timeout: Long): JSONObject {
        val expectedHost = when (provider) {
            SearchProvider.GOOGLE -> "google.com"
            SearchProvider.BING -> "bing.com"
            SearchProvider.DUCKDUCKGO -> "duckduckgo.com"
            SearchProvider.BRAVE -> "search.brave.com"
            SearchProvider.YAHOO -> "search.yahoo.com"
            SearchProvider.STARTPAGE -> "startpage.com"
        }
        val queryParameters = when (provider) {
            SearchProvider.YAHOO -> listOf("p")
            SearchProvider.STARTPAGE -> listOf("query", "q")
            else -> listOf("q")
        }
        val deadline = SystemClock.elapsedRealtime() + timeout
        var latest = JSONObject()
        while (SystemClock.elapsedRealtime() < deadline) {
            val webView = currentWebView()
            if (webView != null) {
                readPage(webView)?.let { latest = it }
                val url = latest.optString("url")
                val host = Uri.parse(url).host.orEmpty()
                val body = latest.optString("body").lowercase()
                val blocked = host.startsWith("consent.") || url.contains("/sorry/") || url.contains("/captcha") ||
                    listOf("before you continue to google", "our systems have detected unusual traffic",
                        "verify you're not a robot", "webpage not available", "err_internet_disconnected",
                        "err_name_not_resolved", "err_tunnel_connection_failed", "unfortunately, bots use duckduckgo too",
                        "confirm you're a human", "verify you are human", "access to this page has been denied").any(body::contains)
                assertFalse("Real ${provider.displayName} results unavailable: provider consent, CAPTCHA, or network error. " +
                    "URL=$url; title=${latest.optString("title")}", blocked)
                val address = Uri.parse(url)
                // A new WebView briefly reports about:blank, an opaque URI.
                val queryMatches = address.isHierarchical && queryParameters.any {
                    address.getQueryParameter(it)?.equals(QUERY, true) == true
                }
                if ((host == expectedHost || host.endsWith(".$expectedHost")) && queryMatches &&
                    latest.optString("ready") == "complete" &&
                    ((latest.optInt("resultHeadings") > 0 && latest.optInt("externalLinks") > 0) ||
                        latest.optInt("resultLinks") > 0) &&
                    body.contains("florida")) {
                    assertTrue("Expected Android's real mobile WebView user agent", latest.optString("userAgent").contains("Android"))
                    assertTrue("Search provider must receive a mobile user agent", latest.optString("userAgent").contains("Mobile"))
                    return latest.apply { remove("body") }
                }
            }
            SystemClock.sleep(300)
        }
        throw AssertionError("Real ${provider.displayName} results did not load within ${timeout / 1000} seconds; no fake results used. " +
            "URL=${latest.optString("url")}; title=${latest.optString("title")}; " +
            "ready=${latest.optString("ready")}; result headings=${latest.optInt("resultHeadings")}")
    }

    private fun waitForExampleDomain(expectedHost: String): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var latest = JSONObject()
        while (SystemClock.elapsedRealtime() < deadline) {
            currentWebView()?.let { readPage(it)?.let { page -> latest = page } }
            if (Uri.parse(latest.optString("url")).host == expectedHost &&
                latest.optString("ready") == "complete" &&
                latest.optString("title") == "Example Domain" &&
                latest.optString("body").contains("This domain is for use in")) {
                return latest.apply { remove("body") }
            }
            SystemClock.sleep(250)
        }
        throw AssertionError("Real https://$expectedHost did not load within 20 seconds; " +
            "URL=${latest.optString("url")}; title=${latest.optString("title")}")
    }

    private fun clickRealResultLink() {
        val view = currentWebView() ?: throw AssertionError("No WebView for Google's results")
        val completed = CountDownLatch(1)
        val activated = AtomicReference<JSONObject?>()
        instrumentation.runOnMainSync {
            view.evaluateJavascript("""
                (() => {
                  const anchors = Array.from(document.querySelectorAll('a[href]'));
                  const external = anchors.filter(a => {
                    if (a.closest('header, footer, nav, [role="navigation"]')) return false;
                    try {
                      const u = new URL(a.href);
                      const target = u.hostname.endsWith('google.com') && u.pathname === '/url'
                        ? new URL(u.searchParams.get('q') || u.searchParams.get('url')) : u;
                      return /^https?:${'$'}/.test(target.protocol) &&
                        !/(^|\.)(google\.[a-z.]+|bing\.com|duckduckgo\.com|brave\.com|yahoo\.com|startpage\.com|gstatic\.com|googleusercontent\.com)${'$'}/.test(target.hostname);
                    } catch (_) { return false; }
                  });
                  // Google mobile headings can be spans with role=heading or contain
                  // the anchor, and AI overview citations can be descriptive links.
                  // Activate only a link already supplied by the provider document.
                  const result = external.find(a => a.querySelector('h2, h3, [role="heading"]') ||
                    a.closest('h2, h3, [role="heading"]')) ||
                    external.find(a => /florida|beach/i.test(a.textContent || a.getAttribute('aria-label') || ''));
                  if (!result) return null;
                  const evidence = { href: result.href, label: (result.textContent || result.getAttribute('aria-label') || '').trim().slice(0, 300) };
                  result.click();
                  return JSON.stringify(evidence);
                })()
            """.trimIndent()) { value ->
                activated.set(runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull())
                completed.countDown()
            }
        }
        assertTrue("Provider page did not respond to result activation", completed.await(3, TimeUnit.SECONDS))
        assertTrue("No real external result link found in the provider document", activated.get() != null)
        File(artifacts, "activated-provider-link.json").writeText(activated.get()!!.toString(2))
    }

    private fun waitForExternalPage(): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var latest = JSONObject()
        while (SystemClock.elapsedRealtime() < deadline) {
            currentWebView()?.let { readPage(it)?.let { page -> latest = page } }
            val url = Uri.parse(latest.optString("url"))
            val host = url.host.orEmpty()
            val providerHost = listOf("google.com", "bing.com", "duckduckgo.com", "brave.com", "yahoo.com", "startpage.com")
                .any { host == it || host.endsWith(".$it") }
            if (url.scheme in listOf("https", "http") && host.isNotEmpty() && !providerHost &&
                latest.optString("ready") == "complete" && latest.optString("body").isNotBlank() &&
                !latest.optString("body").contains("Webpage not available", ignoreCase = true)) {
                return latest.apply { remove("body") }
            }
            SystemClock.sleep(250)
        }
        throw AssertionError("The real result website did not load within 30 seconds; " +
            "URL=${latest.optString("url")}; title=${latest.optString("title")}")
    }

    private fun readPage(webView: WebView): JSONObject? {
        val completed = CountDownLatch(1)
        val page = AtomicReference<JSONObject?>()
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("""
                (() => JSON.stringify({
                  url: location.href,
                  ready: document.readyState,
                  title: document.title,
                  userAgent: navigator.userAgent,
                  mobileViewport: window.innerWidth,
                  body: (document.body?.innerText || '').slice(0, 20000),
                  resultHeadings: document.querySelectorAll('h2, h3').length,
                  resultLinks: document.querySelectorAll('.result__a, .result__title a, .snippet-title, [data-testid="result-title-a"], #b_results h2 a, article h2 a, #web h3 a, .compTitle h3 a, .algo h3 a').length,
                  externalLinks: Array.from(document.querySelectorAll('a[href]')).filter(a => {
                    try {
                      const u = new URL(a.href);
                      return /^https?:$/.test(u.protocol) &&
                        u.hostname !== location.hostname &&
                        !/(^|\.)(google\.[a-z.]+|bing\.com|duckduckgo\.com|brave\.com|yahoo\.com|startpage\.com)${'$'}/.test(u.hostname) &&
                        !/(^|\.)(gstatic|googleusercontent)\.com${'$'}/.test(u.hostname);
                    } catch (_) { return false; }
                  }).length
                }))()
            """.trimIndent()) { value ->
                page.set(runCatching { JSONObject(JSONTokener(value).nextValue() as String) }.getOrNull())
                completed.countDown()
            }
        }
        completed.await(2, TimeUnit.SECONDS)
        return page.get()
    }

    companion object {
        private const val SEARCH_FIELD = "Search or enter address"
        private const val SEARCH_MODE = "search-input-mode"
        private const val SEARCH_INPUT = "home-search-input"
        private const val PROVIDER_PICKER = "search-provider-picker"
        private const val QUERY = "best beaches in Florida"
    }
}

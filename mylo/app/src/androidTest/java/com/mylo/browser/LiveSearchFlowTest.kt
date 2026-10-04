package com.mylo.browser

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
    fun searchEngineSettingsPersistEverySupportedProvider() {
        compose.onNodeWithContentDescription("Settings").performClick()
        SearchProvider.entries.forEach { provider ->
            compose.onNodeWithText(provider.displayName).performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(provider, store().provider)
                // A fresh model reads SharedPreferences rather than Compose state.
                assertEquals(provider, BrowserStore(compose.activity.application).provider)
            }
        }
        device.pressBack()
        compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(SearchProvider.entries.last(), BrowserStore(compose.activity.application).provider)
        }
    }

    @Test
    fun googleSearchLoadsRealResultsAndBackReturnsHome() {
        val evidence = File(artifacts, "live-search-evidence.json")
        // A previous successful capture must never mask this run's failure.
        File(artifacts, "03-real-google-results.png").delete()
        File(artifacts, "04-back-to-home.png").delete()
        evidence.writeText(JSONObject().put("verified", false).put("status", "Started").toString(2))
        try {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Google").performScrollTo().performClick()
            device.pressBack()
            compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
            capture("01-home.png")

            compose.onNodeWithContentDescription(SEARCH_FIELD).performClick()
            compose.waitUntil(5_000) {
                var keyboardVisible = false
                instrumentation.runOnMainSync {
                    keyboardVisible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                keyboardVisible
            }
            compose.onNodeWithContentDescription(SEARCH_FIELD).performTextInput(QUERY)
            capture("02-query-and-keyboard.png")
            compose.onNodeWithContentDescription(SEARCH_FIELD).performImeAction()

            val result = waitForRealGoogleResults()
            var firstTabId = 0L
            compose.runOnIdle {
                assertEquals("First search must use exactly one tab", 1, store().tabs.size)
                firstTabId = store().tabs.single().id
                assertEquals(SearchProvider.GOOGLE, store().provider)
            }
            capture("03-real-google-results.png")

            // A single browser Back from the initial search must return Home.
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
            capture("04-back-to-home.png")

            // Submitting again from Home must reuse the current tab, not create one.
            compose.onNodeWithContentDescription(SEARCH_FIELD).performClick().performTextInput(QUERY)
            compose.onNodeWithContentDescription(SEARCH_FIELD).performImeAction()
            compose.waitUntil(5_000) { currentWebView() != null }
            compose.runOnIdle {
                assertEquals(1, store().tabs.size)
                assertEquals(firstTabId, store().tabs.single().id)
            }
            evidence.writeText(result.put("verified", true)
                .put("flow", "Home → keyboard → query → real Google webpage → Back → Home")
                .put("currentTabReused", true).toString(2))
        } catch (failure: Throwable) {
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
                .put("verified", false)
            File(artifacts, "$filename.png").delete()
            try {
                // Recover to native Home after a denied provider, then select via actual UI.
                compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText(provider.displayName).performScrollTo().performClick()
                device.pressBack()
                compose.onNodeWithContentDescription(SEARCH_FIELD).performClick().performTextInput(QUERY)
                compose.onNodeWithContentDescription(SEARCH_FIELD).performImeAction()
                compose.waitUntil(5_000) { currentWebView() != null }
                compose.runOnIdle {
                    assertEquals(provider, store().provider)
                    assertEquals(provider, BrowserStore(compose.activity.application).provider)
                    assertEquals("Changing search engines must reuse the current tab", 1, store().tabs.size)
                    val id = store().tabs.single().id
                    if (currentTabId == null) currentTabId = id else assertEquals(currentTabId, id)
                }
                val page = waitForRealProviderResults(provider, 30_000)
                capture("$filename.png")
                evidence.put("verified", true).put("document", page)
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
                evidence.put("backReturnedHome", true)
            } catch (failure: Throwable) {
                val reason = failure.message ?: failure.javaClass.simpleName
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
            compose.onNodeWithContentDescription(SEARCH_FIELD).performClick()
                .performTextInput("https://example.com")
            compose.onNodeWithContentDescription(SEARCH_FIELD).performImeAction()
            val first = waitForExampleDomain("example.com")
            var tabId = 0L
            compose.runOnIdle {
                assertEquals(1, store().tabs.size)
                tabId = store().tabs.single().id
            }
            evidence.put("directUrl", first)
            capture("navigation-01-direct-url.png")

            compose.onNodeWithContentDescription("Browser address").performClick()
                .performTextReplacement("https://example.org")
            compose.onNodeWithContentDescription("Browser address").performImeAction()
            evidence.put("secondUrl", waitForExampleDomain("example.org"))
            compose.runOnIdle { assertEquals(tabId, store().tabs.single().id) }

            compose.onNodeWithContentDescription("Back").performClick()
            evidence.put("back", waitForExampleDomain("example.com"))
            compose.onNodeWithContentDescription("Forward").assertIsEnabled().performClick()
            evidence.put("forward", waitForExampleDomain("example.org"))
            capture("navigation-02-forward.png")

            compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed().performClick()
                .performTextInput("example.com")
            compose.onNodeWithContentDescription(SEARCH_FIELD).performImeAction()
            evidence.put("bareAddressFromHome", waitForExampleDomain("example.com"))
            compose.runOnIdle {
                assertEquals("Home URL submission must keep the current tab", 1, store().tabs.size)
                assertEquals(tabId, store().tabs.single().id)
            }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
            evidence.put("backReturnedHome", true).put("currentTabReused", true).put("verified", true)
        } catch (failure: Throwable) {
            evidence.put("failure", failure.message ?: failure.javaClass.simpleName)
            throw failure
        } finally {
            report.writeText(evidence.toString(2))
        }
    }

    private fun store(): BrowserStore = ViewModelProvider(compose.activity)[BrowserSession::class.java].store

    private fun capture(name: String) {
        compose.waitForIdle()
        assertTrue("Could not save device screenshot $name", device.takeScreenshot(File(artifacts, name)))
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
            SearchProvider.STARTPAGE -> "startpage.com"
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
                val queryMatches = listOf("q", "query").any { Uri.parse(url).getQueryParameter(it)?.equals(QUERY, true) == true }
                if ((host == expectedHost || host.endsWith(".$expectedHost")) && queryMatches &&
                    latest.optString("ready") == "complete" &&
                    ((latest.optInt("resultHeadings") > 0 && latest.optInt("externalLinks") > 0) ||
                        latest.optInt("resultLinks") > 0) &&
                    body.contains("florida")) {
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

    private fun readPage(webView: WebView): JSONObject? {
        val completed = CountDownLatch(1)
        val page = AtomicReference<JSONObject?>()
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("""
                (() => JSON.stringify({
                  url: location.href,
                  ready: document.readyState,
                  title: document.title,
                  body: (document.body?.innerText || '').slice(0, 20000),
                  resultHeadings: document.querySelectorAll('h2, h3').length,
                  resultLinks: document.querySelectorAll('.result__a, .result__title a, .snippet-title, [data-testid="result-title-a"], #b_results h2 a, article h2 a').length,
                  externalLinks: Array.from(document.querySelectorAll('a[href]')).filter(a => {
                    try {
                      const u = new URL(a.href);
                      return /^https?:$/.test(u.protocol) &&
                        u.hostname !== location.hostname &&
                        !/(^|\.)(google\.[a-z.]+|bing\.com|duckduckgo\.com|brave\.com|startpage\.com)${'$'}/.test(u.hostname) &&
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
        private const val QUERY = "best beaches in Florida"
    }
}

package com.mylo.browser

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The exact user flow, captured on a real device: Home → tap search → type a query → choose a
 * provider → keyboard Search → that provider's real mobile results page in Mylo's WebView.
 * Screenshots and evidence go to test-artifacts/provider-flow/<case>/.
 */
@RunWith(AndroidJUnit4::class)
class ProviderFlowPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun braveJustThisSearch() = flow("brave-just-this-search", SearchProvider.BRAVE, setDefault = false)

    @Test fun googleDefault() = flow("google-default", SearchProvider.GOOGLE, setDefault = null)

    @Test fun yahooSetAsDefault() = flow("yahoo-set-as-default", SearchProvider.YAHOO, setDefault = true)

    /** Bing is checked by URL only: the page Mylo opens must be Bing's, never Google's or another's. */
    @Test fun bingOpensBingUrl() = flow("bing-url-check", SearchProvider.BING, setDefault = false, waitForFullPage = false)

    /** [setDefault]: false = "Just this search", true = "Set as default", null = keep the default. */
    private fun flow(case: String, provider: SearchProvider, setDefault: Boolean?, waitForFullPage: Boolean = true) {
        val artifacts = File(instrumentation.targetContext.getExternalFilesDir(null), "test-artifacts/provider-flow/$case")
            .apply { deleteRecursively(); mkdirs() }
        val evidence = JSONObject().put("case", case).put("provider", provider.displayName).put("query", QUERY)
        fun screenshot(name: String) = assertTrue("Could not save screenshot $name", device.takeScreenshot(File(artifacts, name)))
        fun capture(name: String) { compose.waitForIdle(); SystemClock.sleep(600); screenshot(name) }
        try {
            compose.runOnIdle { store().setProvider(SearchProvider.GOOGLE) }

            // 1. Resting Home: no provider controls.
            compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
            capture("01-home.png")

            // 2. Tap the search box: focused field and the Android keyboard.
            compose.onNodeWithContentDescription(SEARCH_FIELD).performClick()
            compose.onNodeWithTag(SEARCH_INPUT).assertIsDisplayed().assertIsFocused()
            waitForKeyboard()
            compose.onNodeWithContentDescription("Search provider: Google").assertIsDisplayed()
            capture("02-focused-keyboard.png")

            // 3. Type the query.
            compose.onNodeWithTag(SEARCH_INPUT).performTextInput(QUERY)
            compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals(QUERY)
            capture("03-typed-query.png")

            // 4. Choose the provider before submitting.
            if (setDefault != null) {
                compose.onNodeWithContentDescription("Search provider: Google").performClick()
                compose.onNode(hasText(provider.displayName) and hasAnyAncestor(hasTestTag(PROVIDER_PICKER))).performClick()
                capture("04-provider-sheet.png")
                compose.onNode(hasText(if (setDefault) "Set as default" else "Just this search") and hasAnyAncestor(hasTestTag(PROVIDER_PICKER)))
                    .performClick()
                compose.onNodeWithTag(PROVIDER_PICKER).assertDoesNotExist()
            }
            compose.onNodeWithContentDescription("Search provider: ${provider.displayName}").assertIsDisplayed()
            compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals(QUERY).assertIsFocused()
            waitForKeyboard()
            capture("05-provider-chosen.png")

            // 5. The keyboard's Search action submits the exact typed text to the chosen provider.
            // From here on frames are advanced explicitly instead of waiting for Compose to be idle,
            // because a page's loading bar may keep animating.
            compose.onNodeWithTag(SEARCH_INPUT).performImeAction()
            val page = waitForProviderPage(provider, waitForFullPage)
            evidence.put("page", page)
            if (waitForFullPage) {
                pump(2_500)
                // Documented, not asserted: whether Mylo's loading bar is still shown after the page finished.
                evidence.put("loadingBarStillShownAfterLoad", device.hasObject(By.clazz("android.widget.ProgressBar")))
            }
            pump(300)
            screenshot("06-results.png")

            val expectedDefault = if (setDefault == true) provider else SearchProvider.GOOGLE
            val savedDefault = BrowserStore(instrumentation.targetContext).provider
            assertEquals("Saved default after the flow", expectedDefault, savedDefault)
            evidence.put("savedDefault", savedDefault.displayName).put("verified", true)
        } catch (failure: Throwable) {
            evidence.put("verified", false).put("failure", failure.message ?: failure.javaClass.simpleName)
            runCatching { device.takeScreenshot(File(artifacts, "failure.png")) }
            throw failure
        } finally {
            File(artifacts, "evidence.json").writeText(evidence.toString(2))
            BrowserStore(instrumentation.targetContext).setProvider(SearchProvider.GOOGLE)
        }
    }

    /**
     * Waits for the provider's own results URL for exactly [QUERY]. The page itself is the
     * provider's; it is recorded (including any consent or CAPTCHA page) and never simulated.
     */
    private fun waitForProviderPage(provider: SearchProvider, fullPage: Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var url = ""; var title = ""; var progress = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                findWebView(compose.activity.window.decorView)?.let { url = it.url.orEmpty(); title = it.title.orEmpty(); progress = it.progress }
            }
            val address = Uri.parse(url)
            val host = address.host.orEmpty()
            val query = if (address.isHierarchical) listOf("q", "query", "p").firstNotNullOfOrNull { address.getQueryParameter(it) } else null
            if (url.isNotEmpty() && host.isNotEmpty() && !url.startsWith("about:")) {
                // The very first provider URL must be the selected provider's own search URL.
                assertTrue("Selected ${provider.displayName} but Mylo opened $url", host == provider.domain || host.endsWith(".${provider.domain}") ||
                    SearchProvider.entries.none { host == it.domain || host.endsWith(".${it.domain}") })
            }
            if ((host == provider.domain || host.endsWith(".${provider.domain}")) && query == QUERY && (!fullPage || progress == 100)) {
                if (fullPage) pump(1_500) // let the page paint before the screenshot
                return JSONObject().put("url", url).put("title", title).put("host", host).put("query", query).put("progress", progress)
            }
            pump(300)
        }
        throw AssertionError("${provider.displayName} results for \"$QUERY\" did not load; last URL=$url title=$title progress=$progress")
    }

    /** Lets real time pass while advancing Compose frames, without waiting for an idle UI. */
    private fun pump(millis: Long) {
        var left = millis
        while (left > 0) { compose.mainClock.advanceTimeBy(50); SystemClock.sleep(50); left -= 50 }
    }

    private fun waitForKeyboard() {
        compose.waitUntil(5_000) {
            var visible = false
            instrumentation.runOnMainSync {
                visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
    }

    private fun store(): BrowserStore = ViewModelProvider(compose.activity)[BrowserSession::class.java].store

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    companion object {
        private const val QUERY = "best hotels in Miami"
        private const val SEARCH_FIELD = "Search or enter address"
        private const val SEARCH_MODE = "search-input-mode"
        private const val SEARCH_INPUT = "search-input"
        private const val PROVIDER_PICKER = "search-provider-picker"
    }
}

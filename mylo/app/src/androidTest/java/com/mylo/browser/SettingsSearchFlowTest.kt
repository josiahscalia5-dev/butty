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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The final search flow on a real device: Home's Settings gear → choose the provider → back on the
 * corgi Home, type into the same Home search box → keyboard Search → that provider's real results
 * page. Each case runs in its own app process, so [savedYahooAfterRelaunch] (run after
 * [yahooFromSettings]) sees only what Mylo saved. Screenshots and evidence go to
 * test-artifacts/provider-flow/<case>/.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSearchFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun googleFromSettings() = case("google-from-settings") { capture ->
        setSavedProvider(SearchProvider.DUCKDUCKGO)
        chooseInSettings(SearchProvider.GOOGLE, capture)
        put("page", waitForResults(SearchProvider.GOOGLE, searchFromHome(QUERY, capture)))
    }

    @Test fun yahooFromSettings() = case("yahoo-from-settings") { capture ->
        setSavedProvider(SearchProvider.GOOGLE)
        chooseInSettings(SearchProvider.YAHOO, capture)
        put("page", waitForResults(SearchProvider.YAHOO, searchFromHome(QUERY, capture)))
    }

    /** Run after [yahooFromSettings]: a new process, no Settings tap, Yahoo still answers. */
    @Test fun savedYahooAfterRelaunch() = case("yahoo-after-relaunch") { capture ->
        compose.runOnIdle {
            assertEquals("The provider saved in Settings before the relaunch", SearchProvider.YAHOO, store().provider)
        }
        // Settings shows the saved choice; it is only looked at, not changed.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag(PROVIDER_SETTINGS).assertIsDisplayed()
        capture("02-settings-still-yahoo.png")
        compose.onNodeWithContentDescription("Close Search engine").performClick()
        compose.onNodeWithTag(PROVIDER_SETTINGS).assertDoesNotExist()
        put("page", waitForResults(SearchProvider.YAHOO, searchFromHome(QUERY, capture)))
    }

    /** A web address typed into the Home box opens directly, never as a provider search. */
    @Test fun domainOpensDirectly() = case("domain-opens-directly") { capture ->
        val submitted = searchFromHome(DOMAIN, capture)
        assertEquals("The Home box must open the typed address itself", "https://facebook.com", submitted)
        put("submittedUrl", submitted)
        put("page", waitForPage(submitted) { host, _ -> host == "facebook.com" || host.endsWith(".facebook.com") })
    }

    private fun case(name: String, body: JSONObject.((String) -> Unit) -> Unit) {
        val artifacts = File(instrumentation.targetContext.getExternalFilesDir(null), "test-artifacts/provider-flow/$name")
            .apply { deleteRecursively(); mkdirs() }
        val evidence = JSONObject().put("case", name)
        val capture: (String) -> Unit = { file ->
            pump(600)
            assertTrue("Could not save screenshot $file", device.takeScreenshot(File(artifacts, file)))
        }
        try {
            // Resting corgi Home: no provider control anywhere on it.
            compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
            assertNoSearchPageControls()
            capture("01-home.png")
            evidence.body(capture)
            pump(1_500) // let the opened page paint before its screenshot
            capture("04-opened-page.png")
            evidence.put("savedProvider", BrowserStore(instrumentation.targetContext).provider.displayName)
                .put("verified", true)
        } catch (failure: Throwable) {
            evidence.put("verified", false).put("failure", failure.message ?: failure.javaClass.simpleName)
            runCatching { device.takeScreenshot(File(artifacts, "failure.png")) }
            throw failure
        } finally {
            File(artifacts, "evidence.json").writeText(evidence.toString(2))
        }
    }

    /** Settings gear → Search engine → [provider]; the choice is saved before the sheet closes. */
    private fun JSONObject.chooseInSettings(provider: SearchProvider, capture: (String) -> Unit) {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag(PROVIDER_SETTINGS).assertIsDisplayed()
        SearchProvider.entries.forEach {
            compose.onNode(hasText(it.displayName) and hasAnyAncestor(hasTestTag(PROVIDER_SETTINGS))).assertIsDisplayed()
        }
        compose.onNode(hasText(provider.displayName) and hasAnyAncestor(hasTestTag(PROVIDER_SETTINGS))).performClick()
        compose.runOnIdle { assertEquals(provider, store().provider) }
        // A fresh model reads the device's saved preferences, not the live Compose state.
        assertEquals(provider, BrowserStore(instrumentation.targetContext).provider)
        capture("02-settings-${provider.name.lowercase()}.png")
        compose.onNodeWithContentDescription("Close Search engine").performClick()
        compose.onNodeWithTag(PROVIDER_SETTINGS).assertDoesNotExist()
        compose.runOnIdle { assertTrue("Choosing a provider must not navigate", store().tabs.isEmpty()) }
        put("chosenInSettings", provider.displayName)
    }

    /**
     * Back on Home: tap the existing search box, type [text], press the keyboard's Search action.
     * Returns the URL Mylo submitted. After the action frames are advanced explicitly instead of
     * waiting for Compose to be idle, because a page's loading bar may keep animating.
     */
    private fun JSONObject.searchFromHome(text: String, capture: (String) -> Unit): String {
        compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed().performClick()
        compose.onNodeWithTag(SEARCH_INPUT).assertIsFocused()
        waitForKeyboard()
        compose.onNodeWithTag(SEARCH_INPUT).performTextInput(text)
        compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals(text).assertIsFocused()
        // Typing happens in Home's own box: Home stays on screen and no search page or picker opens.
        compose.onNodeWithTag(HOME_SEARCH).assertIsDisplayed()
        assertNoSearchPageControls()
        capture("03-typed-in-home-box.png")
        compose.onNodeWithTag(SEARCH_INPUT).performImeAction()
        var submitted = ""
        instrumentation.runOnMainSync { submitted = store().tabs.singleOrNull()?.url.orEmpty() }
        put("typed", text).put("submittedUrl", submitted)
        return submitted
    }

    /** The provider's own results URL for exactly the typed query, fully loaded. Never simulated. */
    private fun waitForResults(provider: SearchProvider, submitted: String): JSONObject {
        assertEquals("Home search must use the provider saved in Settings", provider.searchUrl(QUERY), submitted)
        return waitForPage(submitted) { host, address ->
            // The very first provider page must be the saved provider's, never another one's.
            val provides = { p: SearchProvider -> host == p.domain || host.endsWith(".${p.domain}") }
            assertTrue("Saved ${provider.displayName} but Mylo opened $address", provides(provider) || SearchProvider.entries.none(provides))
            provides(provider) && address.isHierarchical &&
                listOf("q", "query", "p").any { address.getQueryParameter(it) == QUERY }
        }
    }

    private fun waitForPage(submitted: String, matches: (String, Uri) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var url = ""; var title = ""; var progress = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                findWebView(compose.activity.window.decorView)?.let { url = it.url.orEmpty(); title = it.title.orEmpty(); progress = it.progress }
            }
            val address = Uri.parse(url)
            val host = address.host.orEmpty()
            if (url.isNotEmpty() && !url.startsWith("about:") && host.isNotEmpty() && matches(host, address) && progress == 100) {
                return JSONObject().put("url", url).put("title", title).put("host", host).put("progress", progress)
            }
            pump(300)
        }
        throw AssertionError("The page for $submitted did not load; last URL=$url title=$title progress=$progress")
    }

    private fun assertNoSearchPageControls() {
        compose.onNodeWithTag("search-input-mode").assertDoesNotExist()
        compose.onNodeWithText("Search with", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Just this search").assertDoesNotExist()
    }

    private fun setSavedProvider(provider: SearchProvider) = compose.runOnIdle { store().setProvider(provider) }

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
        private const val QUERY = "Facebook"
        private const val DOMAIN = "facebook.com"
        private const val SEARCH_FIELD = "Search or enter address"
        private const val SEARCH_INPUT = "search-input"
        private const val HOME_SEARCH = "home-search"
        private const val PROVIDER_SETTINGS = "search-provider-settings"
    }
}

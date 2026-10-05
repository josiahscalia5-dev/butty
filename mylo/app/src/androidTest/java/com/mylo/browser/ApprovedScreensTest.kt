package com.mylo.browser

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.mylo.browser.ai.AiPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The approved screens on a real device, from production code:
 *  - [VoiceEntry.myloButtonOpensVoiceMode]: launch → Home → bottom Mylo → Voice Mode → Close voice mode → Home → Mylo again,
 *    with no Mylo AI service configured (the screen still opens and says plainly that answers need setup).
 *  - [homeScreen] and [googleResults]: portrait captures of Home and of a real Google results page inside Mylo's browser
 *    chrome, for side-by-side comparison with design/reference/home-reference.jpg and browser-reference.jpg.
 * Pass -e screenCase <name> to label the captures with the display size the CI script set. Screenshots and evidence go
 * to test-artifacts/screens/<case>/.
 */
@RunWith(AndroidJUnit4::class)
class ApprovedScreensTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val screenCase get() = InstrumentationRegistry.getArguments().getString("screenCase", "device").replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun artifacts(name: String) = File(instrumentation.targetContext.getExternalFilesDir(null), "test-artifacts/screens/$name").apply { mkdirs() }

    /** Home exactly as it opens, at whatever display size the script set. */
    @Test fun homeScreen() {
        val dir = artifacts("home")
        compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed()
        compose.onNodeWithTag("home-bottom-nav").assertIsDisplayed()
        compose.onNodeWithTag("home-shield").assertIsDisplayed()
        pump(1_200)
        val metrics = compose.activity.resources.displayMetrics
        assertTrue(device.takeScreenshot(File(dir, "home-$screenCase.png")))
        File(dir, "home-$screenCase.json").writeText(JSONObject().put("widthPx", device.displayWidth).put("heightPx", device.displayHeight)
            .put("density", metrics.density).put("widthDp", device.displayWidth / metrics.density).put("heightDp", device.displayHeight / metrics.density)
            .put("shieldStatus", "real Mylo Shield state").toString(2))
    }

    /** Settings provider Google, "Facebook" typed into Home's box, the real Google results page in Mylo's chrome. */
    @Test fun googleResults() {
        val dir = artifacts("browser")
        compose.runOnIdle { store().setProvider(SearchProvider.GOOGLE) }
        compose.onNodeWithContentDescription(SEARCH_FIELD).assertIsDisplayed().performClick()
        compose.onNodeWithTag("search-input").assertIsFocused().performTextInput("Facebook")
        compose.onNodeWithTag("search-input").performImeAction()
        val page = waitForPage { host -> host == "google.com" || host.endsWith(".google.com") }
        pump(2_500) // let the provider's page paint
        // Resting: the clean host only, with the browser's controls and Search selected in the navigation.
        compose.onNodeWithTag("browser-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("home-bottom-nav").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("More page options").assertIsDisplayed()
        assertTrue(device.takeScreenshot(File(dir, "browser-$screenCase.png")))
        // Tapped: the full address, editable, still inside the pill.
        compose.onNodeWithTag("browser-address").performClick()
        pump(900)
        assertTrue(device.takeScreenshot(File(dir, "browser-editing-$screenCase.png")))
        File(dir, "browser-$screenCase.json").writeText(page.put("provider", "Google (chosen in Settings)").toString(2))
    }

    private fun waitForPage(matches: (String) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var url = ""; var title = ""; var progress = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                findWebView(compose.activity.window.decorView)?.let { url = it.url.orEmpty(); title = it.title.orEmpty(); progress = it.progress }
            }
            val host = android.net.Uri.parse(url).host.orEmpty()
            if (host.isNotEmpty() && matches(host) && progress == 100) return JSONObject().put("url", url).put("title", title).put("host", host)
            pump(300)
        }
        throw AssertionError("The Google results page did not load; last URL=$url title=$title progress=$progress")
    }

    private fun pump(millis: Long) {
        var left = millis
        while (left > 0) { compose.mainClock.advanceTimeBy(50); SystemClock.sleep(50); left -= 50 }
    }

    private fun store(): BrowserStore = ViewModelProvider(compose.activity)[BrowserSession::class.java].store

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    companion object {
        const val SEARCH_FIELD = "Search or enter address"
    }
}

/**
 * The exact Voice Mode check: launch Mylo → Home → tap the bottom navigation's Mylo → the approved Voice Mode screen →
 * Close voice mode → Home → Mylo again → Voice Mode again. Uses UiAutomator taps, as a person would, in a build without
 * a Mylo AI service. Evidence in test-artifacts/screens/voice-entry/.
 */
@RunWith(AndroidJUnit4::class)
class VoiceEntry {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun myloButtonOpensVoiceMode() {
        AiPreferences(context).setTestService(null)
        val dir = File(context.getExternalFilesDir(null), "test-artifacts/screens/voice-entry").apply { deleteRecursively(); mkdirs() }
        val steps = JSONArray()
        val evidence = JSONObject().put("verified", false).put("aiServiceConfigured", BuildConfig.MYLO_AI_API_BASE_URL.isNotBlank())
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor(By.desc(ApprovedScreensTest.SEARCH_FIELD), "Home")
                shot(dir, "01-home.png"); steps.put("launched: Home")
                repeat(2) { round ->
                    val n = round + 1
                    dismissOtherAppsNotResponding()
                    val mylo = bottomMylo()
                    mylo.click(); steps.put("tapped the bottom navigation's Mylo (time $n)")
                    waitFor(By.res("voice-mode-screen"), "the Voice Mode screen (time $n)")
                    // The approved screen's own content, not a panel or Voice Search.
                    listOf("Voice Mode", "Your AI browsing buddy", "Tap to talk with Mylo", "Explain this page", "Find the pricing section",
                        "Help me cancel", "Is this site safe?", "Translate this page", "What Mylo can see", "Current Page", "Other Tabs",
                        "History", "Location", "Press and hold to talk").forEach { waitFor(By.text(it), "\"$it\"") }
                    waitFor(By.res("voice-type-instead"), "Type instead")
                    waitFor(By.textStartsWith("Compare this with"), "Compare tabs")
                    waitFor(By.res("voice-close"), "Close voice mode")
                    waitFor(By.res("voice-nav-mylo"), "Mylo selected in the centre of Voice Mode's navigation")
                    if (BuildConfig.MYLO_AI_API_BASE_URL.isBlank()) {
                        evidence.put("notConnectedStatus", waitFor(By.text(NOT_CONNECTED_CAPTION), "the truthful not-connected status").text)
                    }
                    shot(dir, "0${2 * n}-voice-mode-$n.png"); steps.put("Voice Mode screen open (time $n)")
                    dismissOtherAppsNotResponding()
                    waitFor(By.res("voice-close"), "Close voice mode").click()
                    device.wait(Until.gone(By.res("voice-mode-screen")), 5_000)
                    assertTrue("Voice Mode closed", !device.hasObject(By.res("voice-mode-screen")))
                    waitFor(By.desc(ApprovedScreensTest.SEARCH_FIELD), "Home after Close voice mode (time $n)")
                    shot(dir, "0${2 * n + 1}-home-after-close-$n.png"); steps.put("Close voice mode returned to Home (time $n)")
                }
            }
            evidence.put("verified", true)
        } catch (failure: Throwable) {
            evidence.put("failure", failure.message ?: failure.javaClass.simpleName)
            runCatching { device.takeScreenshot(File(dir, "failure.png")) }
            throw failure
        } finally {
            File(dir, "evidence.json").writeText(evidence.put("steps", steps).put("otherAppDialogsAnsweredWait", JSONArray(dismissed)).toString(2))
        }
    }

    /** The "Mylo" label in Home's bottom navigation (the lowest one on screen: never the search bar's microphone). */
    private fun bottomMylo(): UiObject2 {
        waitFor(By.text("Mylo"), "the bottom navigation's Mylo")
        val candidates = device.findObjects(By.text("Mylo"))
        val lowest = candidates.maxByOrNull { it.visibleBounds.centerY() } ?: throw AssertionError("No Mylo button")
        assertTrue("The Mylo button must be in the bottom navigation", lowest.visibleBounds.centerY() > device.displayHeight * .8)
        assertEquals("The Mylo button must not be the search bar's Voice search", null, lowest.contentDescription?.takeIf { it == "Voice search" })
        return lowest
    }

    /** Waits for [selector], answering another app's "isn't responding" dialog (the emulator's launcher) on the way. */
    private fun waitFor(selector: BySelector, what: String, timeout: Long = 20_000): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (true) {
            device.findObject(selector)?.let { return it }
            dismissOtherAppsNotResponding()
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Not on screen: $what")
            SystemClock.sleep(250)
        }
    }

    /**
     * A busy CI emulator sometimes shows "Pixel Launcher isn't responding" over Mylo. That dialog belongs to another
     * app, so it is answered with Wait (and noted in the evidence); a dialog about Mylo itself is never dismissed, so
     * a real Mylo freeze still fails the test.
     */
    private fun dismissOtherAppsNotResponding() {
        val title = device.findObject(By.textEndsWith("isn't responding")) ?: device.findObject(By.textEndsWith("isn’t responding")) ?: return
        val text = runCatching { title.text }.getOrNull().orEmpty()
        if (text.contains("Mylo", ignoreCase = true)) return
        device.findObject(By.text("Wait"))?.click()
        dismissed += text
        SystemClock.sleep(500)
    }

    private val dismissed = mutableListOf<String>()

    private fun shot(dir: File, name: String) {
        device.waitForIdle(800)
        SystemClock.sleep(600)
        assertTrue("Screenshot $name", device.takeScreenshot(File(dir, name)))
    }
}

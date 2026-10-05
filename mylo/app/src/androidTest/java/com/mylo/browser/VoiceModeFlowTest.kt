package com.mylo.browser

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.mylo.browser.ai.AiPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Voice Mode on a real device: the approved screen from Home's Mylo button, the typed chat (Type instead),
 * the switchboard deciding what leaves the phone, and the Privacy Receipt. The Mylo AI service in CI is the
 * reference gateway in front of a TEST upstream (not an AI) whose replies report what the request carried.
 */
@RunWith(AndroidJUnit4::class)
class VoiceModeFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Before fun setUp() {
        AiPreferences(context).setTestService(null)
        context.getSharedPreferences("mylo_ai_access", 0).edit().clear().commit()
    }

    private fun artifacts(case: String) = File(context.getExternalFilesDir(null), "test-artifacts/voice/$case").apply { deleteRecursively(); mkdirs() }

    /** Without a Mylo AI service: the screen works, talking says it isn't available, and typing sends nothing. */
    @Test fun voiceModeWithoutAServiceSendsNothing() {
        assumeTrue("This case covers builds without a Mylo AI service", BuildConfig.MYLO_AI_API_BASE_URL.isBlank())
        val dir = artifacts("no-service")
        val evidence = JSONObject().put("verified", false)
        val steps = JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { recording(dir) {
                waitFor(By.desc("Search or enter address"), "Home")
                waitFor(By.text("Mylo"), "Home's Mylo button").click()
                waitFor(By.res("voice-mode-screen"), "the Voice Mode screen")
                listOf("Voice Mode", "Your AI browsing buddy", "Tap to talk with Mylo", "Explain this page", "Find the pricing section", "Help me cancel",
                    "Is this site safe?", "Translate this page", "What Mylo can see", "Adjust", "Current Page", "Other Tabs", "History", "Location",
                    "Press and hold to talk").forEach { waitFor(By.text(it), "\"$it\"") }
                waitFor(By.textStartsWith("Compare this with"), "Compare tabs")
                shot(dir, "01-voice-mode.png"); steps.put("voice mode screen")

                // The microphone asks Android first; then Mylo listens (live level, "Microphone on") or says why it can't.
                waitFor(By.res("voice-talk"), "the microphone").click()
                waitFor(By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button"), "Android's microphone prompt", 20_000).click()
                // Then Mylo listens with the microphone on, or says plainly why it can't (no recognizer or language
                // pack on this phone). Either way the microphone ends up off.
                val problems = com.mylo.browser.voice.ListenProblem.entries.map { it.message }
                val deadline = SystemClock.uptimeMillis() + 15_000
                var outcome: String? = null
                while (outcome == null && SystemClock.uptimeMillis() < deadline) {
                    if (device.hasObject(By.res("voice-mic-live"))) outcome = "listening"
                    else problems.firstOrNull { device.hasObject(By.text(it)) }?.let { outcome = it }
                    if (outcome == null) SystemClock.sleep(100)
                }
                assertTrue("Mylo neither listened nor explained why", outcome != null)
                evidence.put("microphoneOutcome", outcome)
                if (outcome == "listening") {
                    shot(dir, "02-listening.png"); steps.put("listening with the microphone on")
                    runCatching { device.findObject(By.res("voice-talk"))?.click() }
                } else {
                    shot(dir, "02-microphone-explained.png"); steps.put("microphone: $outcome")
                }
                device.wait(Until.gone(By.res("voice-mic-live")), 20_000)
                assertTrue("The microphone turned off", !device.hasObject(By.res("voice-mic-live")))
                steps.put("microphone off after the turn")
                evidence.put("speechRecognitionAvailable", android.speech.SpeechRecognizer.isRecognitionAvailable(context))

                waitFor(By.res("voice-type-instead"), "Type instead").click()
                waitFor(By.text("Mylo AI isn’t connected in this build"), "the not-connected status")
                type(By.res("voice-chat-input"), "the message box", "Hello Mylo")
                waitFor(By.res("voice-chat-send"), "Send").click()
                waitFor(By.textStartsWith("Mylo AI isn't connected in this build yet, so nothing was sent."), "the not-sent explanation")
                waitFor(By.text("Not sent"), "the not-sent label")
                shot(dir, "03-chat-not-connected.png"); steps.put("typed message not sent without a service")

                waitFor(By.res("voice-chat-close"), "close chat").click()
                waitFor(By.res("voice-access-adjust"), "Adjust").click()
                waitFor(By.res("voice-access"), "the switchboard")
                shot(dir, "04-what-mylo-can-see.png")
                // The sheet scrolls on smaller screens: every source has its own row.
                val list = waitFor(By.res("voice-access-list"), "the switchboard list")
                listOf("currentpage", "selectedtext", "screenshot", "othertabs", "history", "location", "mylomemory").forEach { source ->
                    val row = By.res("access-$source")
                    if (!device.hasObject(row)) list.scrollUntil(Direction.DOWN, Until.findObject(row))
                    waitFor(By.res("access-$source"), "the $source row")
                }
                shot(dir, "05-what-mylo-can-see-end.png"); steps.put("switchboard sheet: all seven sources")
                waitFor(By.res("voice-access-close"), "close switchboard").click()

                waitFor(By.res("voice-close"), "Close voice mode").click()
                waitFor(By.desc("Search or enter address"), "Home after closing Voice Mode")
                steps.put("closed voice mode")
            } }
            evidence.put("verified", true)
        } finally {
            File(dir, "evidence.json").writeText(evidence.put("steps", steps).put("otherAppDialogsDismissed", JSONArray(dismissed)).toString(2))
        }
    }

    /** With the Mylo AI service: an answer streams from it, sensitive details leave hidden, and the switchboard decides. */
    @Test fun typedChatThroughTheMyloAiService() {
        val url = arguments.getString(URL_ARG)
        assumeTrue("Pass -e $URL_ARG (and -e $TOKEN_ARG) to run against a Mylo AI test service", !url.isNullOrBlank())
        AiPreferences(context).setTestService(com.mylo.browser.ai.AiEndpoint(url!!, arguments.getString(TOKEN_ARG)))
        val dir = artifacts("service")
        val evidence = JSONObject().put("verified", false)
        val steps = JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { recording(dir) {
                type(By.desc("Search or enter address"), "Home search", PLANS_PAGE)
                waitFor(By.desc("Go"), "the Go button").click()
                waitFor(By.text("Mylo Plans"), "the test page", 20_000)
                steps.put("opened the plans page")

                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-mode-screen"), "the Voice Mode screen")
                waitFor(By.res("voice-action-explain"), "Explain this page").click()
                waitFor(By.textStartsWith("Mylo AI · connected to localhost"), "the connected status")
                waitFor(By.textContains("I can see the page “Mylo Plans”"), "the streamed answer about the page", 30_000)
                // The test upstream counts placeholders it received: the card number on the page left the phone hidden.
                val answer = waitFor(By.textContains("1 detail arrived hidden"), "the card number arriving hidden", 20_000)
                waitFor(By.textContains("Explain this page in simple words."), "the question echoed by the test upstream", 20_000)
                evidence.put("answer", answer.text)
                val receipt = waitFor(By.textStartsWith("Privacy receipt: Current Page shared · 1 hidden"), "the privacy receipt")
                evidence.put("receipt", receipt.text)
                shot(dir, "01-explain-answer.png"); steps.put("explained the page through the service")
                receipt.click()
                waitFor(By.text("• Other Tabs not shared"), "the receipt details")
                shot(dir, "02-privacy-receipt.png"); steps.put("privacy receipt")

                // Turning Current Page off means the next question carries no page at all.
                waitFor(By.res("voice-chat-close"), "close chat").click()
                waitFor(By.res("voice-access-adjust"), "Adjust").click()
                waitFor(By.res("access-currentpage-off"), "Current Page: Off").click()
                waitFor(By.res("voice-access-close"), "close switchboard").click()
                waitFor(CURRENT_PAGE_OFF, "the Current Page chip off")
                shot(dir, "03-current-page-off.png"); steps.put("current page off")
                waitFor(By.res("voice-type-instead"), "Type instead").click()
                type(By.res("voice-chat-input"), "the message box", "What is this page about")
                waitFor(By.res("voice-chat-send"), "Send").click()
                val withoutPage = waitFor(By.textContains("No page was shared with me"), "an answer without the page", 30_000)
                evidence.put("answerWithoutPage", withoutPage.text)
                waitFor(By.textStartsWith("Privacy receipt: Nothing from your browser was shared"), "the empty receipt")
                shot(dir, "04-answer-without-page.png"); steps.put("switchboard kept the page on the phone")

                // A page action that needs the page asks first; Allow once is used up by that question.
                waitFor(By.res("voice-chat-close"), "close chat").click()
                waitFor(By.res("voice-action-sitesafety"), "Is this site safe?").click()
                waitFor(By.res("voice-chat-ask"), "Mylo asking to read the page")
                shot(dir, "05-asks-before-reading.png"); steps.put("asked before reading the page")
                waitFor(By.res("voice-ask-allow"), "Allow once").click()
                waitFor(By.textContains("Check this page for red flags."), "the safety question answered with the page", 30_000)
                waitFor(By.textStartsWith("Privacy receipt: Current Page shared"), "the receipt for the one-time grant")
                waitFor(By.res("voice-chat-close"), "close chat").click()
                waitFor(CURRENT_PAGE_OFF, "Current Page back off after the one-time grant")
                steps.put("allow once used up")

                waitFor(By.res("voice-close"), "Close voice mode").click()
                waitFor(By.text("Mylo Plans"), "the page again after closing Voice Mode")
                shot(dir, "06-back-to-page.png"); steps.put("closed voice mode, back on the page")
            } }
            evidence.put("verified", true)
        } finally {
            File(dir, "evidence.json").writeText(evidence.put("steps", steps).put("otherAppDialogsDismissed", JSONArray(dismissed)).toString(2))
            AiPreferences(context).setTestService(null)
        }
    }

    /** Fills a field through its accessibility "set text" action, falling back to Android's key events. */
    private fun type(selector: BySelector, what: String, text: String) {
        waitFor(selector, what).click()
        SystemClock.sleep(600)
        dismissOtherAppsNotResponding()
        // The box's description sits on a separate label node; Android's "set text" goes to the EditText itself.
        val editable = device.findObject(By.clazz("android.widget.EditText").focused(true)) ?: device.findObject(selector)
        runCatching { editable?.text = text }
        if (fieldShows(selector, text, 3_000)) return
        val now = runCatching { device.findObject(By.clazz("android.widget.EditText").focused(true))?.text }.getOrNull().orEmpty()
        // Keys still dropped at the end: type only what is missing; otherwise start again.
        if (now.isNotEmpty() && text.startsWith(now)) { shell("input text " + escape(text.removePrefix(now))); if (fieldShows(selector, text, 10_000)) return }
        runCatching { device.findObject(By.clazz("android.widget.EditText").focused(true))?.clear() }
        shell("input text " + escape(text))
        if (fieldShows(selector, text, 15_000)) return
        throw AssertionError("Typing into $what did not finish: \"${runCatching { device.findObject(selector)?.text }.getOrNull()}\"")
    }

    private fun escape(text: String) = text.replace(" ", "%s")

    private fun fieldShows(selector: BySelector, text: String, timeout: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            // Found by the text too: a filled field may no longer match a description selector.
            if (device.hasObject(By.text(text)) || runCatching { device.findObject(selector)?.text }.getOrNull() == text) return true
            dismissOtherAppsNotResponding()
            SystemClock.sleep(200)
        }
        return false
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    private fun <T> recording(dir: File, block: () -> T): T = try { block() } catch (failure: Throwable) {
        runCatching { device.takeScreenshot(File(dir, "failure.png")) }
        runCatching { File(dir, "failure-hierarchy.xml").outputStream().use { device.dumpWindowHierarchy(it) } }
        runCatching { File(dir, "failure-android-events.txt").writeText(shell("logcat -d -b events -v time").lines().filter { " wm_" in it || " am_" in it }.takeLast(200).joinToString("\n")) }
        runCatching { File(dir, "failure-activities.txt").writeText(shell("dumpsys activity activities").lines().filter { "Activity" in it || "mResumed" in it || "mFocused" in it }.take(80).joinToString("\n")) }
        throw failure
    }

    private fun waitFor(selector: BySelector, what: String, timeout: Long = 15_000): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (true) {
            device.findObject(selector)?.let { return it }
            dismissOtherAppsNotResponding()
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("$what did not appear")
            SystemClock.sleep(250)
        }
    }

    /**
     * A busy CI emulator sometimes shows "Pixel Launcher isn't responding" over Mylo, taking its touches and
     * keys. That dialog belongs to another app, so it is answered with Wait (and noted); a dialog about Mylo
     * itself is never dismissed, so a real Mylo freeze still fails the test.
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
        SystemClock.sleep(700)
        assertTrue("Could not save screenshot $name", device.takeScreenshot(File(dir, name)))
    }

    private companion object {
        const val URL_ARG = "aiServiceUrl"
        const val TOKEN_ARG = "aiServiceToken"
        const val PLANS_PAGE = "http://localhost:8080/plans.html"
        val CURRENT_PAGE_OFF: BySelector = By.res("voice-access-currentpage").hasDescendant(By.text("OFF"))
    }
}

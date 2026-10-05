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

                // Location (approximate, after Android's permission), a screenshot of the page and Saved Mylo Memory.
                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-settings").clickable(true), "Voice Mode settings").click()
                type(By.res("memory-input"), "the memory box", "I like short answers")
                waitFor(By.res("memory-add"), "Remember").click()
                waitFor(By.text("I like short answers"), "the remembered item")
                shot(dir, "07-saved-memory.png")
                waitFor(By.res("voice-settings-close"), "close settings").click()
                waitFor(By.res("voice-access-adjust"), "Adjust").click()
                scrollToRow("access-location-once")
                waitFor(By.res("access-location-once"), "Location: Allow once").click()
                waitFor(By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button"), "Android's location prompt", 20_000).click()
                scrollToRow("access-screenshot-once")
                waitFor(By.res("access-screenshot-once"), "Screenshot: Allow once").click()
                scrollToRow("access-mylomemory-always")
                waitFor(By.res("access-mylomemory-always"), "Saved Mylo Memory: Always").click()
                shot(dir, "08-location-screenshot-memory-on.png")
                waitFor(By.res("voice-access-close"), "close switchboard").click()
                waitFor(By.res("voice-type-instead"), "Type instead").click()
                type(By.res("voice-chat-input"), "the message box", "What do you see")
                waitFor(By.res("voice-chat-send"), "Send").click()
                val withDevice = waitFor(By.textContains("A screenshot of the page arrived."), "the answer with a screenshot", 30_000)
                assertTrue("Saved memory went with the question: ${withDevice.text}", withDevice.text.contains("1 remembered thing shared."))
                assertTrue("The approximate location went with the question: ${withDevice.text}", withDevice.text.contains("An approximate location was shared."))
                evidence.put("answerWithDeviceSources", withDevice.text)
                shot(dir, "09-answer-with-location-screenshot-memory.png"); steps.put("location, screenshot and memory shared when allowed")
                waitFor(By.res("voice-chat-close"), "close chat").click()
                waitFor(By.res("voice-close"), "Close voice mode").click()
            } }
            evidence.put("verified", true)
        } finally {
            File(dir, "evidence.json").writeText(evidence.put("steps", steps).put("otherAppDialogsDismissed", JSONArray(dismissed)).toString(2))
            AiPreferences(context).setTestService(null)
        }
    }

    /**
     * Mylo's realtime voice through the Mylo AI service: a short-lived session, a WebRTC call to the TEST
     * provider (a scripted peer that is not an AI and says so, with a tone as its voice), live captions, the
     * page context the switchboard allows (card number hidden), a tool call run on the page, Mute, Type instead
     * answered aloud, interruption, a voice sample, and the call ending with the microphone off.
     */
    @Test fun realtimeVoiceThroughTheTestService() {
        val url = arguments.getString(URL_ARG)
        assumeTrue("Pass -e $URL_ARG (and -e $TOKEN_ARG) to run against a Mylo AI test service", !url.isNullOrBlank())
        AiPreferences(context).setTestService(com.mylo.browser.ai.AiEndpoint(url!!, arguments.getString(TOKEN_ARG)))
        val dir = artifacts("realtime")
        val evidence = JSONObject().put("verified", false)
        val steps = JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { recording(dir) {
                type(By.desc("Search or enter address"), "Home search", PLANS_PAGE)
                waitFor(By.desc("Go"), "the Go button").click()
                waitFor(By.text("Mylo Plans"), "the test page", 20_000)
                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-mode-screen"), "the Voice Mode screen")

                // Tap to talk: Android's microphone prompt, then the call (hands-free).
                waitFor(By.res("voice-talk"), "the microphone").click()
                waitFor(By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button"), "Android's microphone prompt", 20_000).click()
                waitFor(By.res("voice-mute"), "the call's Mute control", 30_000)
                waitFor(By.res("voice-mic-live"), "the microphone-on indicator", 40_000)
                shot(dir, "01-call-listening.png"); steps.put("call open, microphone on")

                // The test provider "hears" a question, answers aloud and asks to show the pricing; Mylo runs it.
                waitFor(By.textContains("Mylo: (test voice"), "Mylo's spoken answer as a caption", 30_000)
                shot(dir, "02-speaking-caption.png"); steps.put("spoken answer captioned")
                waitFor(By.textContains("The pricing is on your screen now."), "Mylo's answer after the tool ran", 30_000)
                shot(dir, "02b-answer-after-tool.png")
                val log = providerLog()
                assertTrue("The page context arrived with the card number hidden: $log", log.any { it == "context title=Mylo Plans hidden=1" })
                assertTrue("The tool call ran on the page: $log", log.any { it.startsWith("tool call_test_1 ok=true") || it.startsWith("tool call_test_1 ok=True") })
                evidence.put("providerLog", JSONArray(log))
                steps.put("context sent once (card hidden); scroll_to Pricing ran")

                // Mute and unmute.
                waitFor(By.res("voice-mute"), "Mute").click()
                waitFor(By.res("voice-mic-muted"), "Microphone muted")
                shot(dir, "03-muted.png")
                waitFor(By.res("voice-mute"), "Unmute").click()
                waitFor(By.res("voice-mic-live"), "Microphone on again")
                steps.put("mute and unmute")

                // Type instead keeps the same conversation; Mylo answers aloud; a tap interrupts.
                waitFor(By.res("voice-type-instead"), "Type instead").click()
                waitFor(By.textContains("Where is the pricing?"), "the spoken question in the conversation")
                waitFor(By.textContains("The pricing is on your screen now."), "the spoken answer in the conversation")
                shot(dir, "04-conversation-has-the-call.png")
                type(By.res("voice-chat-input"), "the message box", "Tell me a long story")
                waitFor(By.res("voice-chat-send"), "Send").click()
                waitFor(By.res("voice-chat-close"), "close chat").click()
                waitFor(By.text("Mylo is speaking · tap to interrupt"), "Mylo speaking the typed answer", 30_000)
                shot(dir, "05-speaking.png")
                waitFor(By.res("voice-talk"), "the microphone").click()
                waitFor(By.text("Listening…"), "listening again after the interruption", 15_000)
                val afterInterrupt = providerLog()
                assertTrue("The interruption reached the provider: $afterInterrupt", afterInterrupt.contains("cancel") && afterInterrupt.contains("clear"))
                steps.put("type instead answered aloud; tap interrupted")

                // Hang up: the microphone is released.
                waitFor(By.res("voice-close"), "Close voice mode").click()
                waitFor(By.text("Mylo Plans"), "the page after closing Voice Mode")
                assertTrue("The microphone is off after closing", !device.hasObject(By.res("voice-mic-live")))
                waitFor(By.text("Pricing"), "the pricing section shown on the page")
                shot(dir, "06-page-shows-pricing.png"); steps.put("call closed; the page shows the pricing")

                // A voice sample plays through the same service (no microphone).
                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-mode-screen"), "the Voice Mode screen")
                waitFor(By.res("voice-settings").clickable(true), "Voice Mode settings").click()
                waitFor(By.res("voice-sample-cedar"), "Play sample (Cedar)").click()
                val deadline = SystemClock.uptimeMillis() + 20_000
                while (!providerLog().contains("sample") && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(300)
                assertTrue("The Cedar sample was requested", providerLog().contains("sample"))
                steps.put("voice sample requested")
            } }
            evidence.put("verified", true)
        } finally {
            File(dir, "evidence.json").writeText(evidence.put("steps", steps).put("otherAppDialogsDismissed", JSONArray(dismissed)).toString(2))
            AiPreferences(context).setTestService(null)
        }
    }

    /** Page actions that run on the phone without Mylo AI: find and mark pricing, find how to cancel, translate on the phone. */
    @Test fun pageActionsOnThePhone() {
        assumeTrue("This case covers builds without a Mylo AI service", BuildConfig.MYLO_AI_API_BASE_URL.isBlank())
        val dir = artifacts("page-actions")
        val evidence = JSONObject().put("verified", false)
        val steps = JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { recording(dir) {
                type(By.desc("Search or enter address"), "Home search", PLANS_PAGE)
                waitFor(By.desc("Go"), "the Go button").click()
                waitFor(By.text("Mylo Plans"), "the test page", 20_000)

                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-action-findpricing"), "Find the pricing section").click()
                waitFor(By.text("Mylo found the pricing section and marked it."), "the note on the page", 15_000)
                waitFor(By.text("Pricing"), "the pricing section on screen")
                shot(dir, "01-pricing-found.png"); steps.put("pricing found and marked on the phone")

                // Help me cancel: the page's own instructions become Page Coach steps, each marked on the page.
                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-action-helpcancel"), "Help me cancel").click()
                waitFor(By.text("Mylo Coach · Step 1 of 3"), "Page Coach", 15_000)
                waitFor(By.text("Open Account"), "the first step")
                shot(dir, "02-coach-step-1.png")
                waitFor(By.res("coach-next"), "Next step").click()
                waitFor(By.text("Choose Plan"), "the second step")
                waitFor(By.res("coach-next"), "Next step").click()
                waitFor(By.text("Cancel plan"), "the last step")
                waitFor(By.text("Cancel your plan"), "the cancel section on screen")
                shot(dir, "02b-coach-step-3.png")
                waitFor(By.res("coach-done"), "Done").click()
                device.wait(Until.gone(By.res("coach-bar")), 5_000)
                steps.put("page coach: three steps from the page's instructions")

                // Compare tabs: prices read from two open tabs, on the phone.
                waitFor(By.text("Tabs"), "Tabs in the bottom bar").click()
                waitFor(By.text("New tab"), "New tab").click()
                type(By.desc("Search or enter address"), "Home search", PLANS_PAGE_2)
                waitFor(By.desc("Go"), "the Go button").click()
                waitFor(By.text("Mylo Starter"), "the second shop", 20_000)
                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-action-comparetabs"), "Compare tabs").click()
                val comparison = waitFor(By.res("compare-summary"), "the comparison", 15_000)
                assertTrue("The cheaper plan per month wins: ${comparison.text}", comparison.text.startsWith("The lowest price is on “Mylo Starter”") && comparison.text.contains("\$48 a year"))
                evidence.put("comparison", comparison.text)
                shot(dir, "02c-compare-tabs.png"); steps.put("compared prices across two tabs on the phone")
                waitFor(By.res("voice-compare-close"), "close the comparison").click()
                waitFor(By.res("voice-close"), "Close voice mode").click()

                // Translate a Spanish page on the phone, then show the original.
                waitFor(By.text("Home"), "Home in the bottom bar").click()
                type(By.desc("Search or enter address"), "Home search", SPANISH_PAGE)
                waitFor(By.desc("Go"), "the Go button").click()
                waitFor(By.text("Planes de Mylo"), "the Spanish page", 20_000)
                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-action-translate"), "Translate this page").click()
                waitFor(By.res("translate-source"), "the detected language", 20_000)
                shot(dir, "03-translate-sheet.png")
                evidence.put("detected", device.findObject(By.res("translate-source"))?.text)
                waitFor(By.res("translate-to-en"), "English").click()
                waitFor(By.res("translate-go"), "Translate").click()
                waitFor(By.textStartsWith("Translated from Spanish to English on this phone."), "the translated note", 180_000)
                val translated = waitFor(By.textContains("month"), "English text on the page", 20_000)
                evidence.put("translatedSample", translated.text)
                shot(dir, "04-translated.png"); steps.put("translated Spanish to English on the phone")
                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-action-translate"), "Translate this page").click()
                waitFor(By.res("translate-original"), "Show original").click()
                waitFor(By.text("Precios"), "the original Spanish again", 15_000)
                shot(dir, "05-original.png"); steps.put("original restored")

                // Is this site safe? A page imitating a scam is flagged by checks on the phone.
                waitFor(By.text("Home"), "Home in the bottom bar").click()
                type(By.desc("Search or enter address"), "Home search", SCAM_PAGE)
                waitFor(By.desc("Go"), "the Go button").click()
                waitFor(By.text("Security alert"), "the test scam page", 20_000)
                waitFor(By.text("Mylo"), "the Mylo button").click()
                waitFor(By.res("voice-action-sitesafety"), "Is this site safe?").click()
                val summary = waitFor(By.res("safety-summary"), "the site check", 15_000)
                assertTrue("The scam page is flagged: ${summary.text}", summary.text.startsWith("Be careful"))
                val findings = waitFor(By.res("safety-list"), "the findings")
                listOf("Password field without encryption", "A form sends to another site", "Unusual ways to pay or recover accounts", "Pressure to act quickly")
                    .forEach { title -> if (!device.hasObject(By.text(title))) findings.scrollUntil(Direction.DOWN, Until.findObject(By.text(title))); waitFor(By.text(title), "the finding “$title”") }
                evidence.put("safetySummary", summary.text)
                shot(dir, "06-site-check.png"); steps.put("scam page flagged on the phone")
                waitFor(By.res("voice-safety-close"), "close the site check").click()
            } }
            evidence.put("verified", true)
        } finally {
            File(dir, "evidence.json").writeText(evidence.put("steps", steps).put("otherAppDialogsDismissed", JSONArray(dismissed)).toString(2))
        }
    }

    /** Scrolls the switchboard sheet (found afresh: it redraws after Android's permission prompt) until [res] shows. */
    private fun scrollToRow(res: String) {
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            if (device.hasObject(By.res(res))) return
            runCatching { waitFor(By.res("voice-access-list"), "the switchboard list").scrollUntil(direction, Until.findObject(By.res(res))) }
        }
    }

    /** What the TEST provider received on the call's event channel. */
    private fun providerLog(): List<String> = runCatching {
        val connection = java.net.URL("http://localhost:8091/test/realtime").openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 3_000; connection.readTimeout = 3_000
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val events = json.getJSONArray("events")
        (0 until events.length()).map { events.getString(it) }
    }.getOrDefault(emptyList())

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
        const val SPANISH_PAGE = "http://localhost:8080/planes.html"
        const val SCAM_PAGE = "http://localhost:8080/scam.html"
        const val PLANS_PAGE_2 = "http://localhost:8080/plans2.html"
        val CURRENT_PAGE_OFF: BySelector = By.res("voice-access-currentpage").hasDescendant(By.text("OFF"))
    }
}

package com.mylo.browser

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Private Mode on a real device, through the UI (Private Mode runs in its own process). Uses the local test
 * site (compat-site, `adb reverse tcp:8080`) for a page with a first-party visit counter and four real tracker
 * URLs. Screenshots and evidence go to test-artifacts/private/<case>/. Debug builds let this test opt in to
 * screenshots of Private Mode, which normally blocks them.
 */
@RunWith(AndroidJUnit4::class)
class PrivateModeFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Before fun setUp() {
        context.getSharedPreferences(PrivateActivity.TEST_PREFS, 0).edit().putBoolean(PrivateActivity.ALLOW_SCREENSHOTS, true).commit()
        // The CI script stops Mylo between cases; this test process must not stop its own app.
        context.getSharedPreferences("mylo_private_settings", 0).edit().clear().commit()
    }

    @After fun tearDown() {
        context.getSharedPreferences(PrivateActivity.TEST_PREFS, 0).edit().clear().commit()
    }

    private fun artifacts(case: String) = File(context.getExternalFilesDir(null), "test-artifacts/private/$case").apply { deleteRecursively(); mkdirs() }

    /**
     * The approved screen, private browsing with tracker blocking, separate cookies, Burn, and leaving
     * Private Mode without anything in normal history.
     */
    @Test fun privateSessionIsSeparateBlocksTrackersAndBurns() {
        val dir = artifacts("session")
        val evidence = JSONObject().put("verified", false)
        val steps = JSONArray()
        try { recording(dir) {
            context.getSharedPreferences("mylo_browser", 0).edit().remove("history").commit()
            ActivityScenario.launch(MainActivity::class.java).use {
                // 1. A normal tab visits the test page first, so its cookie exists outside Private Mode.
                typeInto(By.desc("Search or enter address"), "Home search", NORMAL_PAGE)
                submit()
                waitFor(By.textContains("Visit 1 in this browser"), "the test page in a normal tab")
                shot(dir, "01-normal-tab.png"); steps.put("normal tab visit 1")
                device.pressBack()

                // 2. Home's Private card opens the approved Private Mode screen in the private process.
                waitFor(By.text("Private"), "Home's Private card").click()
                waitFor(By.res("private-mode-screen"), "the Private Mode screen", 20_000)
                listOf("Active", "No history saved", "Block trackers", "Lock tabs", "Burn session on exit", "Enter Private Session", "Burn on Exit", "Clear everything now")
                    .forEach { waitFor(By.text(it), "\"$it\"") }
                shot(dir, "02-private-mode.png"); steps.put("private mode screen")

                // 3. Enter Private Session → a private new tab; the same test page sees no normal cookie.
                device.findObject(By.res("private-enter")).click()
                typeInto(By.res("private-search-input"), "the private search box", PRIVATE_PAGE)
                shot(dir, "03-private-new-tab.png"); steps.put("private new tab")
                submit()
                waitFor(By.textContains("Visit 1 in this browser"), "a fresh cookie jar in Private Mode", 20_000)
                waitFor(By.textContains("did not load"), "the tracker requests to finish")
                val blockedOnPage = waitFor(By.res("private-page-trackers"), "the private strip").text
                assertTrue("All four trackers were blocked: $blockedOnPage", blockedOnPage.startsWith("4 trackers blocked"))
                evidence.put("privatePageStrip", blockedOnPage)
                shot(dir, "04-private-page-trackers-blocked.png"); steps.put("trackers blocked: $blockedOnPage")

                // 4. The Block trackers page lists what was blocked.
                device.findObject(By.res("private-nav-mylo")).click()
                waitFor(By.res("private-row-block-trackers"), "the Block trackers row").click()
                waitFor(By.textContains("tracker requests blocked this session"), "the tracker count")
                waitFor(By.text("google-analytics.com"), "Google Analytics in the blocked list")
                shot(dir, "05-block-trackers-sheet.png"); steps.put("blocked list")
                device.pressBack()

                // 5. Burn on Exit → Clear now: tabs, cookies and storage are gone.
                waitFor(By.res("private-burn-now"), "Burn on Exit").click()
                waitFor(By.text("Clear now"), "the burn confirmation").click()
                waitFor(By.textContains("Private session cleared"), "the burn message")
                shot(dir, "06-burned.png"); steps.put("burned")
                device.findObject(By.res("private-enter")).click()
                typeInto(By.res("private-search-input"), "the private search box after burning", PRIVATE_PAGE)
                submit()
                waitFor(By.textContains("Visit 1 in this browser (cookie) · storage visit 1"), "cookie and storage cleared by the burn", 20_000)
                shot(dir, "07-after-burn-fresh-storage.png"); steps.put("fresh cookie and storage after burn")

                // 6. Home leaves Private Mode; Burn session on exit (on by default) asks first.
                device.findObject(By.res("private-nav-home")).click()
                waitFor(By.text("Leave and burn"), "the leave confirmation").click()
                waitFor(By.desc("Search or enter address"), "normal Home after leaving", 20_000)
                shot(dir, "08-back-home.png"); steps.put("left private mode")
            }

            // 7. Nothing private reached normal history; the normal tab's visit is there.
            SystemClock.sleep(1_000)
            val history = context.getSharedPreferences("mylo_browser", 0).getString("history", "[]").orEmpty()
            val entries = JSONArray(history)
            val urls = (0 until entries.length()).map { entries.getJSONObject(it).optString("url") }
            evidence.put("normalHistory", JSONArray(urls))
            assertTrue("The normal tab's visit is in history: $urls", urls.any { it.contains("tab=normal") })
            assertFalse("Private visits must not be written to history: $urls", urls.any { it.contains("tab=private") })
            assertFalse("The private process ended after the burn", shell("pidof ${context.packageName}:private").isNotBlank())
            evidence.put("privateProcessEnded", true)
            evidence.put("verified", true)
        } } finally {
            File(dir, "evidence.json").writeText(evidence.put("steps", steps).toString(2))
        }
    }

    /**
     * Opens Private Mode with Android's animations on, so the CI script's screen recording captures the
     * entrance (corgi settling, the sunglasses' highlight, the shield pulse, Active fading in).
     */
    @Test fun entranceAnimation() {
        val dir = artifacts("entrance")
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(By.text("Private"), "Home's Private card").click()
            waitFor(By.res("private-mode-screen"), "the Private Mode screen", 20_000)
            SystemClock.sleep(2_500)
            shot(dir, "after-entrance.png")
        }
    }

    /** Lock tabs with Android's own screen lock (a test PIN is set and removed by this test). */
    @Test fun lockTabsNeedsTheScreenLock() {
        val dir = artifacts("lock")
        val evidence = JSONObject().put("verified", false)
        shell("locksettings set-pin $PIN")
        try { recording(dir) {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor(By.text("Private"), "Home's Private card").click()
                waitFor(By.res("private-mode-screen"), "the Private Mode screen", 20_000)
                device.findObject(By.res("private-row-lock-tabs")).click()
                waitFor(By.textContains("Mylo never sees your screen lock"), "the Lock tabs page")
                shot(dir, "01-lock-sheet.png")
                waitFor(By.checkable(true).checked(false), "the Lock tabs switch").click()
                enterPin("turning Lock tabs on")
                waitFor(By.checkable(true).checked(true), "Lock tabs switched on")
                shot(dir, "02-lock-on.png")
                device.pressBack()
                device.findObject(By.res("private-enter")).click()
                typeInto(By.res("private-search-input"), "the private search box", PRIVATE_PAGE)
                submit()
                waitFor(By.textContains("in this browser"), "the private page", 20_000)

                // Leave Mylo and come back: the tabs are locked, and Android's screen lock is asked for at once.
                device.pressHome()
                SystemClock.sleep(1_500)
                context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val shown = device.wait(Until.hasObject(By.res("private-locked")), 8_000) || device.wait(Until.hasObject(By.pkg("com.android.systemui")), 12_000)
                assertTrue("Neither the lock screen nor Android's unlock prompt appeared", shown)
                evidence.put("lockScreenSeen", device.hasObject(By.res("private-locked")))
                SystemClock.sleep(1_500)
                shot(dir, "03-locked-with-prompt.png")
                enterPin("unlocking private tabs")
                device.wait(Until.gone(By.res("private-locked")), 10_000)
                waitFor(By.textContains("in this browser"), "the private page after unlocking")
                shot(dir, "04-unlocked.png")
                evidence.put("verified", true)
            }
        } } finally {
            shell("locksettings clear --old $PIN")
            File(dir, "evidence.json").writeText(evidence.toString(2))
        }
    }

    private fun enterPin(why: String) {
        // Android's own credential screen (System UI): type the PIN like a user.
        if (!device.wait(Until.hasObject(By.pkg("com.android.systemui")), 10_000)) throw AssertionError("Android's screen-lock prompt did not appear ($why)")
        SystemClock.sleep(800)
        device.findObject(By.textContains("Use PIN"))?.click()
        shell("input text $PIN")
        SystemClock.sleep(300)
        device.pressEnter()
        SystemClock.sleep(1_500)
    }

    /** The search box's own Go button, which appears once something is typed. */
    private fun submit() = waitFor(By.desc("Go"), "the Go button").click()

    /** On failure: a screenshot and the screen's accessibility tree, for the evidence. */
    private fun <T> recording(dir: File, block: () -> T): T = try { block() } catch (failure: Throwable) {
        runCatching { device.takeScreenshot(File(dir, "failure.png")) }
        runCatching { File(dir, "failure-hierarchy.xml").outputStream().use { device.dumpWindowHierarchy(it) } }
        runCatching { File(dir, "failure-activities.txt").writeText(shell("dumpsys activity activities").lines().filter { "Activity" in it || "mResumed" in it || "mFocused" in it }.take(80).joinToString("\n")) }
        throw failure
    }

    /**
     * Fills a text field: first through the field's own accessibility "set text" action (what Mylo's search
     * tests use; no key events for the keyboard or a busy emulator to drop), then, if the field didn't take
     * it, with Android's key events. Go is pressed only once the whole address is in the field.
     */
    private fun typeInto(selector: BySelector, what: String, text: String) {
        waitFor(selector, what).click()
        SystemClock.sleep(600)
        runCatching { waitFor(selector, what).text = text }
        if (fieldShows(selector, text, 3_000)) return
        runCatching { waitFor(selector, what).clear() }
        shell("input text " + text.replace("&", "\\&"))
        if (fieldShows(selector, text, 15_000)) return
        throw AssertionError("Typing into $what did not finish: \"${runCatching { device.findObject(selector)?.text }.getOrNull()}\"")
    }

    private fun fieldShows(selector: BySelector, text: String, timeout: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching { device.findObject(selector)?.text }.getOrNull() == text) return true
            SystemClock.sleep(200)
        }
        return false
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    private fun waitFor(selector: BySelector, what: String, timeout: Long = 15_000): UiObject2 =
        device.wait(Until.findObject(selector), timeout) ?: throw AssertionError("$what did not appear")

    private fun shot(dir: File, name: String) {
        SystemClock.sleep(700)
        assertTrue("Could not save screenshot $name", device.takeScreenshot(File(dir, name)))
    }

    private companion object {
        const val NORMAL_PAGE = "http://localhost:8080/trackers.html?tab=normal"
        const val PRIVATE_PAGE = "http://localhost:8080/trackers.html?tab=private"
        const val PIN = "1234"
    }
}

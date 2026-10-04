package com.mylo.browser

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device portrait layout checks; screenshots include the Android system bars and IME. */
@RunWith(AndroidJUnit4::class)
class HomeLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val caseName get() = arguments.getString("layoutCase", "current-device")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    private val artifacts: File
        get() = File(instrumentation.targetContext.getExternalFilesDir(null), "home-layout-artifacts/$caseName")
            .apply { mkdirs() }

    @Test
    fun pinnedHomeAndKeyboardRespectPortraitInsets() {
        val report = JSONObject().put("case", caseName).put("verified", false)
            .put("requestedNavigation", arguments.getString("navigationMode", "current"))
        try {
            compose.waitForIdle()
            val density = compose.activity.resources.displayMetrics.density
            val widthDp = device.displayWidth / density
            val heightDp = device.displayHeight / density
            val fontScale = compose.activity.resources.configuration.fontScale
            assertTrue("This is a portrait-only Home check", heightDp > widthDp)
            arguments.getString("expectedWidthDp")?.toFloat()?.let {
                assertEquals("Requested device width was not applied", it, widthDp, 1f)
            }
            arguments.getString("expectedHeightDp")?.toFloat()?.let {
                assertEquals("Requested device height was not applied", it, heightDp, 1f)
            }
            arguments.getString("expectedFontScale")?.toFloat()?.let {
                assertEquals("Requested font scale was not applied", it, fontScale, .01f)
            }
            report.put("widthDp", widthDp).put("heightDp", heightDp)
                .put("density", density).put("fontScale", fontScale)

            val initial = assertHomeLayout()
            val scrollBefore = scrollPosition()
            report.put("homeInitial", geometry(initial)).put("scrollBefore", scrollBefore.first)
                .put("scrollMaximum", scrollBefore.second)
            capture("01-home-top.png")

            // Exercise the actual touch scroll region before bringing its final card fully into view.
            compose.onNodeWithTag(MIDDLE).performTouchInput { swipeUp() }
            compose.onNodeWithTag(DISCOVERY).performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            val scrolled = assertHomeLayout()
            assertPinned(initial, scrolled)
            val discovery = bounds(DISCOVERY)
            assertInside("The entire discovery card must be reachable", discovery, scrolled.getValue(MIDDLE))
            val scrollAfter = scrollPosition()
            if (scrollBefore.second > 1f) {
                assertTrue("Overflowing middle content must move when scrolled", scrollAfter.first > scrollBefore.first)
            }
            report.put("homeScrolled", geometry(scrolled)).put("discovery", rectangle(discovery))
                .put("scrollAfter", scrollAfter.first).put("pinnedWhileScrolling", true)
            capture("02-home-bottom-content.png")

            // Search remains reachable even when the middle content is at its lower edge.
            compose.onNodeWithContentDescription("Search or enter address").performClick()
            compose.onNodeWithTag(SEARCH_MODE).assertIsDisplayed()
            compose.onNodeWithTag(SEARCH_INPUT).assertIsDisplayed().assertIsFocused()
            waitForKeyboard(true)
            compose.onNodeWithTag(SEARCH_INPUT).performTextInput("best beaches in Florida")
            compose.onNodeWithTag(SEARCH_INPUT).assertTextEquals("best beaches in Florida")
            val keyboardSafe = safeArea(includeKeyboard = true)
            assertInside("Focused search input must remain above the real keyboard", bounds(SEARCH_INPUT), keyboardSafe)
            assertInside("Search mode must resize above the keyboard", bounds(SEARCH_MODE), keyboardSafe)
            report.put("keyboardVisible", true).put("keyboardSafeArea", rectangle(keyboardSafe))
                .put("searchInput", rectangle(bounds(SEARCH_INPUT)))
            capture("03-search-and-android-keyboard.png")

            compose.onNodeWithContentDescription("Close search").assertIsDisplayed().performClick()
            compose.onNodeWithTag(SEARCH_MODE).assertDoesNotExist()
            waitForKeyboard(false)
            val restored = assertHomeLayout()
            assertPinned(initial, restored)
            compose.onNodeWithContentDescription("Search or enter address").assertIsDisplayed()
            report.put("homeRestored", geometry(restored)).put("keyboardDismissed", true)
                .put("homeRestoredAfterClosingSearch", true).put("verified", true)
            capture("04-home-restored.png")
        } catch (failure: Throwable) {
            report.put("verified", false).put("failure", failure.message ?: failure.javaClass.simpleName)
            runCatching { device.takeScreenshot(File(artifacts, "failure.png")) }
            throw failure
        } finally {
            File(artifacts, "layout-evidence.json").writeText(report.toString(2))
        }
    }

    private fun assertHomeLayout(): Map<String, Rect> {
        val layout = listOf(HEADER, SEARCH, MIDDLE, BOTTOM).associateWith { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            bounds(tag)
        }
        val safe = safeArea()
        layout.forEach { (tag, rectangle) -> assertInside("$tag must avoid system bars/cutouts", rectangle, safe) }
        assertAbove("Greeting and search must not overlap", layout.getValue(HEADER), layout.getValue(SEARCH))
        assertAbove("Search must stay above the scrolling content", layout.getValue(SEARCH), layout.getValue(MIDDLE))
        assertAbove("Scrolling content must stay above navigation", layout.getValue(MIDDLE), layout.getValue(BOTTOM))
        val greeting = compose.onNodeWithText("Good evening!", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val settings = compose.onNodeWithContentDescription("Settings", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertInside("Greeting remains inside the pinned header", greeting, layout.getValue(HEADER))
        assertInside("Settings remains inside the pinned header", settings, layout.getValue(HEADER))
        assertTrue("Greeting must not overlap the settings control", greeting.right <= settings.left + 1f)
        listOf("Home", "Search", "Tabs", "Mylo").forEach { label ->
            val labelBounds = compose.onNode(
                hasText(label) and hasAnyAncestor(hasTestTag(BOTTOM)), useUnmergedTree = true,
            ).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertInside("$label navigation label must be fully visible", labelBounds, layout.getValue(BOTTOM))
        }
        return layout
    }

    private fun bounds(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val rectangle = node.boundsInWindow
        assertEquals("$tag must not be clipped horizontally", node.size.width.toFloat(), rectangle.width, 1f)
        assertEquals("$tag must not be clipped vertically", node.size.height.toFloat(), rectangle.height, 1f)
        return rectangle
    }

    private fun scrollPosition(): Pair<Float, Float> {
        val range = compose.onNodeWithTag(MIDDLE).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        var position = 0f
        var maximum = 0f
        compose.runOnIdle { position = range.value(); maximum = range.maxValue() }
        return position to maximum
    }

    private fun safeArea(includeKeyboard: Boolean = false): Rect {
        var result = Rect.Zero
        instrumentation.runOnMainSync {
            val decor = compose.activity.window.decorView
            val windowInsets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
            var types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            if (includeKeyboard) types = types or WindowInsetsCompat.Type.ime()
            val insets = windowInsets.getInsets(types)
            result = Rect(insets.left.toFloat(), insets.top.toFloat(),
                (decor.width - insets.right).toFloat(), (decor.height - insets.bottom).toFloat())
        }
        return result
    }

    private fun waitForKeyboard(visible: Boolean) {
        compose.waitUntil(5_000) {
            var actual = !visible
            instrumentation.runOnMainSync {
                actual = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            actual == visible
        }
        // IME visibility can change before its window-inset animation finishes.
        SystemClock.sleep(400)
        compose.waitForIdle()
    }

    private fun assertPinned(before: Map<String, Rect>, after: Map<String, Rect>) {
        listOf(HEADER, SEARCH, BOTTOM).forEach { tag ->
            val old = before.getValue(tag)
            val current = after.getValue(tag)
            assertTrue("$tag moved while middle content scrolled or after search closed: $old → $current",
                abs(old.left - current.left) <= 1f && abs(old.top - current.top) <= 1f &&
                    abs(old.right - current.right) <= 1f && abs(old.bottom - current.bottom) <= 1f)
        }
    }

    private fun assertAbove(message: String, upper: Rect, lower: Rect) {
        assertTrue("$message: $upper / $lower", upper.bottom <= lower.top + 1f)
    }

    private fun assertInside(message: String, inner: Rect, outer: Rect) {
        assertTrue("$message: $inner inside $outer", inner.width > 0f && inner.height > 0f &&
            inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
            inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f)
    }

    private fun rectangle(value: Rect) = JSONObject().put("left", value.left).put("top", value.top)
        .put("right", value.right).put("bottom", value.bottom)

    private fun geometry(layout: Map<String, Rect>) = JSONObject().apply {
        layout.forEach { (name, value) -> put(name, rectangle(value)) }
        put("systemSafeArea", rectangle(safeArea()))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        assertTrue("Could not capture real device screenshot $name", device.takeScreenshot(File(artifacts, name)))
    }

    companion object {
        private const val HEADER = "home-header"
        private const val SEARCH = "home-search"
        private const val MIDDLE = "home-middle"
        private const val DISCOVERY = "home-discovery"
        private const val BOTTOM = "home-bottom-nav"
        private const val SEARCH_MODE = "search-input-mode"
        private const val SEARCH_INPUT = "search-input"
    }
}

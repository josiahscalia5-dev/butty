package com.mylo.browser

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Candidate appearances rendered by the actual production composables, without changing the app default. */
@RunWith(AndroidJUnit4::class)
class HomeVariationsRenderTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun threePolishVariationsAt393x851() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val device = UiDevice.getInstance(instrumentation)
        val caseName = arguments.getString("layoutCase", "393x851-variations")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val artifacts = File(instrumentation.targetContext.getExternalFilesDir(null), "home-layout-artifacts/$caseName")
            .apply { mkdirs() }
        val reports = JSONArray()
        val failures = mutableListOf<String>()
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("Variation renders require a 393 dp width", 393f, device.displayWidth / density, 1f)
        assertEquals("Variation renders require an 851 dp height", 851f, device.displayHeight / density, 1f)
        assertEquals("Variation renders require default font scale", 1f,
            compose.activity.resources.configuration.fontScale, .01f)

        val variations = listOf(
            HomePolish.REFERENCE to "06-variation-a-reference",
            HomePolish.SEARCH_FOCUS to "07-variation-b-search",
            HomePolish.ROOMY_CARDS to "08-variation-c-roomy-cards",
        )
        variations.forEach { (polish, filename) ->
            val report = JSONObject().put("variation", polish.name).put("verified", false)
                .put("source", "Real Android production composables; test-only appearance selection")
                .put("vpnActive", false).put("tabCount", 0)
            try {
                compose.runOnUiThread {
                    compose.activity.setContent {
                        MyloTheme {
                            key(polish) {
                                MyloViewport(edgeToEdgeHome = true) {
                                    Box(Modifier.weight(1f)) { HomeScreen(polish = polish) }
                                    BottomBar(true, 0, {}, {}, {}, {})
                                }
                            }
                        }
                    }
                }
                compose.waitForIdle()
                val layout = listOf("home-header", "home-search", "home-middle", "home-discovery", "home-bottom-nav")
                    .associateWith { tag ->
                        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
                        val bounds = node.boundsInWindow
                        assertEquals("$polish: $tag is horizontally clipped", node.size.width.toFloat(), bounds.width, 1f)
                        assertEquals("$polish: $tag is vertically clipped", node.size.height.toFloat(), bounds.height, 1f)
                        bounds
                    }
                var safeArea = Rect.Zero
                instrumentation.runOnMainSync {
                    val decor = compose.activity.window.decorView
                    val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                        .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    safeArea = Rect(insets.left.toFloat(), insets.top.toFloat(),
                        (decor.width - insets.right).toFloat(), (decor.height - insets.bottom).toFloat())
                }
                listOf("home-header", "home-search", "home-middle", "home-bottom-nav").forEach {
                    assertInside("$polish: $it must avoid system bars and cutouts", layout.getValue(it), safeArea)
                }
                assertInside("$polish: complete discovery banner must fit before scrolling",
                    layout.getValue("home-discovery"), layout.getValue("home-middle"))
                assertTrue("$polish: search must stay above middle content",
                    layout.getValue("home-search").bottom <= layout.getValue("home-middle").top + 1f)
                assertTrue("$polish: middle content must stay above bottom navigation",
                    layout.getValue("home-middle").bottom <= layout.getValue("home-bottom-nav").top + 1f)
                val range = compose.onNodeWithTag("home-middle").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                var scrollMaximum = 0f
                compose.runOnIdle { scrollMaximum = range.maxValue() }
                assertTrue("$polish: initial approved portrait must fit without scrolling", scrollMaximum <= density)
                device.waitForIdle(1_000)
                assertTrue("Could not capture actual Android variation $polish",
                    device.takeScreenshot(File(artifacts, "$filename.png")))
                report.put("verified", true).put("screenshot", "$filename.png")
                    .put("widthPixels", device.displayWidth).put("heightPixels", device.displayHeight)
                    .put("density", density).put("completeBannerVisible", true)
                    .put("scrollMaximum", scrollMaximum)
                    .put("bounds", JSONObject().apply {
                        layout.forEach { (tag, bounds) -> put(tag, rectangle(bounds)) }
                        put("systemSafeArea", rectangle(safeArea))
                    })
            } catch (failure: Throwable) {
                val reason = failure.message ?: failure.javaClass.simpleName
                report.put("failure", reason).put("screenshot", "$filename.png")
                failures.add("${polish.name}: $reason")
                runCatching { device.takeScreenshot(File(artifacts, "$filename.png")) }
            } finally {
                reports.put(report)
                File(artifacts, "$filename.json").writeText(report.toString(2))
                File(artifacts, "home-variations-evidence.json").writeText(JSONObject()
                    .put("verified", reports.length() == variations.size && failures.isEmpty())
                    .put("variations", reports).toString(2))
            }
        }
        assertTrue("Native Home variation checks failed:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun assertInside(message: String, inner: Rect, outer: Rect) {
        assertTrue("$message: $inner inside $outer", inner.width > 0f && inner.height > 0f &&
            inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
            inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f)
    }

    private fun rectangle(bounds: Rect) = JSONObject().put("left", bounds.left).put("top", bounds.top)
        .put("right", bounds.right).put("bottom", bounds.bottom)
}

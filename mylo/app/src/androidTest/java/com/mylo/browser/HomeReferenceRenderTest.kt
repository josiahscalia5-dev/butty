package com.mylo.browser

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

/** Device rendering of production composables with the approved image's sample data, only in tests. */
@RunWith(AndroidJUnit4::class)
class HomeReferenceRenderTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun approvedReferenceStateAt393x851() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val device = UiDevice.getInstance(instrumentation)
        val caseName = arguments.getString("layoutCase", "393x851-reference")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val artifacts = File(instrumentation.targetContext.getExternalFilesDir(null), "home-layout-artifacts/$caseName")
            .apply { mkdirs() }
        val report = JSONObject().put("verified", false).put("testFixture", true)
            .put("source", "Real Android rendering of production HomeScreen, BottomBar, MyloViewport and MyloTheme")
            .put("fixture", "Approved reference only: active VPN label, Singapore, one tab; no VPN service is simulated")
        try {
            val density = compose.activity.resources.displayMetrics.density
            assertEquals("Reference render requires a 393 dp width", 393f, device.displayWidth / density, 1f)
            assertEquals("Reference render requires an 851 dp height", 851f, device.displayHeight / density, 1f)
            assertEquals("Reference render requires default font scale", 1f,
                compose.activity.resources.configuration.fontScale, .01f)
            compose.runOnUiThread {
                compose.activity.setContent {
                    MyloTheme {
                        MyloViewport(edgeToEdgeHome = true) {
                            Box(Modifier.weight(1f)) {
                                HomeScreen(vpnActive = true, vpnLocation = "Singapore")
                            }
                            BottomBar(NavTab.Home, 1, {}, {}, {}, {})
                        }
                    }
                }
            }
            compose.waitForIdle()
            listOf("home-header", "home-search", "home-discovery", "home-bottom-nav").forEach {
                compose.onNodeWithTag(it).assertIsDisplayed()
            }
            compose.onNodeWithText("Singapore", useUnmergedTree = true).assertIsDisplayed()
            device.waitForIdle(1_000)
            assertTrue("Failed to capture the actual Android reference render",
                device.takeScreenshot(File(artifacts, "05-approved-reference-state.png")))
            report.put("verified", true).put("widthPixels", device.displayWidth)
                .put("heightPixels", device.displayHeight).put("density", density)
                .put("screenshot", "05-approved-reference-state.png")
        } catch (failure: Throwable) {
            report.put("failure", failure.message ?: failure.javaClass.simpleName)
            runCatching { device.takeScreenshot(File(artifacts, "reference-render-failure.png")) }
            throw failure
        } finally {
            File(artifacts, "reference-render.json").writeText(report.toString(2))
        }
    }
}

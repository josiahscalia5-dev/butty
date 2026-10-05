package com.mylo.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test

/**
 * Mylo's browser chrome (toolbar and bottom navigation) on a 411 × 914 dp Pixel 6, for comparison with
 * design/reference/browser-reference.jpg. Layoutlib cannot run a WebView, so the page area is a plain
 * placeholder here; the real provider page is checked on the emulator.
 */
class BrowserChromePreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, density = Density(420)),
        theme = "Theme.Mylo",
        showSystemUi = false,
    )

    @Test fun browserChrome() {
        paparazzi.snapshot(name = "mylo_browser_chrome_1080x2400") {
            MyloTheme {
                MyloViewport(edgeToEdgeHome = false) {
                    // The target's (taller, iPhone-style) status bar, so the toolbar lines up for comparison.
                    Spacer(Modifier.fillMaxWidth().height(51.dp))
                    BrowserToolbar(url = "https://www.google.com/search?q=Facebook", loading = false, progress = 100, canGoForward = false,
                        editing = false, address = "", onAddress = {}, onEditing = {}, onBack = {}, onForward = {}, onReloadOrStop = {}, onSubmit = {},
                        bookmarked = false, onBookmark = {}, privateMode = false, menu = { _, _ -> })
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1F1F23)))
                    BottomBar(NavTab.Search, 1, {}, {}, {}, {}, NavMetrics.Browser, navigationInset = 24.dp)
                }
            }
        }
    }

    @Test fun browserChromeLongAddressAndLoading() {
        paparazzi.snapshot(name = "mylo_browser_chrome_long_loading") {
            MyloTheme {
                MyloViewport(edgeToEdgeHome = false) {
                    Spacer(Modifier.fillMaxWidth().height(24.dp))
                    BrowserToolbar(url = "https://accounts.subdomain.an-extremely-long-hostname-for-testing.example.co.uk/path?q=1", loading = true, progress = 45,
                        canGoForward = true, editing = false, address = "", onAddress = {}, onEditing = {}, onBack = {}, onForward = {}, onReloadOrStop = {},
                        onSubmit = {}, bookmarked = true, onBookmark = {}, privateMode = false, menu = { _, _ -> })
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color.White))
                    BottomBar(NavTab.Search, 12, {}, {}, {}, {}, NavMetrics.Browser, navigationInset = 24.dp)
                }
            }
        }
    }
}

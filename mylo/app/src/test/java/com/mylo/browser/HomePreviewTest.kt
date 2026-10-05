package com.mylo.browser

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test

/** Uses Android Layoutlib to render the actual production Compose screen, not an HTML imitation. */
class HomePreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        // 786×1702 px at 320 dpi is exactly the requested 393×851 dp viewport.
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 786,
            screenHeight = 1702,
            density = Density.XHIGH,
        ),
        theme = "Theme.Mylo",
        showSystemUi = true,
    )

    @Test fun homePortrait() {
        paparazzi.snapshot(name = "mylo_home_production_393x851") {
            MyloTheme {
                MyloViewport(edgeToEdgeHome = true) {
                    Box(Modifier.weight(1f)) { HomeScreen() }
                    BottomBar(NavTab.Home, 0, {}, {}, {}, {})
                }
            }
        }
    }

}

/**
 * The approved Home target's own phone: 1080 × 1920 px at 420 dpi (411.4 × 731.4 dp), exactly 1.25× the
 * 864 × 1536 px target, so the render can be compared with design/reference/home-reference.jpg pixel for pixel.
 */
class HomeTargetPreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 1920, density = Density(420)),
        theme = "Theme.Mylo",
        // The target's system bars (a 24 dp status bar, gesture navigation) are passed in explicitly.
        showSystemUi = false,
    )

    /** The same phone with a camera-cutout status bar (39 dp, as the Pixel 6 emulator reports at this size). */
    @Test fun homeAtTargetSizeTallStatusBar() {
        paparazzi.snapshot(name = "mylo_home_target_1080x1920_status39") {
            MyloTheme {
                MyloViewport(edgeToEdgeHome = true) {
                    Box(Modifier.weight(1f)) { HomeScreen(statusBarInset = 39.dp) }
                    BottomBar(NavTab.Home, 0, {}, {}, {}, {}, navigationInset = 24.dp)
                }
            }
        }
    }

    @Test fun homeAtTargetSize() {
        paparazzi.snapshot(name = "mylo_home_target_1080x1920") {
            MyloTheme {
                MyloViewport(edgeToEdgeHome = true) {
                    Box(Modifier.weight(1f)) { HomeScreen(statusBarInset = 24.dp) }
                    BottomBar(NavTab.Home, 0, {}, {}, {}, {}, navigationInset = 24.dp)
                }
            }
        }
    }
}

/** Compact portrait rendering of the same production composables, including its scroll viewport. */
class HomeCompactPreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 720,
            screenHeight = 1280,
            density = Density.XHIGH,
        ),
        theme = "Theme.Mylo",
        showSystemUi = true,
    )

    @Test fun compactProductionPortrait() {
        paparazzi.snapshot(name = "mylo_home_production_360x640") {
            MyloTheme {
                MyloViewport(edgeToEdgeHome = true) {
                    Box(Modifier.weight(1f)) { HomeScreen() }
                    BottomBar(NavTab.Home, 0, {}, {}, {}, {})
                }
            }
        }
    }
}

/** A taller phone than the target (Pixel 6: 1080 × 2400 at 420 dpi, 411 × 914 dp), with a camera-cutout status bar. */
class HomeTallPreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, density = Density(420)),
        theme = "Theme.Mylo",
        showSystemUi = false,
    )

    @Test fun homeOnPixel6() {
        paparazzi.snapshot(name = "mylo_home_pixel6_1080x2400") {
            MyloTheme {
                MyloViewport(edgeToEdgeHome = true) {
                    Box(Modifier.weight(1f)) { HomeScreen(statusBarInset = 42.dp) }
                    BottomBar(NavTab.Home, 0, {}, {}, {}, {}, navigationInset = 24.dp)
                }
            }
        }
    }
}

package com.mylo.browser

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
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
                    BottomBar(true, 0, {}, {}, {}, {})
                }
            }
        }
    }

    @Test fun approvedReferencePortrait() {
        // Match the reference's sample state only inside this visual test. The app
        // continues reading its actual Android VPN connection and browser tab count.
        paparazzi.snapshot(name = "mylo_home_reference_state_393x851") {
            MyloTheme {
                MyloViewport(edgeToEdgeHome = true) {
                    Box(Modifier.weight(1f)) {
                        HomeScreen(vpnActive = true, vpnLocation = "Singapore")
                    }
                    BottomBar(true, 1, {}, {}, {}, {})
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
                    BottomBar(true, 0, {}, {}, {}, {})
                }
            }
        }
    }
}

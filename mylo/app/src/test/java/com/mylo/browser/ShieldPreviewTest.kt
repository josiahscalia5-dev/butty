package com.mylo.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.mylo.browser.shield.ServerChoice
import com.mylo.browser.shield.ServerDirectory
import com.mylo.browser.shield.ShieldProblem
import com.mylo.browser.shield.ShieldState
import org.junit.Rule
import org.junit.Test

/**
 * Layoutlib renders of Mylo Shield as this build really shows it: no Shield server is configured, so the
 * screen says "VPN unavailable · Server setup required" and Home's strip does not claim protection.
 * Connected states are only ever captured from a real tunnel on a device.
 */
class ShieldPreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 786, screenHeight = 1702, density = Density.XHIGH),
        theme = "Theme.Mylo",
        showSystemUi = true,
    )

    @Test fun shieldWithoutAServer() {
        paparazzi.snapshot(name = "mylo_shield_server_setup_required_393x851") {
            MyloTheme {
                ShieldScreen(ShieldUi(ShieldState.Error(ShieldProblem.NotConfigured, null), ServerDirectory.NotConfigured,
                    ServerChoice.Fastest, autoConnect = false, systemVpn = null, nowMillis = 0))
            }
        }
    }

    @Test fun homeStripWithoutAServer() {
        paparazzi.snapshot(name = "mylo_home_shield_setup_required_393x851") {
            MyloTheme {
                MyloViewport(edgeToEdgeHome = true) {
                    Box(Modifier.weight(1f)) { HomeScreen(vpnDetail = "Server setup required") }
                    BottomBar(NavTab.Home, 0, {}, {}, {}, {})
                }
            }
        }
    }
}

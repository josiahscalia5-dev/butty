package com.mylo.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test

/** Layoutlib renders of the Private Mode screen as the app draws it, next to the approved reference's size. */
class PrivateModePreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 786, screenHeight = 1702, density = Density.XHIGH),
        theme = "Theme.Mylo",
        showSystemUi = true,
    )

    @Test fun privateModeDefault() {
        paparazzi.snapshot(name = "mylo_private_mode_393x851") {
            MyloTheme { Box(Modifier.fillMaxSize()) { PrivateModeScreen(PrivateModeUi(), statusBarInset = 30.dp, playEntrance = false) } }
        }
    }

    @Test fun privateModeWithSessionState() {
        paparazzi.snapshot(name = "mylo_private_mode_lock_on_burn_off_393x851") {
            MyloTheme {
                Box(Modifier.fillMaxSize()) {
                    PrivateModeScreen(PrivateModeUi(blockTrackers = true, lockTabs = true, burnOnExit = false, trackersBlocked = 12, privateTabs = 2),
                        statusBarInset = 30.dp, playEntrance = false)
                }
            }
        }
    }
}

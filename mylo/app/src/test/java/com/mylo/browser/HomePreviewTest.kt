package com.mylo.browser

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import org.junit.Rule
import org.junit.Test

/** Uses Android Layoutlib to render the actual production Compose screen, not an HTML imitation. */
class HomePreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 2508),
        theme = "Theme.Mylo",
    )

    @Test fun homePortrait() {
        paparazzi.snapshot(name = "mylo_home_android_portrait") {
            MyloTheme {
                MyloViewport {
                    Box(Modifier.weight(1f)) { HomeScreen() }
                    BottomBar(true, 0, {}, {}, {}, {})
                }
            }
        }
    }
}

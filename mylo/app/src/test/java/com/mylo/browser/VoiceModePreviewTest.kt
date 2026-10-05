package com.mylo.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test

/** Layoutlib renders of the Voice Mode screen as the app draws it, at the approved reference's proportions. */
class VoiceModePreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 786, screenHeight = 1702, density = Density.XHIGH),
        theme = "Theme.Mylo",
        showSystemUi = true,
    )

    @Test fun voiceModeIdle() {
        paparazzi.snapshot(name = "mylo_voice_mode_393x851") {
            MyloTheme { Shell { VoiceModeScreen(VoiceModeUi(tabs = 3), statusBarInset = 30.dp) } }
        }
    }

    @Test fun voiceModeAnswering() {
        paparazzi.snapshot(name = "mylo_voice_mode_thinking_393x851") {
            MyloTheme {
                Shell {
                    VoiceModeScreen(VoiceModeUi(phase = VoicePhase.Thinking, tabs = 3, access = AiAccessUi(currentPage = true, otherTabs = true),
                        caption = "Mylo: Basic is \$5 a month for one device; Family is \$12 for up to five."), statusBarInset = 30.dp)
                }
            }
        }
    }

    /** A build without a Mylo AI service: the screen opens as approved and says plainly that answers need setup. */
    @Test fun voiceModeWithoutService() {
        paparazzi.snapshot(name = "mylo_voice_mode_not_connected_393x851") {
            MyloTheme { Shell { VoiceModeScreen(VoiceModeUi(tabs = 3, aiConnected = false), statusBarInset = 30.dp) } }
        }
    }

    /** The same insets VoiceRoute applies: art behind the status bar, controls above the navigation bar. */
    @androidx.compose.runtime.Composable private fun Shell(content: @androidx.compose.runtime.Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFF071430)).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) { content() }
    }
}

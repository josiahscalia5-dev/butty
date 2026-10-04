// Harness-only: Android's BackHandler has no desktop equivalent; previews never press Back.
package androidx.activity.compose

import androidx.compose.runtime.Composable

@Suppress("UNUSED_PARAMETER")
@Composable fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

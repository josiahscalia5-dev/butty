// Harness-only: Android's Font(resId) backed by the project's res/font file.
package androidx.compose.ui.text.font

import androidx.compose.ui.res.resFile

fun Font(resId: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    androidx.compose.ui.text.platform.Font(resFile(resId), weight, style)

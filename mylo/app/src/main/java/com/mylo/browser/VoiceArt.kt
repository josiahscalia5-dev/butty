package com.mylo.browser

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Voice Mode's icons, drawn from the approved reference (design/reference/voice-mode-reference.png). */
internal object VoiceArt {
    private fun outline(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()

    /** Studio microphone (the big Tap to talk button and the Voice Mode selector). */
    val Mic: ImageVector by lazy {
        outline("Mic") {
            moveTo(12f, 3.2f); curveTo(10.3f, 3.2f, 9.2f, 4.4f, 9.2f, 6f); lineTo(9.2f, 11.2f)
            curveTo(9.2f, 12.8f, 10.3f, 14f, 12f, 14f); curveTo(13.7f, 14f, 14.8f, 12.8f, 14.8f, 11.2f)
            lineTo(14.8f, 6f); curveTo(14.8f, 4.4f, 13.7f, 3.2f, 12f, 3.2f); close()
            moveTo(6.2f, 10.8f); curveTo(6.2f, 14.3f, 8.7f, 16.9f, 12f, 16.9f); curveTo(15.3f, 16.9f, 17.8f, 14.3f, 17.8f, 10.8f)
            moveTo(12f, 16.9f); lineTo(12f, 20.8f)
        }
    }

    val Keyboard: ImageVector by lazy {
        outline("Keyboard") {
            moveTo(4.5f, 6.5f); lineTo(19.5f, 6.5f); quadTo(21f, 6.5f, 21f, 8f); lineTo(21f, 16f); quadTo(21f, 17.5f, 19.5f, 17.5f)
            lineTo(4.5f, 17.5f); quadTo(3f, 17.5f, 3f, 16f); lineTo(3f, 8f); quadTo(3f, 6.5f, 4.5f, 6.5f); close()
            listOf(6.5f, 9.5f, 12.5f, 15.5f, 18f).forEach { x -> moveTo(x, 10f); lineTo(x + .1f, 10f) }
            listOf(8f, 11f, 14f, 17f).forEach { x -> moveTo(x, 12.6f); lineTo(x + .1f, 12.6f) }
            moveTo(8.5f, 15f); lineTo(15.5f, 15f)
        }
    }

    val Close: ImageVector by lazy { outline("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }

    val Eye: ImageVector by lazy {
        ImageVector.Builder("Eye", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 5.5f); curveTo(6.8f, 5.5f, 3.2f, 9.3f, 2f, 12f); curveTo(3.2f, 14.7f, 6.8f, 18.5f, 12f, 18.5f)
                curveTo(17.2f, 18.5f, 20.8f, 14.7f, 22f, 12f); curveTo(20.8f, 9.3f, 17.2f, 5.5f, 12f, 5.5f); close()
                moveTo(12f, 8.2f); curveTo(14.1f, 8.2f, 15.8f, 9.9f, 15.8f, 12f); curveTo(15.8f, 14.1f, 14.1f, 15.8f, 12f, 15.8f)
                curveTo(9.9f, 15.8f, 8.2f, 14.1f, 8.2f, 12f); curveTo(8.2f, 9.9f, 9.9f, 8.2f, 12f, 8.2f); close()
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 10.2f); curveTo(13f, 10.2f, 13.8f, 11f, 13.8f, 12f); curveTo(13.8f, 13f, 13f, 13.8f, 12f, 13.8f)
                curveTo(11f, 13.8f, 10.2f, 13f, 10.2f, 12f); curveTo(10.2f, 11f, 11f, 10.2f, 12f, 10.2f); close()
            }
        }.build()
    }

    val Document: ImageVector by lazy {
        ImageVector.Builder("Document", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7f, 2.8f); lineTo(14.2f, 2.8f); lineTo(19.2f, 7.8f); lineTo(19.2f, 19.2f); quadTo(19.2f, 21.2f, 17.2f, 21.2f)
                lineTo(7f, 21.2f); quadTo(5f, 21.2f, 5f, 19.2f); lineTo(5f, 4.8f); quadTo(5f, 2.8f, 7f, 2.8f); close()
            }
            path(stroke = SolidColor(Color(0xFF1B2366)), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
                moveTo(8.2f, 11.5f); lineTo(15.8f, 11.5f); moveTo(8.2f, 14.6f); lineTo(15.8f, 14.6f); moveTo(8.2f, 17.6f); lineTo(13f, 17.6f)
            }
        }.build()
    }

    val Tabs: ImageVector by lazy {
        outline("Tabs") {
            moveTo(8f, 4.5f); lineTo(18.5f, 4.5f); quadTo(19.5f, 4.5f, 19.5f, 5.5f); lineTo(19.5f, 14f)
            moveTo(5.5f, 8f); lineTo(15f, 8f); quadTo(16f, 8f, 16f, 9f); lineTo(16f, 18.5f); quadTo(16f, 19.5f, 15f, 19.5f)
            lineTo(5.5f, 19.5f); quadTo(4.5f, 19.5f, 4.5f, 18.5f); lineTo(4.5f, 9f); quadTo(4.5f, 8f, 5.5f, 8f); close()
        }
    }

    val Clock: ImageVector by lazy {
        outline("Clock") {
            moveTo(12f, 3.5f); curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f); curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f)
            curveTo(7.3f, 20.5f, 3.5f, 16.7f, 3.5f, 12f); curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f); close()
            moveTo(12f, 7.5f); lineTo(12f, 12.2f); lineTo(15.2f, 14f)
        }
    }

    val Pin: ImageVector by lazy {
        ImageVector.Builder("Pin", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 2.5f); curveTo(8.1f, 2.5f, 5.2f, 5.4f, 5.2f, 9.2f); curveTo(5.2f, 14f, 12f, 21.5f, 12f, 21.5f)
                curveTo(12f, 21.5f, 18.8f, 14f, 18.8f, 9.2f); curveTo(18.8f, 5.4f, 15.9f, 2.5f, 12f, 2.5f); close()
                moveTo(12f, 6.6f); curveTo(13.5f, 6.6f, 14.6f, 7.7f, 14.6f, 9.2f); curveTo(14.6f, 10.7f, 13.5f, 11.8f, 12f, 11.8f)
                curveTo(10.5f, 11.8f, 9.4f, 10.7f, 9.4f, 9.2f); curveTo(9.4f, 7.7f, 10.5f, 6.6f, 12f, 6.6f); close()
            }
        }.build()
    }

    val Cart: ImageVector by lazy {
        ImageVector.Builder("Cart", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(9.3f, 17.6f); arcToRelative(1.7f, 1.7f, 0f, true, true, 0f, 3.4f); arcToRelative(1.7f, 1.7f, 0f, true, true, 0f, -3.4f); close()
                moveTo(16.6f, 17.6f); arcToRelative(1.7f, 1.7f, 0f, true, true, 0f, 3.4f); arcToRelative(1.7f, 1.7f, 0f, true, true, 0f, -3.4f); close()
            }
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2.8f, 4f); lineTo(5.3f, 4f); lineTo(7.4f, 14.6f); quadTo(7.6f, 15.6f, 8.6f, 15.6f); lineTo(17.6f, 15.6f)
                quadTo(18.5f, 15.6f, 18.8f, 14.7f); lineTo(20.6f, 8.3f); quadTo(20.8f, 7.4f, 19.9f, 7.4f); lineTo(6f, 7.4f)
            }
        }.build()
    }

    val ShieldCheck: ImageVector by lazy {
        outline("ShieldCheck") {
            moveTo(12f, 2.8f); lineTo(19.2f, 5.6f); lineTo(19.2f, 11.2f); curveTo(19.2f, 15.6f, 16.2f, 19.2f, 12f, 21f)
            curveTo(7.8f, 19.2f, 4.8f, 15.6f, 4.8f, 11.2f); lineTo(4.8f, 5.6f); close()
            moveTo(8.7f, 11.8f); lineTo(11f, 14.1f); lineTo(15.4f, 9.6f)
        }
    }

    val House: ImageVector by lazy {
        ImageVector.Builder("House", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 3.2f); lineTo(21f, 10.6f); quadTo(21.6f, 11.2f, 20.9f, 11.6f); lineTo(19.2f, 11.6f); lineTo(19.2f, 19.6f)
                quadTo(19.2f, 20.8f, 18f, 20.8f); lineTo(14.6f, 20.8f); lineTo(14.6f, 15.4f); lineTo(9.4f, 15.4f); lineTo(9.4f, 20.8f)
                lineTo(6f, 20.8f); quadTo(4.8f, 20.8f, 4.8f, 19.6f); lineTo(4.8f, 11.6f); lineTo(3.1f, 11.6f); quadTo(2.4f, 11.2f, 3f, 10.6f); close()
            }
        }.build()
    }

    /** Paw print after "Your AI browsing buddy". */
    val Paw: ImageVector by lazy {
        ImageVector.Builder("Paw", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                fun oval(cx: Float, cy: Float, rx: Float, ry: Float) {
                    moveTo(cx - rx, cy); curveTo(cx - rx, cy - ry * 1.33f, cx + rx, cy - ry * 1.33f, cx + rx, cy)
                    curveTo(cx + rx, cy + ry * 1.33f, cx - rx, cy + ry * 1.33f, cx - rx, cy); close()
                }
                oval(6f, 10.2f, 2.1f, 2.6f); oval(9.8f, 6.4f, 2.2f, 2.8f); oval(14.2f, 6.4f, 2.2f, 2.8f); oval(18f, 10.2f, 2.1f, 2.6f)
                moveTo(12f, 11.4f); curveTo(15.2f, 11.4f, 18.4f, 15.6f, 17.6f, 18.4f); curveTo(17f, 20.4f, 14.4f, 19.6f, 12f, 19.6f)
                curveTo(9.6f, 19.6f, 7f, 20.4f, 6.4f, 18.4f); curveTo(5.6f, 15.6f, 8.8f, 11.4f, 12f, 11.4f); close()
            }
        }.build()
    }

    /** Translate: 文/A. */
    val Translate: ImageVector by lazy {
        outline("Translate") {
            moveTo(3f, 5.6f); lineTo(12.4f, 5.6f); moveTo(7.7f, 3.4f); lineTo(7.7f, 5.6f)
            moveTo(10.6f, 5.6f); curveTo(9.8f, 9.4f, 7.4f, 12.2f, 3.6f, 14.2f)
            moveTo(5.4f, 8.6f); curveTo(6.6f, 11f, 8.4f, 12.6f, 10.6f, 13.6f)
            moveTo(12.2f, 20.6f); lineTo(15.8f, 11f); lineTo(19.4f, 20.6f); moveTo(13.5f, 17.4f); lineTo(18.1f, 17.4f)
        }
    }
}

/** Explain this page: a cyan magnifier. */
@Composable internal fun MagnifierGlyph(modifier: Modifier) = Canvas(modifier) {
    val s = size.minDimension
    val brush = Brush.linearGradient(listOf(Color(0xFF6FE0FF), Color(0xFF2F8BFF)), Offset.Zero, Offset(s, s))
    drawCircle(brush, s * .27f, Offset(s * .42f, s * .42f), style = Stroke(s * .09f))
    drawLine(brush, Offset(s * .62f, s * .62f), Offset(s * .84f, s * .84f), strokeWidth = s * .11f, cap = StrokeCap.Round)
}

/** Help me cancel: a blue compass. */
@Composable internal fun CompassGlyph(modifier: Modifier) = Canvas(modifier) {
    val s = size.minDimension
    drawCircle(Brush.linearGradient(listOf(Color(0xFF7FC3FF), Color(0xFF3C7FF0)), Offset.Zero, Offset(s, s)), s * .42f, Offset(s / 2, s / 2))
    val needle = Path().apply {
        moveTo(s * .66f, s * .32f); lineTo(s * .55f, s * .55f); lineTo(s * .34f, s * .68f); lineTo(s * .45f, s * .45f); close()
    }
    drawPath(needle, Color(0xFF14246B))
    drawCircle(Color.White, s * .05f, Offset(s / 2, s / 2))
}

/** Is this site safe?: a mint shield with an inner shield. */
@Composable internal fun SafetyShieldGlyph(modifier: Modifier) = Canvas(modifier) {
    val w = size.width * .8f
    val h = size.height
    val left = (size.width - w) / 2
    drawPath(PrivateArt.shield(w, h, left), Brush.verticalGradient(listOf(Color(0xFF8AF3D0), Color(0xFF3FD2A5)), 0f, h))
    drawPath(PrivateArt.shield(w * .5f, h * .5f, left + w * .25f, h * .26f), Color(0xFF117A63))
    drawPath(PrivateArt.shield(w * .5f, h * .5f, left + w * .25f, h * .26f), Color(0x4D000000), style = Stroke(1f))
}

/** A dark disc behind an action's icon. */
@Composable internal fun IconDisc(modifier: Modifier, ring: Color = Color(0x661B2A57)) = Canvas(modifier) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFF172654), Color(0xFF111D42)), Offset(size.width / 2, size.height / 2), size.minDimension / 2), size.minDimension / 2)
    drawCircle(ring, size.minDimension / 2 - 1f, style = Stroke(1.5f))
}

internal fun roundedRect(size: Size, radius: Float) = Path().apply {
    addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
}

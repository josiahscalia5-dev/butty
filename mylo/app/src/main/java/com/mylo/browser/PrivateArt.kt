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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Private Mode's icons, drawn from the approved reference (design/reference/private-mode-reference.png). */
internal object PrivateArt {
    /** Hat and round glasses: Private Mode's mark (bottom navigation, Enter Private Session). */
    val Incognito: ImageVector by lazy {
        ImageVector.Builder("Incognito", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                // Crown with a soft dip in the middle, then the brim.
                moveTo(6.4f, 9.3f); lineTo(7.5f, 4.4f)
                curveTo(7.7f, 3.5f, 8.5f, 3.1f, 9.3f, 3.4f); lineTo(12f, 4.3f); lineTo(14.7f, 3.4f)
                curveTo(15.5f, 3.1f, 16.3f, 3.5f, 16.5f, 4.4f); lineTo(17.6f, 9.3f); close()
                roundRect(2.4f, 9.7f, 21.6f, 11.7f, 1f)
                circle(7.5f, 16.4f, 3.3f)
                circle(16.5f, 16.4f, 3.3f)
                roundRect(10.4f, 15.5f, 13.6f, 16.7f, .6f)
            }
        }.build()
    }

    /** The outlined bin of Burn on Exit. */
    val Trash: ImageVector by lazy {
        ImageVector.Builder("Trash", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3.8f, 6.3f); lineTo(20.2f, 6.3f)
                moveTo(9f, 6.1f); lineTo(9.3f, 4.2f); quadTo(9.45f, 3.4f, 10.3f, 3.4f); lineTo(13.7f, 3.4f); quadTo(14.55f, 3.4f, 14.7f, 4.2f); lineTo(15f, 6.1f)
                moveTo(5.7f, 6.4f); lineTo(6.7f, 19.4f); quadTo(6.85f, 20.9f, 8.3f, 20.9f); lineTo(15.7f, 20.9f); quadTo(17.15f, 20.9f, 17.3f, 19.4f); lineTo(18.3f, 6.4f)
                moveTo(10f, 10.2f); lineTo(10f, 17.2f)
                moveTo(14f, 10.2f); lineTo(14f, 17.2f)
            }
        }.build()
    }

    /** A four-point sparkle path centred on ([cx], [cy]) with radius [r]. */
    fun sparkle(cx: Float, cy: Float, r: Float, waist: Float = .18f) = Path().apply {
        val w = r * waist
        moveTo(cx, cy - r)
        quadraticBezierTo(cx + w, cy - w, cx + r, cy)
        quadraticBezierTo(cx + w, cy + w, cx, cy + r)
        quadraticBezierTo(cx - w, cy + w, cx - r, cy)
        quadraticBezierTo(cx - w, cy - w, cx, cy - r)
        close()
    }

    /** The classic shield outline in a [w] × [h] box. */
    fun shield(w: Float, h: Float, left: Float = 0f, top: Float = 0f) = Path().apply {
        fun x(f: Float) = left + f * w
        fun y(f: Float) = top + f * h
        moveTo(x(.5f), y(.015f))
        cubicTo(x(.63f), y(.075f), x(.80f), y(.105f), x(.93f), y(.115f))
        quadraticBezierTo(x(.985f), y(.12f), x(.985f), y(.17f))
        lineTo(x(.985f), y(.47f))
        cubicTo(x(.985f), y(.73f), x(.79f), y(.89f), x(.54f), y(.985f))
        quadraticBezierTo(x(.5f), y(1f), x(.46f), y(.985f))
        cubicTo(x(.21f), y(.89f), x(.015f), y(.73f), x(.015f), y(.47f))
        lineTo(x(.015f), y(.17f))
        quadraticBezierTo(x(.015f), y(.12f), x(.07f), y(.115f))
        cubicTo(x(.20f), y(.105f), x(.37f), y(.075f), x(.5f), y(.015f))
        close()
    }

    /** Hat and glasses for the big shield, in white, inside a [w] × [h] shield box. */
    fun DrawScope.incognitoOnShield(w: Float, h: Float, color: Color = Color.White) {
        val crown = Path().apply {
            moveTo(.335f * w, .43f * h); lineTo(.375f * w, .27f * h)
            cubicTo(.385f * w, .235f * h, .415f * w, .222f * h, .45f * w, .232f * h)
            lineTo(.5f * w, .248f * h); lineTo(.55f * w, .232f * h)
            cubicTo(.585f * w, .222f * h, .615f * w, .235f * h, .625f * w, .27f * h)
            lineTo(.665f * w, .43f * h); close()
        }
        drawPath(crown, color)
        drawRoundRect(color, Offset(.215f * w, .425f * h), Size(.57f * w, .055f * h), CornerRadius(.03f * h))
        drawCircle(color, .108f * w, Offset(.385f * w, .585f * h))
        drawCircle(color, .108f * w, Offset(.615f * w, .585f * h))
    }
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}

private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
    moveTo(l + radius, t); lineTo(r - radius, t); arcTo(radius, radius, 0f, false, true, r, t + radius)
    lineTo(r, b - radius); arcTo(radius, radius, 0f, false, true, r - radius, b)
    lineTo(l + radius, b); arcTo(radius, radius, 0f, false, true, l, b - radius)
    lineTo(l, t + radius); arcTo(radius, radius, 0f, false, true, l + radius, t); close()
}

/** "No history saved": a mint clock with a small no-entry badge. */
@Composable internal fun NoHistoryIcon(modifier: Modifier) = Canvas(modifier) {
    val s = size.minDimension
    val c = Offset(s * .45f, s * .45f)
    drawCircle(Brush.linearGradient(listOf(Color(0xFF6FEAC0), Color(0xFF22B48A)), Offset(0f, 0f), Offset(s, s)), s * .43f, c)
    val hand = Color(0xFF0D3F36)
    drawLine(hand, c, Offset(c.x, s * .19f), strokeWidth = s * .085f, cap = StrokeCap.Round)
    drawLine(hand, c, Offset(s * .64f, c.y), strokeWidth = s * .085f, cap = StrokeCap.Round)
    val badge = Offset(s * .80f, s * .80f)
    drawCircle(Color(0xFF0E1D3C), s * .2f, badge)
    drawCircle(Color(0xFF3FD3A2), s * .155f, badge, style = Stroke(s * .06f))
    drawLine(Color(0xFF3FD3A2), Offset(badge.x - s * .1f, badge.y + s * .1f), Offset(badge.x + s * .1f, badge.y - s * .1f), strokeWidth = s * .06f, cap = StrokeCap.Round)
}

/** "Block trackers": a violet shield with a white sparkle. */
@Composable internal fun TrackerShieldIcon(modifier: Modifier) = Canvas(modifier) {
    val w = size.width * .82f
    val h = size.height
    val left = (size.width - w) / 2
    drawPath(PrivateArt.shield(w, h, left), Brush.verticalGradient(listOf(Color(0xFFA897FF), Color(0xFF6A55F0)), 0f, h))
    clipRect(left = size.width / 2) { drawPath(PrivateArt.shield(w, h, left), Color(0x1F1B0F7A)) }
    drawPath(PrivateArt.sparkle(size.width / 2, h * .47f, h * .26f, .22f), Color.White)
}

/** "Lock tabs": two stacked blue tabs and a small padlock. */
@Composable internal fun LockTabsIcon(modifier: Modifier) = Canvas(modifier) {
    val s = size.minDimension
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF3F7DF0), Color(0xFF2953C8)), Offset.Zero, Offset(s, s)),
        Offset(s * .02f, s * .0f), Size(s * .66f, s * .68f), CornerRadius(s * .14f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF79B9FF), Color(0xFF4C8EF5)), Offset(s * .15f, s * .1f), Offset(s * .8f, s * .85f)),
        Offset(s * .14f, s * .12f), Size(s * .66f, s * .7f), CornerRadius(s * .14f))
    val lock = Color(0xFFB5DAFF)
    drawArc(lock, 180f, 180f, false, Offset(s * .63f, s * .5f), Size(s * .27f, s * .28f), style = Stroke(s * .07f))
    drawRoundRect(lock, Offset(s * .57f, s * .63f), Size(s * .4f, s * .35f), CornerRadius(s * .07f))
    drawCircle(Color(0xFF2953C8), s * .045f, Offset(s * .77f, s * .79f))
}

/** "Burn session on exit": a coral drop holding a white flame. */
@Composable internal fun BurnIcon(modifier: Modifier) = Canvas(modifier) {
    val w = size.width
    val h = size.height
    val drop = Path().apply {
        moveTo(w * .5f, h * .02f)
        cubicTo(w * .66f, h * .2f, w * .95f, h * .44f, w * .95f, h * .64f)
        cubicTo(w * .95f, h * .86f, w * .74f, h * .99f, w * .5f, h * .99f)
        cubicTo(w * .26f, h * .99f, w * .05f, h * .86f, w * .05f, h * .64f)
        cubicTo(w * .05f, h * .44f, w * .34f, h * .2f, w * .5f, h * .02f)
        close()
    }
    drawPath(drop, Brush.verticalGradient(listOf(Color(0xFFFF7B7E), Color(0xFFE2414D)), 0f, h))
    val flame = Path().apply {
        moveTo(w * .5f, h * .36f)
        cubicTo(w * .58f, h * .48f, w * .72f, h * .58f, w * .70f, h * .73f)
        cubicTo(w * .68f, h * .86f, w * .59f, h * .89f, w * .5f, h * .89f)
        cubicTo(w * .40f, h * .89f, w * .31f, h * .84f, w * .30f, h * .73f)
        cubicTo(w * .29f, h * .64f, w * .36f, h * .58f, w * .40f, h * .53f)
        cubicTo(w * .41f, h * .6f, w * .44f, h * .64f, w * .47f, h * .65f)
        cubicTo(w * .45f, h * .55f, w * .46f, h * .45f, w * .5f, h * .36f)
        close()
        fillType = PathFillType.NonZero
    }
    drawPath(flame, Color.White)
}

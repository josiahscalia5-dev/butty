package com.mylo.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the Private Mode screen shows. Every value comes from the real private session and its settings. */
data class PrivateModeUi(
    val blockTrackers: Boolean = true,
    val lockTabs: Boolean = false,
    val burnOnExit: Boolean = true,
    /** Requests blocked in this private session (never stored). */
    val trackersBlocked: Int = 0,
    val privateTabs: Int = 0,
)

enum class PrivateFeature { NoHistory, BlockTrackers, LockTabs, BurnOnExit }
enum class PrivateNav { Home, Search, Tabs, Mylo }

// Palette sampled from the approved reference.
internal val PrivateNight = Color(0xFF071532)
private val PrivateInk = Color(0xFFF4F3FB)
private val PrivateMuted = Color(0xFFB7BCD9)
private val PrivateHand = Color(0xFFD3C9FF)
private val ButtonInk = Color(0xFF1D176B)
private val NightRounded = FontFamily(Font(R.font.nunito_black, FontWeight.Black))
private val Handwriting = FontFamily(Font(R.font.kalam_regular, FontWeight.Normal))

/** The reference is 941 px wide; every size on this screen is measured from it and scaled to the device width. */
private const val REF_W = 941f
/** The hero art covers the reference's top 468 px (see tools/private-art). */
private const val REF_ART_H = 468f
/** The sunglasses corgi's layer inside the art, in reference px. */
private const val CORGI_X = 521f
private const val CORGI_Y = 64f
private const val CORGI_W = 289f
private const val CORGI_H = 355f

/** Heights (reference px) of the screen's parts, top to bottom, and the gaps between them. */
private const val REF_HERO = 471f
private const val REF_CARD = 261f
private const val REF_ROW = 106.5f
private const val REF_ENTER = 105f
private const val REF_BURN = 95f
private const val REF_NAV = 126f

/**
 * Vertical layout for one screen. Sizes follow the reference at the device's width; taller phones (the
 * reference is a 9:16 screen) give the controls up to 18% more height and share the rest between the gaps,
 * so nothing is stretched. Short phones scroll.
 */
internal class PrivateLayout(width: Dp, height: Dp, statusBar: Dp) {
    /** dp per reference px. */
    val k = width.value / REF_W
    /** Extra room at the top when the status bar is taller than the reference's. */
    val shift = max(0f, statusBar.value - 24f).dp
    val grow: Float
    val row: Dp
    val gaps: List<Dp>

    init {
        val naturalGaps = listOf(19f, 12.5f, 12.5f, 12.5f, 23f, 17f, 23f).map { it * k }
        val weights = listOf(1f, .4f, .4f, .4f, 1.4f, .8f, 1.4f)
        val rowAt = { g: Float -> max(44f, REF_ROW * k * g) }
        val controlsAt = { g: Float -> (REF_CARD + REF_ENTER + REF_BURN + REF_NAV) * k * g + 4 * rowAt(g) }
        val navBottom = 12f * k
        val natural = REF_HERO * k + shift.value + controlsAt(1f) + naturalGaps.sum() + navBottom
        val extra = height.value - natural
        grow = if (extra > 0) min(1.18f, 1f + extra * .55f / controlsAt(1f)) else 1f
        var rest = max(0f, height.value - (REF_HERO * k + shift.value + controlsAt(grow) + naturalGaps.sum() + navBottom))
        val share = naturalGaps.indices.map { naturalGaps[it] * weights[it] }
        val sum = share.sum()
        val added = share.indices.map { i -> min(rest * share[i] / sum, naturalGaps[i] * 2.4f) }
        rest -= added.sum()
        gaps = naturalGaps.indices.map { i -> (naturalGaps[i] + added[i] + if (i == naturalGaps.lastIndex) rest else 0f).dp }
        row = rowAt(grow).dp
    }

    fun px(v: Float) = (v * k).dp
    fun tall(v: Float) = (v * k * grow).dp
    /** Text scales with the device width, like the reference's artwork. */
    fun sp(v: Float): TextUnit = (v * (k * REF_W / 392.7f)).sp
}

/**
 * The approved Private Mode screen: night lake, the sunglasses corgi on the moon, Private Mode status, the four
 * protections, Enter Private Session and Burn on Exit, and the bottom navigation with Mylo selected.
 * [playEntrance]: the corgi settles onto the moon, the sunglasses catch the light once, the shield pulses and
 * Active fades in. It honours Android's animation scale (instant when animations are off).
 */
@Composable fun PrivateModeScreen(
    ui: PrivateModeUi,
    onFeature: (PrivateFeature) -> Unit = {},
    onEnter: () -> Unit = {},
    onBurnNow: () -> Unit = {},
    onSettings: () -> Unit = {},
    onNav: (PrivateNav) -> Unit = {},
    statusBarInset: Dp? = null,
    playEntrance: Boolean = true,
) {
    val statusBar = statusBarInset ?: WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    val entrance = rememberPrivateEntrance(playEntrance)
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(PrivateNight).testTag("private-mode-screen")) {
            val layout = PrivateLayout(maxWidth, maxHeight, statusBar)
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    PrivateHero(layout, entrance, onSettings)
                    StatusCard(layout, ui, entrance)
                    Spacer(Modifier.height(layout.gaps[0]))
                    FeatureRow(layout, "No history saved", "Pages you visit won’t be stored.", null, { onFeature(PrivateFeature.NoHistory) }) { NoHistoryIcon(it) }
                    Spacer(Modifier.height(layout.gaps[1]))
                    FeatureRow(layout, "Block trackers", "Helps stop websites from following you.", if (ui.blockTrackers) null else "Off",
                        { onFeature(PrivateFeature.BlockTrackers) }) { TrackerShieldIcon(it) }
                    Spacer(Modifier.height(layout.gaps[2]))
                    FeatureRow(layout, "Lock tabs", "Keep your private tabs separate.", if (ui.lockTabs) "On" else null,
                        { onFeature(PrivateFeature.LockTabs) }) { LockTabsIcon(it) }
                    Spacer(Modifier.height(layout.gaps[3]))
                    FeatureRow(layout, "Burn session on exit", "Clear cookies, data and tabs automatically.", if (ui.burnOnExit) null else "Off",
                        { onFeature(PrivateFeature.BurnOnExit) }) { BurnIcon(it) }
                    Spacer(Modifier.height(layout.gaps[4]))
                    EnterButton(layout, onEnter)
                    Spacer(Modifier.height(layout.gaps[5]))
                    BurnButton(layout, onBurnNow)
                    Spacer(Modifier.height(layout.gaps[6]))
                }
                PrivateBottomBar(layout, ui.privateTabs, onNav)
                Spacer(Modifier.height(layout.px(12f)))
            }
        }
    }
}

/** Entrance animation progress, all ending at 1. */
internal class PrivateEntrance(val corgiDrop: Animatable<Float, *>, val corgiAlpha: Animatable<Float, *>, val glint: Animatable<Float, *>,
                               val pulse: Animatable<Float, *>, val active: Animatable<Float, *>)

@Composable private fun rememberPrivateEntrance(play: Boolean): PrivateEntrance {
    val e = remember {
        PrivateEntrance(Animatable(if (play) -18f else 0f), Animatable(if (play) 0f else 1f), Animatable(if (play) 0f else 1f),
            Animatable(if (play) 0f else 1f), Animatable(if (play) 0f else 1f))
    }
    if (play) LaunchedEffect(Unit) {
        launch { e.corgiAlpha.animateTo(1f, tween(220, easing = LinearEasing)) }
        launch { e.corgiDrop.animateTo(0f, spring(dampingRatio = .52f, stiffness = 240f)) }
        launch { delay(640); e.glint.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
        launch { delay(260); e.pulse.animateTo(1f, tween(1500, easing = LinearEasing)) }
        launch { delay(420); e.active.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    }
    return e
}

/** The night scene, the corgi layer, the gear, the wordmark, the titles and the handwritten notes. */
@Composable private fun PrivateHero(layout: PrivateLayout, e: PrivateEntrance, onSettings: () -> Unit) {
    val scene = ImageBitmap.imageResource(R.drawable.private_hero_scene)
    val corgi = ImageBitmap.imageResource(R.drawable.private_hero_corgi)
    val lenses = ImageBitmap.imageResource(R.drawable.private_hero_lenses)
    val k = layout.k
    val shift = layout.shift
    fun px(v: Float) = layout.px(v)
    Box(Modifier.fillMaxWidth().height(px(REF_HERO) + shift).drawBehind {
        val top = shift.toPx()
        val artH = REF_ART_H * k * density
        if (top > 0) drawRect(Color(0xFF0A1843), size = Size(size.width, top + 1))
        drawImage(scene, dstOffset = IntOffset(0, top.roundToInt()), dstSize = IntSize(size.width.roundToInt(), artH.roundToInt()), filterQuality = FilterQuality.High)
        // The art's lower edge melts into the page behind the status card's rounded corners.
        val fade = 26.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, PrivateNight), top + artH - fade, top + artH), Offset(0f, top + artH - fade), Size(size.width, fade))
        drawRect(PrivateNight, Offset(0f, top + artH - 1), Size(size.width, size.height - top - artH + 2 + 20.dp.toPx()))
    }) {
        Box(Modifier.matchParentSize().clearAndSetSemantics { contentDescription = "Mylo the corgi in sunglasses, sitting on the moon above a night lake" })
        // The corgi settles onto the moon; the lenses catch the light once.
        Box(Modifier.offset(x = px(CORGI_X), y = shift + px(CORGI_Y)).size(px(CORGI_W), px(CORGI_H))
            .graphicsLayer {
                val drop = e.corgiDrop.value
                translationY = drop.dp.toPx()
                val squash = (drop.coerceAtLeast(0f) * .014f).coerceAtMost(.04f)
                scaleY = 1f - squash; scaleX = 1f + squash * .6f
                transformOrigin = TransformOrigin(.5f, .96f)
                alpha = e.corgiAlpha.value
            }) {
            Image(corgi, null, Modifier.matchParentSize(), filterQuality = FilterQuality.High)
            val g = e.glint.value
            if (g > 0f && g < 1f) Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                drawImage(lenses, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.High)
                val x = -size.width * .35f + g * size.width * 1.5f
                val band = size.width * .16f
                drawRect(Brush.linearGradient(0f to Color.Transparent, .5f to Color.White.copy(alpha = .78f * (1f - (2 * g - 1) * (2 * g - 1)) + .1f), 1f to Color.Transparent,
                    start = Offset(x - band, 0f), end = Offset(x + band, size.height * .32f)), blendMode = BlendMode.SrcIn)
            }
        }
        // Gear: Private Mode settings.
        Box(Modifier.offset(x = px(868f) - 24.dp, y = shift + px(106f) - 24.dp).size(48.dp).clip(CircleShape)
            .clickable(onClickLabel = "Private Mode settings", role = Role.Button, onClick = onSettings).testTag("private-settings"),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(px(74f)).background(Color(0xD91D2758), CircleShape).border(1.dp, Color(0x1FFFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Settings, "Settings", tint = Color.White, modifier = Modifier.size(px(40f)))
            }
        }
        Image(rememberVectorPainter(HomeArt.Wordmark), "Mylo",
            Modifier.offset(x = px(64f), y = shift + px(86f)).width(px(260f)).aspectRatio(HomeArt.WORDMARK_ASPECT))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = PrivateInk)) { append("Private ") }
            withStyle(SpanStyle(brush = Brush.horizontalGradient(listOf(Color(0xFFD9CEFF), Color(0xFFB4A3FF))))) { append("Mode") }
        }, fontFamily = NightRounded, fontWeight = FontWeight.Black, fontSize = layout.sp(25.9f), maxLines = 1, softWrap = false,
            modifier = Modifier.offset(x = px(63f), y = shift + px(193f)).semantics { heading() })
        Text("Browse without a trace.", fontSize = layout.sp(14.7f), fontWeight = FontWeight.Medium, color = Color(0xFFDADCF3), maxLines = 1, softWrap = false,
            modifier = Modifier.offset(x = px(64f), y = shift + px(271f)))
        // Handwritten notes, as in the reference.
        Column(Modifier.offset(x = px(68f), y = shift + px(356f)).graphicsLayer { rotationZ = -9.3f; transformOrigin = TransformOrigin(0f, .5f) }) {
            HandLine("Same curious you.", layout.sp(11f), Modifier.height(px(29f)))
            HandLine("Just more private.", layout.sp(10.9f), Modifier.height(px(29f)).padding(start = px(20f)))
        }
        Column(Modifier.offset(x = px(818f), y = shift + px(190f))) {
            listOf("Good", "secrets", "brighter", "tomorrows").forEachIndexed { i, line ->
                HandLine(line, layout.sp(9.1f), Modifier.height(px(27f)).padding(start = px(3f * i)).graphicsLayer { rotationZ = -13.5f; transformOrigin = TransformOrigin(0f, .5f) })
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val y0 = shift.toPx()
            fun p(x: Float, y: Float) = Offset(x * k * density, y0 + y * k * density)
            val stroke = Stroke(2.6f * k * density, cap = StrokeCap.Round)
            drawPath(Path().apply { moveTo(p(186f, 416f).x, p(186f, 416f).y); quadraticBezierTo(p(214f, 400f).x, p(214f, 400f).y, p(253f, 395f).x, p(253f, 395f).y) }, PrivateHand, style = Stroke(4f * k * density, cap = StrokeCap.Round))
            drawPath(Path().apply { moveTo(p(821f, 338f).x, p(821f, 338f).y); quadraticBezierTo(p(842f, 322f).x, p(842f, 322f).y, p(867f, 317f).x, p(867f, 317f).y) }, PrivateHand, style = stroke)
            // Outline heart.
            val c = p(889f, 309f)
            val r = 13f * k * density
            rotate(-8f, c) {
                drawPath(Path().apply {
                    moveTo(c.x, c.y + r * .95f)
                    cubicTo(c.x - r * 1.35f, c.y + r * .1f, c.x - r * .9f, c.y - r * 1.05f, c.x, c.y - r * .35f)
                    cubicTo(c.x + r * .9f, c.y - r * 1.05f, c.x + r * 1.35f, c.y + r * .1f, c.x, c.y + r * .95f)
                }, PrivateHand, style = stroke)
            }
        }
    }
}

/** One handwritten line: Kalam, slanted, with a hairline stroke so its weight matches the reference's pen. */
@Composable private fun HandLine(text: String, size: TextUnit, modifier: Modifier) {
    val style = TextStyle(fontFamily = Handwriting, fontSize = size, color = PrivateHand, textGeometricTransform = TextGeometricTransform(skewX = -.14f))
    Box(modifier) {
        val unclipped = Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
        Text(text, style = style, maxLines = 1, softWrap = false, modifier = unclipped)
        Text(text, style = style.copy(drawStyle = Stroke(size.value * .02f * LocalDensity.current.density)), maxLines = 1, softWrap = false, modifier = unclipped)
    }
}

/** PRIVATE MODE · Active, with the violet privacy shield. */
@Composable private fun StatusCard(layout: PrivateLayout, ui: PrivateModeUi, e: PrivateEntrance) {
    fun px(v: Float) = layout.px(v)
    val shape = RoundedCornerShape(px(46f))
    val pulse = e.pulse.value
    // Two soft pulses, then rest.
    val wave = if (pulse <= 0f || pulse >= 1f) 0f else kotlin.math.sin(pulse * 2 * Math.PI.toFloat()).let { it * it }
    val description = if (ui.burnOnExit) "Your browsing is private and won’t be saved on this device."
        else "Nothing is saved to history. Private tabs stay until you burn them."
    Box(Modifier.padding(start = px(45f), end = px(REF_W - 896f)).fillMaxWidth().height(layout.tall(REF_CARD))
        .shadow(px(30f), shape, ambientColor = Color(0xFF5B4BFF), spotColor = Color(0xFF6B5BFF))
        .clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xFF2A2D82), Color(0xFF1C2466), Color(0xFF111F52)), Offset.Zero, Offset(900f * layout.k * 2.6f, 300f * layout.k * 2.6f)))
        .border(px(3.2f), Brush.verticalGradient(listOf(Color(0xFF5A5CB8), Color(0xFF3A43A0), Color(0xFF2B3787))), shape)
        .semantics(mergeDescendants = true) { stateDescription = "Active" }
        .testTag("private-status")) {
        // Shield with its orbit and sparkles.
        Box(Modifier.align(Alignment.CenterStart).offset(x = px(55f)).size(px(260f), px(200f))) {
            Canvas(Modifier.matchParentSize()) {
                val k = layout.k * density
                val glowCenter = Offset(133f * k, 100f * k)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF8E7BFF).copy(alpha = .30f + .35f * wave), Color.Transparent), glowCenter, 120f * k * (1f + .08f * wave)), 120f * k * (1f + .08f * wave), glowCenter)
                // Orbit: a thin tilted ellipse, its far side passing behind the shield.
                rotate(11f, Offset(130f * k, 100f * k)) {
                    drawOval(Brush.horizontalGradient(listOf(Color(0xFFB9A8FF), Color(0x66A796FF), Color(0xFFB9A8FF))), Offset(2f * k, 72f * k), Size(254f * k, 58f * k), style = Stroke(2.4f * k))
                }
            }
            val scale = 1f + .045f * wave
            Canvas(Modifier.offset(x = px(52f), y = px(7f)).size(px(161f), px(187f)).graphicsLayer { scaleX = scale; scaleY = scale }) {
                val w = size.width; val h = size.height
                val path = PrivateArt.shield(w, h)
                drawPath(path, Brush.verticalGradient(listOf(Color(0xFFB7A9FF), Color(0xFF8774FA), Color(0xFF6A55F2), Color(0xFF7867F8)), 0f, h))
                clipRect(left = w / 2) { drawPath(path, Color(0x26231089)) }
                drawPath(path, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x00FFFFFF)), 0f, h * .5f), style = Stroke(w * .018f))
                with(PrivateArt) { incognitoOnShield(w, h) }
            }
            Canvas(Modifier.matchParentSize()) {
                val k = layout.k * density
                // The near side of the orbit passes in front of the shield's lower half.
                rotate(11f, Offset(130f * k, 100f * k)) {
                    clipRect(top = 101f * k) {
                        drawOval(Color(0xFFB9A8FF), Offset(2f * k, 72f * k), Size(254f * k, 58f * k), style = Stroke(2.4f * k))
                    }
                }
                listOf(Triple(27f, 22f, 7f), Triple(242f, 74f, 8f), Triple(30f, 141f, 11.5f)).forEach { (x, y, r) ->
                    drawPath(PrivateArt.sparkle(x * k, y * k, r * k, .2f), Color(0xFFE4DDFF))
                }
            }
        }
        Column(Modifier.align(Alignment.CenterStart).padding(start = px(346f)).offset(y = px(18f))) {
            Text("PRIVATE MODE", fontSize = layout.sp(7.1f), letterSpacing = layout.sp(2.75f), fontWeight = FontWeight.Medium, color = Color(0xFFC8CBE7), maxLines = 1, softWrap = false,
                modifier = Modifier.offset(y = px(5f)).padding(bottom = px(1f)))
            Row(Modifier.offset(y = px(-4f)).graphicsLayer { alpha = e.active.value }, verticalAlignment = Alignment.CenterVertically) {
                Text("Active", fontFamily = NightRounded, fontWeight = FontWeight.Black, fontSize = layout.sp(35.8f), color = PrivateInk, maxLines = 1, softWrap = false,
                    modifier = Modifier.graphicsLayer { scaleX = .84f; transformOrigin = TransformOrigin(0f, .5f) }.testTag("private-active"))
                Box(Modifier.offset(x = px(-12f)).padding(top = px(4f)).size(px(25f)).drawBehind {
                    drawCircle(Color(0xFF5EE6AB).copy(alpha = .35f * e.active.value), size.minDimension * .95f)
                    drawCircle(Color(0xFF5EE6AB), size.minDimension / 2)
                })
            }
            Text(description, fontSize = layout.sp(10.7f), lineHeight = layout.sp(13.6f), color = Color(0xFFD4D7EF), modifier = Modifier.offset(y = px(-24f)).width(px(410f)))
        }
    }
}

@Composable private fun FeatureRow(layout: PrivateLayout, title: String, subtitle: String, state: String?, onClick: () -> Unit, icon: @Composable (Modifier) -> Unit) {
    fun px(v: Float) = layout.px(v)
    val shape = RoundedCornerShape(px(40f))
    val source = remember { MutableInteractionSource() }
    Row(Modifier.padding(start = px(44f), end = px(REF_W - 897f)).fillMaxWidth().height(layout.row).clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xFF15244A), Color(0xFF112043), Color(0xFF0F1D3E))))
        .border(1.dp, Brush.verticalGradient(listOf(Color(0xFF26355E), Color(0xFF16233F))), shape)
        .clickable(source, LocalIndication.current, role = Role.Button, onClickLabel = "Open $title", onClick = onClick)
        .semantics { if (state != null) stateDescription = state }
        .testTag("private-row-${title.lowercase().replace(' ', '-')}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = px(53f)).size(px(66f)), contentAlignment = Alignment.Center) { icon(Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).padding(start = px(74f), end = 8.dp)) {
            Text(title, fontSize = layout.sp(11.2f), fontWeight = FontWeight.SemiBold, color = PrivateInk, maxLines = 1)
            Text(subtitle, fontSize = layout.sp(9.5f), color = PrivateMuted, maxLines = 2, modifier = Modifier.padding(top = px(5f)))
        }
        if (state != null) Text(state, fontSize = layout.sp(9f), fontWeight = FontWeight.Medium, color = Color(0xFFC9BEFF),
            modifier = Modifier.padding(end = 6.dp).background(Color(0x33A996FF), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFC3C8E3), modifier = Modifier.padding(end = px(34f)).size(px(52f)))
    }
}

@Composable private fun EnterButton(layout: PrivateLayout, onClick: () -> Unit) {
    fun px(v: Float) = layout.px(v)
    val source = remember { MutableInteractionSource() }
    Box(Modifier.padding(start = px(42f), end = px(REF_W - 898f)).fillMaxWidth().height(layout.tall(REF_ENTER))
        .shadow(px(34f), CircleShape, ambientColor = Color(0xFF9C86FF), spotColor = Color(0xFFA894FF))
        .clip(CircleShape)
        .background(Brush.verticalGradient(listOf(Color(0xFFD9CCFF), Color(0xFFC6B5FE), Color(0xFFB39EFC))))
        .border(1.dp, Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color(0x26FFFFFF))), CircleShape)
        .clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick)
        .testTag("private-enter")) {
        Row(Modifier.align(Alignment.CenterStart).padding(start = px(218f)), verticalAlignment = Alignment.CenterVertically) {
            Icon(PrivateArt.Incognito, null, tint = ButtonInk, modifier = Modifier.size(px(63f)))
            Text("Enter Private Session", fontSize = layout.sp(13.45f), fontWeight = FontWeight.ExtraBold, color = ButtonInk, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(start = px(26f)))
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = px(39f)).size(px(66f)).background(Color(0xFF231B70), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(px(34f)))
        }
    }
}

@Composable private fun BurnButton(layout: PrivateLayout, onClick: () -> Unit) {
    fun px(v: Float) = layout.px(v)
    val source = remember { MutableInteractionSource() }
    Box(Modifier.padding(start = px(40f), end = px(REF_W - 900f)).fillMaxWidth().height(layout.tall(REF_BURN))
        .clip(CircleShape)
        .background(Brush.verticalGradient(listOf(Color(0xFF0D1B3B), Color(0xFF0A1735))))
        .border(px(2.6f), Brush.verticalGradient(listOf(Color(0xFF4A50A2), Color(0xFF353D88))), CircleShape)
        .clickable(source, LocalIndication.current, role = Role.Button, onClickLabel = "Clear everything now", onClick = onClick)
        .testTag("private-burn-now")) {
        Row(Modifier.align(Alignment.CenterStart).padding(start = px(270f)).offset(y = px(-5f)), verticalAlignment = Alignment.CenterVertically) {
            Icon(PrivateArt.Trash, null, tint = Color.White, modifier = Modifier.size(px(52f)))
            Column(Modifier.padding(start = px(27f))) {
                Text("Burn on Exit", fontSize = layout.sp(10.4f), fontWeight = FontWeight.SemiBold, color = PrivateInk, maxLines = 1, softWrap = false)
                Text("Clear everything now", fontSize = layout.sp(9.05f), color = PrivateMuted, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = px(3f)))
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFC3C8E3),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = px(36f)).size(px(52f)))
    }
}

/** Home · Search · Tabs · Mylo, with Mylo (Private Mode) selected. */
@Composable internal fun PrivateBottomBar(layout: PrivateLayout, tabs: Int, onNav: (PrivateNav) -> Unit, selected: PrivateNav? = PrivateNav.Mylo) {
    fun px(v: Float) = layout.px(v)
    val shape = RoundedCornerShape(px(42f))
    Row(Modifier.padding(start = px(38f), end = px(REF_W - 903f)).fillMaxWidth().height(layout.tall(REF_NAV)).clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF14244A), Color(0xFF101F40))))
        .border(1.dp, Color(0xFF1C2C50), shape).testTag("private-bottom-nav"), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun item(nav: PrivateNav, label: String, icon: @Composable (Color) -> Unit) {
            val on = nav == selected
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(px(36f))).selectable(on, role = Role.Tab) { onNav(nav) }
                .testTag("private-nav-${label.lowercase()}"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(px(126f), px(66f)).background(if (on) Color(0xFFD1C2FE) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    icon(if (on) Color(0xFF231C6E) else Color(0xFFD6D9EE))
                }
                Text(label, fontSize = layout.sp(9.9f), fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) Color(0xFFD3C8FF) else Color(0xFFD3D6EA), maxLines = 1, modifier = Modifier.padding(top = px(4f)))
            }
        }
        item(PrivateNav.Home, "Home") { Icon(Icons.Outlined.Home, null, tint = it, modifier = Modifier.size(px(54f))) }
        item(PrivateNav.Search, "Search") { Icon(HomeArt.SearchThin, null, tint = it, modifier = Modifier.size(px(56f))) }
        item(PrivateNav.Tabs, "Tabs") {
            Box(Modifier.size(px(44f), px(46f)).border(px(4f), it, RoundedCornerShape(px(10f))), contentAlignment = Alignment.Center) {
                Text(if (tabs > 99) "99+" else tabs.toString(), fontSize = layout.sp(8.8f), color = it, fontWeight = FontWeight.Medium)
            }
        }
        item(PrivateNav.Mylo, "Mylo") { Icon(PrivateArt.Incognito, null, tint = it, modifier = Modifier.size(px(54f))) }
    }
}

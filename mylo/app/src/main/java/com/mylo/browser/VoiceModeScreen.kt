package com.mylo.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where a voice conversation is. */
enum class VoicePhase { Idle, Listening, Thinking, Speaking }

/** What Mylo AI may read, exactly as the switchboard enforces it. */
data class AiAccessUi(val currentPage: Boolean = true, val otherTabs: Boolean = false, val history: Boolean = false, val location: Boolean = false)

enum class AiSource(val label: String) { CurrentPage("Current Page"), OtherTabs("Other Tabs"), History("History"), Location("Location") }

enum class VoiceAction(val title: String, val subtitle: String) {
    Explain("Explain this page", "Give me a simple summary"),
    FindPricing("Find the pricing section", "Scroll there for me"),
    HelpCancel("Help me cancel", "Guide me step by step"),
    CompareTabs("Compare this with\nmy other tab", "Show key differences"),
    SiteSafety("Is this site safe?", "Check for red flags"),
    Translate("Translate this page", "To another language"),
}

enum class VoiceNav { Home, Search, Mylo, Tabs, Private }

/** Everything the Voice Mode screen shows; every value comes from the real voice session and switchboard. */
data class VoiceModeUi(
    val phase: VoicePhase = VoicePhase.Idle,
    /** 0..1: the live microphone level while listening, Mylo's playback level while speaking. */
    val level: Float = 0f,
    /** The microphone is capturing audio right now (shown on screen whenever true). */
    val micLive: Boolean = false,
    val access: AiAccessUi = AiAccessUi(),
    val tabs: Int = 0,
    /** The latest caption ("You: …" / "Mylo: …"), shown under the title during a conversation. */
    val caption: String? = null,
    /** A plain-language problem to show instead of the title (no microphone permission, no AI service…). */
    val problem: String? = null,
)

private val VoiceNight = Color(0xFF071430)
private val VoiceInk = Color(0xFFF3F4FC)
private val VoiceMuted = Color(0xFFB9BEDA)
private val VoiceHand = Color(0xFFD7CCFF)
private val Mint = Color(0xFF52E0AE)
private val VoiceHandwriting = FontFamily(Font(R.font.kalam_regular, FontWeight.Normal))

private const val VREF_W = 941f
private const val VREF_ART_H = 490f
private const val VCORGI_X = 251f
private const val VCORGI_Y = 127f
private const val VCORGI_W = 445f
private const val VCORGI_H = 363f
private const val V_HERO = 493f
private const val V_MIC = 284f
private const val V_ROW1 = 102f
private const val V_ROW2 = 117f
private const val V_ROW3 = 109f
private const val V_PANEL = 170f
private const val V_PRESS = 90f
private const val V_NAV = 138f

/** The reference's proportions at the device width; taller phones grow the controls (≤15%) and the gaps. */
internal class VoiceLayout(width: Dp, height: Dp, statusBar: Dp) {
    val k = width.value / VREF_W
    val shift = max(0f, statusBar.value - 24f).dp
    val grow: Float
    val gaps: List<Dp>

    init {
        val natural = listOf(15f, 13f, 14f, 18f, 20f, 33f).map { it * k }
        val controls = (V_MIC + V_ROW1 + V_ROW2 + V_ROW3 + V_PANEL + V_PRESS + V_NAV) * k
        val fixed = V_HERO * k + shift.value
        val extra = height.value - (fixed + controls + natural.sum())
        grow = if (extra > 0) min(1.15f, 1f + extra * .5f / controls) else 1f
        var rest = max(0f, height.value - (fixed + controls * grow + natural.sum()))
        val weights = listOf(1f, .6f, .6f, 1f, 1.2f, .8f)
        val share = natural.indices.map { natural[it] * weights[it] }
        val added = share.indices.map { min(rest * share[it] / share.sum(), natural[it] * 2.2f) }
        rest -= added.sum()
        gaps = natural.indices.map { (natural[it] + added[it] + if (it == 4) rest else 0f).dp }
    }

    fun px(v: Float) = (v * k).dp
    fun tall(v: Float) = (v * k * grow).dp
    fun sp(v: Float): TextUnit = (v * (k * VREF_W / 392.7f)).sp
}

/**
 * The approved Voice Mode screen: Mylo with glowing headphones, the Voice Mode selector, the big microphone
 * card, six page actions, What Mylo can see (the AI permission switchboard), Press and hold to talk, and the
 * bottom navigation with Mylo in the middle. The microphone glow and waveforms follow [VoiceModeUi.level]
 * (real microphone or playback amplitude); the bubble follows [VoiceModeUi.phase].
 */
@Composable fun VoiceModeScreen(
    ui: VoiceModeUi,
    onTalk: () -> Unit = {},
    onHoldStart: () -> Unit = {},
    onHoldEnd: () -> Unit = {},
    onTypeInstead: () -> Unit = {},
    onClose: () -> Unit = {},
    onAction: (VoiceAction) -> Unit = {},
    onAdjustAccess: () -> Unit = {},
    onToggleAccess: (AiSource) -> Unit = {},
    onModeMenu: () -> Unit = {},
    onSettings: () -> Unit = {},
    onNav: (VoiceNav) -> Unit = {},
    statusBarInset: Dp? = null,
) {
    val statusBar = statusBarInset ?: WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(VoiceNight).testTag("voice-mode-screen")) {
            val layout = VoiceLayout(maxWidth, maxHeight, statusBar)
            val pulse = rememberInfiniteTransition(label = "voice")
            val breath by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "breath")
            val level by animateFloatAsState(ui.level.coerceIn(0f, 1f), tween(90), label = "level")
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Box {
                        Column {
                            Spacer(Modifier.height(layout.px(V_HERO) + layout.shift))
                            MicCard(layout, ui, level, breath, onTalk, onTypeInstead, onClose)
                        }
                        VoiceHero(layout, ui, breath, onModeMenu, onSettings)
                    }
                    Spacer(Modifier.height(layout.gaps[0]))
                    ActionGrid(layout, onAction)
                    Spacer(Modifier.height(layout.gaps[3]))
                    AccessPanel(layout, ui.access, onAdjustAccess, onToggleAccess)
                    Spacer(Modifier.height(layout.gaps[4]))
                    PressAndHold(layout, ui, level, onHoldStart, onHoldEnd)
                    Spacer(Modifier.height(layout.gaps[5]))
                }
                VoiceBottomBar(layout, ui.tabs, onNav)
            }
        }
    }
}

/** The night scene, the corgi (in front of the microphone card), the controls and the speech bubble. */
@Composable private fun VoiceHero(layout: VoiceLayout, ui: VoiceModeUi, breath: Float, onModeMenu: () -> Unit, onSettings: () -> Unit) {
    val scene = ImageBitmap.imageResource(R.drawable.voice_hero_scene)
    val corgi = ImageBitmap.imageResource(R.drawable.voice_hero_corgi)
    val headset = ImageBitmap.imageResource(R.drawable.voice_hero_headset)
    val k = layout.k
    val shift = layout.shift
    fun px(v: Float) = layout.px(v)
    // The scene is drawn behind the card; only its part above the card is visible.
    Box(Modifier.fillMaxWidth().height(px(V_HERO) + shift)) {
        Canvas(Modifier.matchParentSize()) {
            val top = shift.toPx()
            val artH = VREF_ART_H * k * density
            if (top > 0) drawRect(Color(0xFF0B1640), size = Size(size.width, top + 1))
            drawImage(scene, dstOffset = IntOffset(0, top.roundToInt()), dstSize = IntSize(size.width.roundToInt(), artH.roundToInt()), filterQuality = FilterQuality.High)
        }
        Box(Modifier.matchParentSize().clearAndSetSemantics { contentDescription = "Mylo the corgi wearing glowing headphones" })
        Image(rememberVectorPainter(HomeArt.Wordmark), "Mylo",
            Modifier.offset(x = px(33f), y = shift + px(53f)).width(px(268f)).aspectRatio(HomeArt.WORDMARK_ASPECT))
        Row(Modifier.offset(x = px(35f), y = shift + px(161f)), verticalAlignment = Alignment.CenterVertically) {
            Text("Your AI browsing buddy", fontSize = layout.sp(10f), fontWeight = FontWeight.Medium, color = Color(0xFFCDD0EE), maxLines = 1, softWrap = false)
            Icon(VoiceArt.Paw, null, tint = Color(0xFFC9B9FF), modifier = Modifier.padding(start = px(10f)).size(px(30f)))
        }
        // Voice Mode selector.
        val pillShape = CircleShape
        Row(Modifier.offset(x = px(528f), y = shift + px(68f)).size(px(286f), px(76f)).clip(pillShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF121D48), Color(0xFF0D1739)))).border(px(2.6f), Brush.horizontalGradient(listOf(Color(0xFF6466E6), Color(0xFF4E54C8))), pillShape)
            .clickable(role = Role.Button, onClickLabel = "Choose how to talk with Mylo", onClick = onModeMenu).testTag("voice-mode-menu"),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = px(11f)).size(px(56f)).background(Brush.verticalGradient(listOf(Color(0xFF3A63F0), Color(0xFF2338B8))), CircleShape), contentAlignment = Alignment.Center) {
                Icon(VoiceArt.Mic, null, tint = Color.White, modifier = Modifier.size(px(34f)))
            }
            Text("Voice Mode", Modifier.padding(start = px(14f)).weight(1f), fontSize = layout.sp(10.5f), fontWeight = FontWeight.Medium, color = VoiceInk, maxLines = 1, softWrap = false)
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.padding(end = px(20f)).size(px(40f)))
        }
        Box(Modifier.offset(x = px(870f) - 24.dp, y = shift + px(106f) - 24.dp).size(48.dp).clip(CircleShape)
            .clickable(onClickLabel = "Voice Mode settings", role = Role.Button, onClick = onSettings).testTag("voice-settings"), contentAlignment = Alignment.Center) {
            Box(Modifier.size(px(74f)).background(Color(0xD91A2456), CircleShape).border(px(2.4f), Color(0xFF3A438E), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Settings, "Settings", tint = Color.White, modifier = Modifier.size(px(40f)))
            }
        }
        // Handwritten notes.
        Column(Modifier.offset(x = px(36f), y = shift + px(262f)).graphicsLayer { rotationZ = -16f; transformOrigin = TransformOrigin(0f, .5f) }) {
            HandNote("Talk to Mylo", layout.sp(12.6f), Modifier.height(px(40f)))
            HandNote("about any", layout.sp(12.6f), Modifier.height(px(40f)).padding(start = px(26f)))
            HandNote("webpage!", layout.sp(12.6f), Modifier.height(px(40f)).padding(start = px(70f)))
        }
        Column(Modifier.offset(x = px(757f), y = shift + px(172f))) {
            listOf("Ask", "Explore", "Understand", "Get things done").forEachIndexed { i, line ->
                HandNote(line, layout.sp(9.9f), Modifier.height(px(30f)).padding(start = px(4f * i)).graphicsLayer { rotationZ = -9f; transformOrigin = TransformOrigin(0f, .5f) })
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val y0 = shift.toPx()
            fun p(x: Float, y: Float) = Offset(x * k * density, y0 + y * k * density)
            val stroke = Stroke(3.6f * k * density, cap = StrokeCap.Round)
            fun swoosh(a: Offset, c: Offset, b: Offset) = drawPath(Path().apply { moveTo(a.x, a.y); quadraticBezierTo(c.x, c.y, b.x, b.y) }, VoiceHand, style = stroke)
            swoosh(p(124f, 394f), p(152f, 380f), p(194f, 376f))
            swoosh(p(778f, 304f), p(810f, 294f), p(853f, 290f))
            val c = p(887f, 309f)
            val r = 14f * k * density
            drawPath(Path().apply {
                moveTo(c.x, c.y + r * .95f)
                cubicTo(c.x - r * 1.35f, c.y + r * .1f, c.x - r * .9f, c.y - r * 1.05f, c.x, c.y - r * .35f)
                cubicTo(c.x + r * .9f, c.y - r * 1.05f, c.x + r * 1.35f, c.y + r * .1f, c.x, c.y + r * .95f)
            }, VoiceHand, style = Stroke(3f * k * density, cap = StrokeCap.Round))
        }
        SpeechBubble(layout, ui)
        // The corgi sits in front of the microphone card: its paws rest on the card's edge.
        Box(Modifier.offset(x = px(VCORGI_X), y = shift + px(VCORGI_Y)).size(px(VCORGI_W), px(VCORGI_H))) {
            Image(corgi, null, Modifier.matchParentSize(), filterQuality = FilterQuality.High)
            // The headset glows a little brighter while Mylo listens or speaks.
            val active = ui.phase != VoicePhase.Idle
            val glow = (if (active) .22f + .28f * (.5f + .5f * sin(breath * 2 * PI.toFloat())) else .08f + .06f * sin(breath * 2 * PI.toFloat()))
            Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = glow.coerceIn(0f, 1f) }) {
                drawImage(headset, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.High)
                drawRect(Brush.radialGradient(listOf(Color(0xFFB8F4FF), Color(0xFF55C8FF)), Offset(size.width / 2, size.height / 2), size.maxDimension), blendMode = BlendMode.SrcIn)
            }
        }
    }
}

@Composable private fun HandNote(text: String, size: TextUnit, modifier: Modifier) {
    val style = TextStyle(fontFamily = VoiceHandwriting, fontSize = size, color = VoiceHand, textGeometricTransform = TextGeometricTransform(skewX = -.12f))
    Box(modifier) {
        val unclipped = Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
        Text(text, style = style, maxLines = 1, softWrap = false, modifier = unclipped)
        Text(text, style = style.copy(drawStyle = Stroke(size.value * .025f * LocalDensity.current.density)), maxLines = 1, softWrap = false, modifier = unclipped)
    }
}

/** "I'm listening…" and its sister states. */
@Composable private fun SpeechBubble(layout: VoiceLayout, ui: VoiceModeUi) {
    fun px(v: Float) = layout.px(v)
    val text = when (ui.phase) {
        VoicePhase.Idle -> "Ready to help"
        VoicePhase.Listening -> "I’m listening…"
        VoicePhase.Thinking -> "Thinking…"
        VoicePhase.Speaking -> "Speaking…"
    }
    Box(Modifier.offset(x = px(636f), y = layout.shift + px(348f)).size(px(270f), px(118f))
        .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = text }.testTag("voice-bubble")) {
        Canvas(Modifier.matchParentSize()) {
            val k = layout.k * density
            val body = androidx.compose.ui.geometry.RoundRect(6f * k, 0f, 270f * k, 104f * k, androidx.compose.ui.geometry.CornerRadius(52f * k))
            val tail = Path().apply { moveTo(20f * k, 68f * k); lineTo(2f * k, 116f * k); lineTo(52f * k, 92f * k); close() }
            val path = Path.combine(androidx.compose.ui.graphics.PathOperation.Union, Path().apply { addRoundRect(body) }, tail)
            drawPath(path, Brush.verticalGradient(listOf(Color(0xF0142152), Color(0xF00E1940))))
            drawPath(path, Brush.horizontalGradient(listOf(Color(0xFF6C6CF0), Color(0xFF7A72FF))), style = Stroke(2.8f * k))
        }
        AnimatedContent(text, Modifier.align(Alignment.TopCenter).padding(start = px(6f)).height(px(104f)), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "bubble") { t ->
            Box(Modifier.fillMaxHeight().width(px(264f)), contentAlignment = Alignment.Center) {
                Text(t, fontSize = layout.sp(12f), fontWeight = FontWeight.Medium, color = VoiceInk, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The microphone card: Type instead, the glowing microphone with live waveforms, Close voice mode. */
@Composable private fun MicCard(layout: VoiceLayout, ui: VoiceModeUi, level: Float, breath: Float, onTalk: () -> Unit, onTypeInstead: () -> Unit, onClose: () -> Unit) {
    fun px(v: Float) = layout.px(v)
    val shape = RoundedCornerShape(px(42f))
    val glow = when (ui.phase) {
        VoicePhase.Idle -> .7f + .12f * sin(breath * 2 * PI.toFloat())
        VoicePhase.Listening -> .7f + .3f * level
        VoicePhase.Thinking -> .55f + .35f * (.5f + .5f * sin(breath * 6 * PI.toFloat()))
        VoicePhase.Speaking -> .7f + .3f * level
    }
    Box(Modifier.padding(start = px(34f), end = px(VREF_W - 908f)).fillMaxWidth().height(layout.tall(V_MIC))) {
        Box(Modifier.matchParentSize().clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF16254F), Color(0xFF111E45), Color(0xFF0F1B40))))
            .border(px(2.4f), Brush.verticalGradient(listOf(Color(0xFF3B4B8E), Color(0xFF24326A))), shape).testTag("voice-mic-card"))
        // Live waveforms either side of the microphone.
        Waveform(layout, Modifier.offset(x = px(193f), y = px(38f)).size(px(136f), px(76f)), level, ui.phase, breath, left = true)
        Waveform(layout, Modifier.offset(x = px(544f), y = px(38f)).size(px(136f), px(76f)), level, ui.phase, breath, left = false)
        RoundControl(layout, VoiceArt.Keyboard, "Type\ninstead", Offset(97f, 126f), "voice-type-instead", onTypeInstead)
        RoundControl(layout, VoiceArt.Close, "Close\nvoice mode", Offset(778f, 126f), "voice-close", onClose)
        Column(Modifier.align(Alignment.TopCenter).padding(top = px(174f)).width(px(520f)), horizontalAlignment = Alignment.CenterHorizontally) {
            val title = ui.problem ?: when (ui.phase) {
                VoicePhase.Idle -> "Tap to talk with Mylo"
                VoicePhase.Listening -> "Listening…"
                VoicePhase.Thinking -> "Thinking…"
                VoicePhase.Speaking -> "Mylo is speaking · tap to interrupt"
            }
            Text(title, fontSize = layout.sp(if (ui.problem != null) 11.5f else 13.7f), fontWeight = FontWeight.SemiBold, color = VoiceInk, textAlign = TextAlign.Center, maxLines = 2,
                modifier = Modifier.testTag("voice-title"))
            Text(ui.caption ?: "Ask questions, get summaries, or let me help you navigate this page.", fontSize = layout.sp(9.7f), lineHeight = layout.sp(11.8f),
                color = Color(0xFFC6CAE6), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = px(12f)).width(px(430f)).semantics { liveRegion = LiveRegionMode.Polite }.testTag("voice-caption"))
        }
        // The microphone rises above the card's edge: a neon ring with a glow that follows the conversation.
        val talkLabel = when (ui.phase) { VoicePhase.Idle -> "Tap to talk with Mylo"; VoicePhase.Listening -> "Stop listening"; VoicePhase.Thinking -> "Mylo is thinking"; VoicePhase.Speaking -> "Stop Mylo speaking" }
        Box(Modifier.offset(x = px(436f - 110f), y = px(79f - 110f)).size(px(220f)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val k = layout.k * density
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(Brush.radialGradient(0f to Color(0xFF6E68FF).copy(alpha = .65f * glow), .55f to Color(0xFF4B7DFF).copy(alpha = .28f * glow), 1f to Color.Transparent,
                    center = c, radius = 110f * k), 110f * k, c)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF1A2766), Color(0xFF0C1535)), c, 80f * k), 78f * k, c)
                val ring = Brush.sweepGradient(listOf(Color(0xFF8C5BFF), Color(0xFF5FC7FF), Color(0xFF68D2FF), Color(0xFF8C5BFF), Color(0xFFC95BFF), Color(0xFF8C5BFF)), c)
                drawCircle(ring, 82f * k, c, style = Stroke(16f * k), alpha = .35f * glow)
                drawCircle(ring, 80f * k, c, style = Stroke(8f * k))
                drawCircle(Color.White.copy(alpha = .35f), 76f * k, c, style = Stroke(1.6f * k))
            }
            Box(Modifier.size(px(168f)).clip(CircleShape).clickable(role = Role.Button, onClickLabel = talkLabel, onClick = onTalk)
                .semantics { stateDescription = ui.phase.name }.testTag("voice-talk"), contentAlignment = Alignment.Center) {
                MicGlyph(Modifier.size(px(78f)))
            }
        }
        if (ui.micLive) Row(Modifier.align(Alignment.TopCenter).offset(y = px(-56f)).background(Color(0xE63A0F1E), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("voice-mic-live"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(Color(0xFFFF4D5E), CircleShape))
            Text("Microphone on", Modifier.padding(start = 6.dp), fontSize = 11.sp, color = Color(0xFFFFE2E5))
        }
    }
}

/** The big microphone: a filled capsule on a stand. */
@Composable private fun MicGlyph(modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    drawRoundRect(Color.White, Offset(w * .34f, h * .04f), Size(w * .32f, h * .56f), androidx.compose.ui.geometry.CornerRadius(w * .16f))
    val stroke = Stroke(w * .075f, cap = StrokeCap.Round)
    drawArc(Color.White, 0f, 180f, false, Offset(w * .2f, h * .22f), Size(w * .6f, h * .52f), style = stroke)
    drawLine(Color.White, Offset(w * .5f, h * .74f), Offset(w * .5f, h * .94f), strokeWidth = w * .075f, cap = StrokeCap.Round)
}

/** A document with text lines (Find the pricing section; Current Page). */
@Composable internal fun DocumentGlyph(modifier: Modifier, color: Color, lines: Color, outline: Boolean = false) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val body = Path().apply {
        moveTo(w * .26f, h * .1f); lineTo(w * .6f, h * .1f); lineTo(w * .78f, h * .28f); lineTo(w * .78f, h * .84f)
        quadraticBezierTo(w * .78f, h * .92f, w * .7f, h * .92f); lineTo(w * .26f, h * .92f)
        quadraticBezierTo(w * .18f, h * .92f, w * .18f, h * .84f); lineTo(w * .18f, h * .18f)
        quadraticBezierTo(w * .18f, h * .1f, w * .26f, h * .1f); close()
    }
    if (outline) drawPath(body, color, style = Stroke(w * .08f, join = androidx.compose.ui.graphics.StrokeJoin.Round)) else drawPath(body, color)
    val lineColor = if (outline) color else lines
    listOf(.46f, .6f, .74f).forEachIndexed { i, y ->
        drawLine(lineColor, Offset(w * .32f, h * y), Offset(w * if (i == 2) .52f else .64f, h * y), strokeWidth = w * .07f, cap = StrokeCap.Round)
    }
}

@Composable private fun RoundControl(layout: VoiceLayout, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, center: Offset, tag: String, onClick: () -> Unit) {
    fun px(v: Float) = layout.px(v)
    Column(Modifier.offset(x = px(center.x - 80f), y = px(center.y - 44f)).width(px(160f)).clip(RoundedCornerShape(px(30f)))
        .clickable(role = Role.Button, onClick = onClick).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = px(5f)).size(px(78f)).background(Color(0xFF172453), CircleShape).border(px(2.2f), Color(0xFF2E3D78), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(px(38f)))
        }
        Text(label, fontSize = layout.sp(9f), lineHeight = layout.sp(10.6f), color = Color(0xFFDADDF2), textAlign = TextAlign.Center, modifier = Modifier.padding(top = px(6f)))
    }
}

/** Bars that rise and fall with the real audio level (gently at rest). */
@Composable private fun Waveform(layout: VoiceLayout, modifier: Modifier, level: Float, phase: VoicePhase, breath: Float, left: Boolean) {
    val pattern = remember { listOf(.28f, .45f, .7f, .5f, .9f, .62f, 1f, .74f, .52f, .86f, .58f, .4f, .64f, .3f) }
    Canvas(modifier.clearAndSetSemantics { }) {
        val n = pattern.size
        val step = size.width / n
        val colors = if (left) listOf(Color(0xFFF07CFF), Color(0xFFA57CFF), Color(0xFF6E8CFF)) else listOf(Color(0xFF6E8CFF), Color(0xFF4FB4FF), Color(0xFF5FDCFF))
        val live = phase == VoicePhase.Listening || phase == VoicePhase.Speaking
        for (i in 0 until n) {
            val base = if (left) pattern[i] else pattern[n - 1 - i]
            val wobble = .5f + .5f * sin((breath * 2 * PI.toFloat() * 3) + i * .9f)
            val amount = if (live) .3f + .7f * level * (.6f + .4f * wobble) else .8f + .12f * wobble
            val h = size.height * (base * amount).coerceIn(.08f, 1f)
            val x = step * i + step / 2
            drawLine(Brush.verticalGradient(colors, size.height / 2 - h / 2, size.height / 2 + h / 2), Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2),
                strokeWidth = step * .46f, cap = StrokeCap.Round)
        }
    }
}

@Composable private fun ActionGrid(layout: VoiceLayout, onAction: (VoiceAction) -> Unit) {
    fun px(v: Float) = layout.px(v)
    val rows = listOf(
        Triple(VoiceAction.Explain, VoiceAction.FindPricing, V_ROW1),
        Triple(VoiceAction.HelpCancel, VoiceAction.CompareTabs, V_ROW2),
        Triple(VoiceAction.SiteSafety, VoiceAction.Translate, V_ROW3),
    )
    Column(Modifier.padding(start = px(34f), end = px(VREF_W - 908f))) {
        rows.forEachIndexed { i, (a, b, h) ->
            if (i > 0) Spacer(Modifier.height(layout.gaps[i]))
            Row(Modifier.fillMaxWidth().height(layout.tall(h)), horizontalArrangement = Arrangement.spacedBy(px(14f))) {
                ActionCard(layout, a, Modifier.weight(1f).fillMaxHeight(), onAction)
                ActionCard(layout, b, Modifier.weight(1f).fillMaxHeight(), onAction)
            }
        }
    }
}

@Composable private fun ActionCard(layout: VoiceLayout, action: VoiceAction, modifier: Modifier, onAction: (VoiceAction) -> Unit) {
    fun px(v: Float) = layout.px(v)
    val shape = RoundedCornerShape(px(38f))
    val source = remember { MutableInteractionSource() }
    Row(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF13214A), Color(0xFF0F1C40))))
        .border(px(2f), Brush.verticalGradient(listOf(Color(0xFF283767), Color(0xFF1A2650))), shape)
        .clickable(source, LocalIndication.current, role = Role.Button, onClick = { onAction(action) }).testTag("voice-action-${action.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = px(22f)).size(px(82f)), contentAlignment = Alignment.Center) {
            IconDisc(Modifier.matchParentSize())
            when (action) {
                VoiceAction.Explain -> MagnifierGlyph(Modifier.size(px(52f)))
                VoiceAction.FindPricing -> DocumentGlyph(Modifier.size(px(54f)), Color(0xFF8C93FF), Color(0xFF1B2366))
                VoiceAction.HelpCancel -> CompassGlyph(Modifier.size(px(52f)))
                VoiceAction.CompareTabs -> Icon(VoiceArt.Cart, null, tint = Color(0xFFA9B2FF), modifier = Modifier.size(px(60f)))
                VoiceAction.SiteSafety -> SafetyShieldGlyph(Modifier.size(px(50f)))
                VoiceAction.Translate -> Icon(VoiceArt.Translate, null, tint = Color(0xFF6FAEFF), modifier = Modifier.size(px(52f)))
            }
        }
        Column(Modifier.weight(1f).padding(start = px(20f), end = px(10f))) {
            Text(action.title, fontSize = layout.sp(10.3f), fontWeight = FontWeight.SemiBold, color = VoiceInk, lineHeight = layout.sp(12f), maxLines = 2)
            Text(action.subtitle, fontSize = layout.sp(8.6f), color = VoiceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = px(6f)))
        }
    }
}

/** What Mylo can see: one chip per source, showing exactly what the switchboard allows. */
@Composable private fun AccessPanel(layout: VoiceLayout, access: AiAccessUi, onAdjust: () -> Unit, onToggle: (AiSource) -> Unit) {
    fun px(v: Float) = layout.px(v)
    val shape = RoundedCornerShape(px(40f))
    Column(Modifier.padding(start = px(34f), end = px(VREF_W - 908f)).fillMaxWidth().height(layout.tall(V_PANEL)).clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF121E47), Color(0xFF0E1A3E))))
        .border(px(2.4f), Brush.verticalGradient(listOf(Color(0xFF3A4590), Color(0xFF283478))), shape).testTag("voice-access-panel")) {
        Row(Modifier.fillMaxWidth().padding(top = px(20f), start = px(28f), end = px(26f)), verticalAlignment = Alignment.CenterVertically) {
            Icon(VoiceArt.Eye, null, tint = Color.White, modifier = Modifier.size(px(42f)))
            Text("What Mylo can see", Modifier.padding(start = px(14f)).weight(1f), fontSize = layout.sp(9.8f), fontWeight = FontWeight.Medium, color = VoiceInk)
            Row(Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onAdjust).padding(horizontal = 6.dp, vertical = 4.dp).testTag("voice-access-adjust"),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Adjust", fontSize = layout.sp(9.8f), fontWeight = FontWeight.Medium, color = Color(0xFFB9ACFF))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFB9ACFF), modifier = Modifier.size(px(40f)))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(start = px(20f), end = px(20f), bottom = px(19f)), horizontalArrangement = Arrangement.spacedBy(px(16f))) {
            AccessChip(layout, AiSource.CurrentPage, access.currentPage, Modifier.weight(1f)) { onToggle(AiSource.CurrentPage) }
            AccessChip(layout, AiSource.OtherTabs, access.otherTabs, Modifier.weight(1f)) { onToggle(AiSource.OtherTabs) }
            AccessChip(layout, AiSource.History, access.history, Modifier.weight(1f)) { onToggle(AiSource.History) }
            AccessChip(layout, AiSource.Location, access.location, Modifier.weight(1f)) { onToggle(AiSource.Location) }
        }
    }
}

@Composable private fun AccessChip(layout: VoiceLayout, source: AiSource, on: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    fun px(v: Float) = layout.px(v)
    val shape = RoundedCornerShape(px(28f))
    Row(modifier.height(layout.tall(84f)).clip(shape)
        .background(if (on) Brush.linearGradient(listOf(Color(0xFF123A40), Color(0xFF10303A))) else Brush.linearGradient(listOf(Color(0xFF1A2650), Color(0xFF172248))))
        .border(px(2.4f), if (on) Color(0xFF2CCF9B) else Color(0x662B376B), shape)
        .toggleable(on, role = Role.Switch, onValueChange = { onToggle() })
        .semantics { stateDescription = if (on) "On" else "Off" }.testTag("voice-access-${source.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = px(10f)).size(px(50f)).background(if (on) Brush.verticalGradient(listOf(Color(0xFF5CE8B6), Color(0xFF2CBF8E))) else Brush.verticalGradient(listOf(Color(0xFF2B3664), Color(0xFF232E58))), CircleShape),
            contentAlignment = Alignment.Center) {
            val tint = if (on) Color(0xFF0B3B33) else Color(0xFFC5CAE6)
            when (source) {
                AiSource.CurrentPage -> DocumentGlyph(Modifier.size(px(36f)), if (on) Color(0xFF0B3B33) else Color(0xFFC5CAE6), if (on) Color(0xFF5CE8B6) else Color(0xFF26315D), outline = true)
                AiSource.OtherTabs -> Icon(VoiceArt.Tabs, null, tint = tint, modifier = Modifier.size(px(36f)))
                AiSource.History -> Icon(VoiceArt.Clock, null, tint = tint, modifier = Modifier.size(px(36f)))
                AiSource.Location -> Icon(VoiceArt.Pin, null, tint = tint, modifier = Modifier.size(px(36f)))
            }
        }
        Column(Modifier.padding(start = px(9f), end = px(4f))) {
            Text(source.label, fontSize = layout.sp(7.9f), fontWeight = FontWeight.Medium, color = VoiceInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (on) "ON" else "OFF", fontSize = layout.sp(6.8f), fontWeight = FontWeight.Medium, color = if (on) Mint else Color(0xFF8E96BC), modifier = Modifier.padding(top = px(3f)))
        }
    }
}

/** Hold to talk; let go to send. */
@Composable private fun PressAndHold(layout: VoiceLayout, ui: VoiceModeUi, level: Float, onStart: () -> Unit, onEnd: () -> Unit) {
    fun px(v: Float) = layout.px(v)
    var held by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = px(81f), end = px(VREF_W - 860f)).fillMaxWidth().height(layout.tall(V_PRESS))
        .shadow(px(30f), CircleShape, ambientColor = Color(0xFF5B5CFF), spotColor = Color(0xFF5B8BFF))
        .clip(CircleShape)
        .background(Brush.horizontalGradient(listOf(Color(0xFF3A2FB4), Color(0xFF4C3EE2), Color(0xFF3A2FB4))))
        .border(px(3f), Brush.horizontalGradient(listOf(Color(0xFF52B9FF), Color(0xFF8A7BFF), Color(0xFF52B9FF))), CircleShape)
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                held = true; onStart()
                tryAwaitRelease()
                held = false; onEnd()
            })
        }
        .semantics { contentDescription = "Press and hold to talk"; stateDescription = if (held) "Listening" else "Released" }
        .testTag("voice-press-hold"), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().padding(horizontal = px(98f)), verticalAlignment = Alignment.CenterVertically) {
            MiniWave(Modifier.size(px(70f), px(44f)), if (held) level else .35f)
            Text(if (held) "Release to send" else "Press and hold to talk", Modifier.weight(1f), fontSize = layout.sp(12f), fontWeight = FontWeight.SemiBold,
                color = Color.White, textAlign = TextAlign.Center, maxLines = 1)
            MiniWave(Modifier.size(px(70f), px(44f)), if (held) level else .35f)
        }
    }
}

@Composable private fun MiniWave(modifier: Modifier, amount: Float) = Canvas(modifier) {
    val bars = listOf(.3f, .55f, .8f, 1f, .7f, .45f, .25f)
    val step = size.width / bars.size
    bars.forEachIndexed { i, b ->
        val h = size.height * (b * (.35f + .65f * amount)).coerceIn(.1f, 1f)
        val x = step * i + step / 2
        drawLine(Brush.verticalGradient(listOf(Color(0xFFE27BFF), Color(0xFF8C7BFF))), Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), strokeWidth = step * .42f, cap = StrokeCap.Round)
    }
}

/** Home · Search · Mylo · Tabs · Private, with Mylo raised in the middle. */
@Composable private fun VoiceBottomBar(layout: VoiceLayout, tabs: Int, onNav: (VoiceNav) -> Unit) {
    fun px(v: Float) = layout.px(v)
    val face = ImageBitmap.imageResource(R.drawable.voice_nav_corgi)
    val bump = px(26f)
    Box(Modifier.padding(start = px(19f), end = px(VREF_W - 922f)).fillMaxWidth().height(layout.tall(V_NAV) + bump).testTag("voice-bottom-nav")) {
        val shape = remember { navShape() }
        Box(Modifier.matchParentSize().clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF13214A), Color(0xFF0E1A3E)))).border(px(2.4f), Color(0xFF1F2D58), shape))
        Row(Modifier.fillMaxSize().padding(top = bump), verticalAlignment = Alignment.CenterVertically) {
            NavEntry(layout, "Home", Modifier.weight(1f), { onNav(VoiceNav.Home) }) { Icon(VoiceArt.House, null, tint = Color.White, modifier = Modifier.size(px(52f))) }
            NavEntry(layout, "Search", Modifier.weight(1f), { onNav(VoiceNav.Search) }) { Icon(HomeArt.SearchThin, null, tint = Color(0xFFD9DCEF), modifier = Modifier.size(px(56f))) }
            Spacer(Modifier.weight(1.05f))
            NavEntry(layout, "Tabs", Modifier.weight(1f), { onNav(VoiceNav.Tabs) }) {
                Box(Modifier.size(px(44f), px(46f)).border(px(4f), Color(0xFFD9DCEF), RoundedCornerShape(px(10f))), contentAlignment = Alignment.Center) {
                    Text(if (tabs > 99) "99+" else tabs.toString(), fontSize = layout.sp(8.8f), color = Color(0xFFD9DCEF), fontWeight = FontWeight.Medium)
                }
            }
            NavEntry(layout, "Private", Modifier.weight(1f), { onNav(VoiceNav.Private) }) { Icon(VoiceArt.ShieldCheck, null, tint = Color(0xFFD9DCEF), modifier = Modifier.size(px(52f))) }
        }
        // Mylo, raised in the middle (this screen).
        Box(Modifier.align(Alignment.TopCenter).offset(y = bump - px(6f) + (layout.tall(V_NAV) - px(120f)) / 2 - px(14f)).size(px(120f)).clip(CircleShape)
            .selectable(true, role = Role.Tab) { onNav(VoiceNav.Mylo) }.semantics { contentDescription = "Mylo" }.testTag("voice-nav-mylo"),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(Color(0xFF2A3AB0), Color(0xFF16226E)), center, r), r * .94f)
                drawCircle(Brush.sweepGradient(listOf(Color(0xFF9D86FF), Color(0xFF6E7BFF), Color(0xFFB08CFF), Color(0xFF9D86FF))), r * .94f, style = Stroke(r * .07f))
            }
            Image(face, null, Modifier.size(px(66f), px(63f)), filterQuality = FilterQuality.High)
        }
    }
}

@Composable private fun NavEntry(layout: VoiceLayout, label: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    fun px(v: Float) = layout.px(v)
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(px(30f))).clickable(role = Role.Tab, onClick = onClick).testTag("voice-nav-${label.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        icon()
        Text(label, fontSize = layout.sp(8.9f), fontWeight = FontWeight.Medium, color = if (label == "Home") Color.White else Color(0xFFD9DCEF), maxLines = 1,
            modifier = Modifier.padding(top = px(12f)))
    }
}

/** The navigation bar's outline: a rounded bar whose top edge rises in a soft hump around Mylo. */
private fun navShape() = GenericShape { size, _ ->
    val bump = size.height * 26f / (V_NAV + 26f)
    val r = 44f * size.width / 903f
    val mid = size.width / 2
    val half = 84f * size.width / 903f
    moveTo(0f, bump + r)
    quadraticBezierTo(0f, bump, r, bump)
    lineTo(mid - half - 40f * size.width / 903f, bump)
    cubicTo(mid - half + 6f * size.width / 903f, bump, mid - half + 10f * size.width / 903f, 0f, mid, 0f)
    cubicTo(mid + half - 10f * size.width / 903f, 0f, mid + half - 6f * size.width / 903f, bump, mid + half + 40f * size.width / 903f, bump)
    lineTo(size.width - r, bump)
    quadraticBezierTo(size.width, bump, size.width, bump + r)
    lineTo(size.width, size.height - r)
    quadraticBezierTo(size.width, size.height, size.width - r, size.height)
    lineTo(r, size.height)
    quadraticBezierTo(0f, size.height, 0f, size.height - r)
    close()
}

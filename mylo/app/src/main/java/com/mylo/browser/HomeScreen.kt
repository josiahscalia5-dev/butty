package com.mylo.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * Home, reproduced from the approved target design/reference/home-reference.jpg: 864 × 1536 px for a
 * 411.4 × 731.4 dp phone (2.1 px per dp, 24 dp status bar). Every size below is a measurement from that image
 * converted to dp; the comments give the source pixels. The hero artwork scales with the screen width so the
 * composition never changes; controls keep their dp sizes (48 dp touch targets), and on short screens the
 * middle of the page scrolls instead of shrinking.
 */

// Palette sampled from the target.
internal val HomeNight = Color(0xFF051431)
private val Ink = Color(0xFFFBFDFF)
private val GreetingMuted = Color(0xFFC5D1FB)
private val CardMuted = Color(0xFFC9D5F8)
private val ShieldMuted = Color(0xFFAEC3EA)
private val SearchFill = Color(0xFFEBE9FE)
private val SearchInk = Color(0xFF1D1F57)
private val SearchHint = Color(0xFF4A4966)
private val CardFill = Color(0xFF122244)
private val CardEdge = Brush.verticalGradient(listOf(Color(0xFF253756), Color(0xFF1A2849), Color(0xFF1F2E50)))
private val NavFill = Brush.verticalGradient(listOf(Color(0xFF0F1F3E), Color(0xFF102140)))
private val NavEdge = Brush.verticalGradient(listOf(Color(0xFF2E3E62), Color(0xFF14243F)))
private val NavPill = Color(0xFFD7CCFE)
private val NavPillInk = Color(0xFF34277A)

/** Google Sans (OFL, third_party/googlesans), the typeface of the approved Home and browser targets. */
internal val GoogleSans = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_semibold, FontWeight.SemiBold),
    Font(R.font.google_sans_bold, FontWeight.Bold),
)
private val MyloRounded = FontFamily(Font(R.font.nunito_black, FontWeight.Black))

/**
 * A text style whose glyphs sit centred in an exact line box, so a text's first baseline is
 * `line / 2 + 0.34 × size` below its top (Google Sans: ascent 0.966 em, descent 0.286 em).
 */
internal fun sansStyle(size: Float, line: Float, weight: FontWeight = FontWeight.Normal, color: Color = Ink) = TextStyle(
    fontFamily = GoogleSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, color = color, letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Target width in px; the hero artwork and wordmark are placed in these units (×width/864). */
private const val TARGET_WIDTH = 864f
/** Target rows 0–447 are the hero; the search field starts at 448. */
private const val HERO_ROWS = 448f
/** The target's status bar. Taller bars move the greeting and art down together. */
private val TARGET_STATUS_BAR = 24.dp

/**
 * Height of the hero (art, greeting and wordmark) above the search field: the target's, plus [lift] of extra sky on
 * taller screens. A taller status bar than the target's (camera cutouts) moves the art, wordmark and greeting down
 * together, without pushing the page: the art's lowest strip then tucks behind the search field.
 */
internal fun heroBottom(width: Dp, lift: Dp = 0.dp): Dp = lift + width * (HERO_ROWS / TARGET_WIDTH)
private fun heroShift(statusBar: Dp) = (statusBar - TARGET_STATUS_BAR).coerceAtLeast(0.dp)

/** Content below the search field at the target size, down to the bottom navigation's top margin. */
private val MIDDLE_HEIGHT = 393.8.dp

/**
 * Approved Home screen. The artwork sits behind the transparent status bar; greeting and search stay pinned
 * while the middle content scrolls on short screens. On taller screens than the target the sky above the
 * wordmark grows a little and the remaining height is shared between the sections.
 */
@Composable fun HomeScreen(
    query: String = "", onQuery: (String) -> Unit = {}, onSearch: () -> Unit = {}, onVoice: () -> Unit = {},
    onPanel: (String) -> Unit = {}, onOpen: (String) -> Unit = {}, onPrivate: () -> Unit = {},
    vpnActive: Boolean = false,
    // Focus this screen's search box (bottom Search, new tab); [onSearchFocused] marks the request handled.
    focusSearch: Boolean = false, onSearchFocused: () -> Unit = {},
    // Mylo Shield's real state: the connected city (only for a verified tunnel) and the status line.
    vpnLocation: String? = null, vpnDetail: String? = null, onScan: () -> Unit = {}, statusBarInset: Dp? = null,
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            // Focus can fail while the field is detached; never let that cancel the request.
            runCatching { requester.requestFocus() }
            // Wait until the input connection has followed Compose focus.
            withFrameNanos { }
            runCatching { keyboard?.show() }
            onSearchFocused()
        }
    }
    val statusBar = statusBarInset ?: WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    CompositionLocalProvider(LocalTextStyle provides sansStyle(14f, 18f)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(HomeNight)) {
            val width = maxWidth
            val keyboardCompact = maxHeight < 440.dp
            // Height the target composition needs here; anything beyond it is spare.
            val spare = maxHeight - (heroBottom(width) + 48.4.dp + MIDDLE_HEIGHT)
            val lift = (spare * .3f).coerceIn(0.dp, 64.dp)
            Column(Modifier.fillMaxSize()) {
                if (keyboardCompact) Spacer(Modifier.height(statusBar + 8.dp))
                else HomeHero(width, statusBar, lift) { onPanel("settings") }
                SearchBar(query, onQuery, onSearch, onVoice, onScan, requester, Modifier.padding(horizontal = 16.dp).testTag("home-search"))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewport = maxHeight
                    Column(Modifier.fillMaxSize().testTag("home-middle").verticalScroll(rememberScrollState())) {
                        Column(Modifier.fillMaxWidth().heightIn(min = viewport)) {
                            // Search bottom (549 px) to the shortcut rings (575 px).
                            Spacer(Modifier.height(11.9.dp)); Spacer(Modifier.weight(1f))
                            Shortcuts(onOpen)
                            // Labels to the cards (766 px).
                            Spacer(Modifier.height(9.2.dp)); Spacer(Modifier.weight(1f))
                            Column(Modifier.padding(horizontal = 17.6.dp), verticalArrangement = Arrangement.spacedBy(6.2.dp)) {
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    UtilityCard("Private", "Browse without\na trace", Color(0xFF9F84FF), Color(0xFF583BE2), Modifier.weight(1f), onPrivate) {
                                        Icon(HomeArt.Incognito, null, tint = Color.White, modifier = Modifier.size(33.dp))
                                    }
                                    UtilityCard("Bookmarks", "Save your\nfavorite places", Color(0xFF5DBAFE), Color(0xFF1D74EF), Modifier.weight(1f), { onPanel("bookmarks") }) {
                                        Icon(Icons.Rounded.Bookmark, null, tint = Color.White, modifier = Modifier.size(34.dp))
                                    }
                                }
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    UtilityCard("History", "Pick up where\nyou left off", Color(0xFF6CF0C1), Color(0xFF27C195), Modifier.weight(1f), { onPanel("history") }) {
                                        Icon(HomeArt.Clock, null, tint = Color.White, modifier = Modifier.size(31.dp))
                                    }
                                    UtilityCard("Tools", "Useful tools\nfor your browsing", Color(0xFFFFE468), Color(0xFFFCBA4B), Modifier.weight(1f), { onPanel("tools") }) {
                                        Icon(Icons.Rounded.GridView, null, tint = Color(0xFF2B3172), modifier = Modifier.size(32.dp))
                                    }
                                }
                            }
                            // Cards to Mylo Shield (1077 px).
                            Spacer(Modifier.height(7.4.dp)); Spacer(Modifier.weight(1f))
                            ShieldRow(vpnActive, vpnLocation, vpnDetail, Modifier.padding(horizontal = 16.2.dp).testTag("home-shield")) { onPanel("vpn") }
                            // Shield to the banner (1203 px).
                            Spacer(Modifier.height(8.6.dp)); Spacer(Modifier.weight(1f))
                            DiscoveryBanner(Modifier.padding(horizontal = 16.2.dp).testTag("home-discovery")) { onOpen("https://en.wikipedia.org/wiki/Special:Random") }
                        }
                    }
                }
            }
        }
    }
}

/** Places a text so its first baseline lands [baseline] below the parent's top, [x] from its start. */
private fun Modifier.baselineAt(x: Dp, baseline: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val first = placeable[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
    layout(placeable.width, placeable.height) { placeable.place(x.roundToPx(), baseline.roundToPx() - first) }
}

/** Mylo on the moon with the wordmark and tagline, composed exactly as in the approved target. */
@Composable internal fun HeroBackdrop(width: Dp, statusBar: Dp, lift: Dp = 0.dp, content: @Composable BoxScope.() -> Unit = {}) {
    val art = ImageBitmap.imageResource(R.drawable.home_hero)
    val k = width / TARGET_WIDTH // dp per target px
    // The art sits below the status bar's extra height or the extra sky, whichever is more.
    val top = maxOf(heroShift(statusBar), lift)
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(heroBottom(width, lift)).clipToBounds().drawBehind {
        val y = top.toPx()
        // The target's sky continues above the art when the hero is taller than the target's.
        if (y > 0f) drawRect(Brush.verticalGradient(listOf(Color(0xFF07163D), Color(0xFF0A1A45)), 0f, y + 1f), size = Size(size.width, y + 1f))
        drawImage(art, dstOffset = IntOffset(0, y.roundToInt()), dstSize = IntSize(size.width.roundToInt(), (size.width * HERO_ROWS / TARGET_WIDTH).roundToInt()),
            filterQuality = FilterQuality.High)
    }) {
        Box(Modifier.matchParentSize().clearAndSetSemantics { contentDescription = "Mylo, a happy corgi sitting on the moon above a moonlit lake" })
        val wordmark = rememberVectorPainter(HomeWordmark.Vector)
        // A soft shadow under the letters, as in the target, then the wordmark itself.
        Image(wordmark, null, Modifier.offset(x = k * 86.9f, y = top + k * 170.4f).size(k * 316f, k * 126.9f).graphicsLayer { alpha = .38f },
            colorFilter = ColorFilter.tint(Color(0xFF040A2A)))
        Image(wordmark, "Mylo", Modifier.offset(x = k * 86.9f, y = top + k * 167.9f).size(k * 316f, k * 126.9f))
        // The tagline is part of the logo lockup: it scales with the artwork, not with the font-size setting.
        val tagline = with(density) { (k * 30.8f).toPx().toSp() }
        Text("A brighter web awaits", maxLines = 1, softWrap = false,
            style = sansStyle(0f, 0f, FontWeight.Medium, Color(0xFFDFE6FF)).copy(fontSize = tagline, lineHeight = tagline * 1.25f, letterSpacing = (-.025).em,
                shadow = Shadow(Color(0x80030A26), Offset(0f, 2f), 7f)),
            modifier = Modifier.baselineAt(k * 101f, top + k * 322.6f))
        content()
    }
}

/** Greeting and settings over the hero. */
@Composable private fun HomeHero(width: Dp, statusBar: Dp, lift: Dp, onSettings: () -> Unit) {
    HeroBackdrop(width, statusBar, lift) {
        val top = statusBar
        Box(Modifier.fillMaxWidth().height(top + 46.dp).testTag("home-header")) {
            // Moon disc: centre (84, 95.5) px, 76 px across.
            Box(Modifier.offset(x = 21.9.dp, y = top + 3.4.dp).size(36.2.dp).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF35438C), Color(0xFF212E6C))))
                .border(1.dp, Color(0x1FFFFFFF), CircleShape)) {
                Image(rememberVectorPainter(HomeArt.GreetingMoon), null, Modifier.fillMaxSize())
            }
            // "Good evening!" baseline 91.5 px, "Have a brighter browse ✨" baseline 118.5 px, both from x 140 px.
            Text("Good evening!", style = sansStyle(11.5f, 14f, FontWeight.SemiBold), maxLines = 1,
                modifier = Modifier.semantics { heading() }.baselineAt(66.7.dp, top + 19.6.dp))
            Row(Modifier.baselineAt(66.7.dp, top + 32.4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Have a brighter browse", style = sansStyle(9.3f, 12f, color = GreetingMuted), maxLines = 1)
                Image(rememberVectorPainter(HomeArt.Sparkles), null, Modifier.padding(start = 4.2.dp).size(9.6.dp, 8.7.dp))
            }
            // Settings: disc centre (787, 95) px, 75 px across, in a 48 dp touch target.
            Box(Modifier.align(Alignment.TopEnd).offset(x = (-12.8).dp, y = top - 2.8.dp).size(48.dp).clip(CircleShape)
                .clickable(onClickLabel = "Open settings", onClick = onSettings), contentAlignment = Alignment.Center) {
                Box(Modifier.size(35.7.dp).background(Brush.verticalGradient(listOf(Color(0xFF2B3A7C), Color(0xFF1E2A63))), CircleShape)
                    .border(1.dp, Color(0x1AFFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Settings, "Settings", tint = Color.White, modifier = Modifier.size(18.5.dp))
                }
            }
        }
    }
}

// Private Mode's new-tab page keeps its own approved search pill and text.
internal val SearchTextStyle = TextStyle(color = Color(0xFF1E2150), fontSize = 15.5.sp)
internal val SearchPlaceholderStyle = TextStyle(color = Color(0xFF4A4E7E), fontSize = 15.5.sp, letterSpacing = .1.sp)

/** Private Mode's large rounded search pill with a soft lavender glow; brighter edge while focused. */
internal fun Modifier.searchPill(focused: Boolean) = fillMaxWidth().height(58.dp)
    .shadow(if (focused) 22.dp else 16.dp, CircleShape, ambientColor = Color(0xFF8E7CFF), spotColor = Color(0xFF8E7CFF))
    .clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFF0F0FE), Color(0xFFE4E5FB))))
    .border(if (focused) 1.5.dp else 1.dp, if (focused) Color(0xFFB4A8FF) else Color(0x66FFFFFF), CircleShape)

/** Home's search pill: 48.4 dp tall (target y 448–549 px), pale lavender, a faint glow; brighter edge while focused. */
private fun Modifier.homeSearchPill(focused: Boolean) = fillMaxWidth().height(48.4.dp)
    .shadow(if (focused) 16.dp else 9.dp, CircleShape, ambientColor = Color(0xFF8E7CFF), spotColor = Color(0xFF6E62E0))
    .clip(CircleShape).background(SearchFill)
    .border(if (focused) 1.5.dp else 1.dp, if (focused) Color(0xFFB4A8FF) else Color(0x80FFFFFF), CircleShape)

/**
 * Home's own search box: the user types here and the keyboard's Search action submits. Words go to
 * the provider saved in Settings and web addresses open directly; there is no separate search page.
 * Positions from the target: magnifier centre 27.6 dp from the left end, text from 58.6 dp, divider,
 * microphone and scanner 104.5, 75.5 and 27.1 dp from the right end.
 */
@Composable private fun SearchBar(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit, onVoice: () -> Unit, onScan: () -> Unit, requester: FocusRequester, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.homeSearchPill(focused).padding(start = 5.6.dp, end = 5.1.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { runCatching { requester.requestFocus() }; keyboard?.show() }, modifier = Modifier.size(44.dp)) {
            Icon(HomeArt.SearchBold, "Focus search", tint = SearchInk, modifier = Modifier.size(25.5.dp))
        }
        BasicTextField(value, onValue, Modifier.weight(1f).padding(start = 7.6.dp, end = 6.dp).focusRequester(requester).onFocusChanged { focused = it.isFocused }
            .testTag("search-input").semantics { contentDescription = "Search or enter address" }, singleLine = true,
            textStyle = sansStyle(13.7f, 18f, color = Color(0xFF1E2150)), cursorBrush = SolidColor(Color(0xFF493B96)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Search or enter address", style = sansStyle(13.7f, 18f, color = SearchHint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            })
        if (value.isNotBlank()) IconButton(onClick = onSubmit, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Go", tint = SearchInk) }
        Box(Modifier.width(1.dp).height(21.dp).background(Color(0xFFCFCDE2)))
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onVoice, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Mic, "Voice search", tint = SearchInk, modifier = Modifier.size(25.3.dp)) }
        Spacer(Modifier.width(1.7.dp))
        IconButton(onClick = onScan, modifier = Modifier.size(44.dp)) { Icon(HomeArt.Scanner, "Scan a code", tint = SearchInk, modifier = Modifier.size(25.9.dp)) }
    }
}

/** Explore, Videos, Shop and AI: 66.7 dp rings centred 96.8 dp apart (x 127/331/534/736 px). */
@Composable private fun Shortcuts(onOpen: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Shortcut("Explore", Color(0xFF15295F), Color(0xFF0F2357), Modifier.weight(1f), { onOpen("https://en.wikipedia.org/wiki/Special:Random") }) {
            ShortcutDisk(Color(0xFF63B2FF), Color(0xFF2B66DE)) { Icon(HomeArt.Compass, null, tint = Color.White, modifier = Modifier.size(41.dp)) }
        }
        Shortcut("Videos", Color(0xFF26254A), Color(0xFF221C41), Modifier.weight(1f), { onOpen("https://m.youtube.com") }) {
            ShortcutDisk(Color(0xFFFF7A8E), Color(0xFFE23F69)) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(35.dp)) }
        }
        Shortcut("Shop", Color(0xFF132E49), Color(0xFF0F2941), Modifier.weight(1f), { onOpen("https://www.amazon.com") }) {
            Image(rememberVectorPainter(HomeArt.ShopBag), null, Modifier.size(55.dp))
        }
        Shortcut("AI", Color(0xFF2B3137), Color(0xFF29282F), Modifier.weight(1f), { onOpen("https://chatgpt.com") }) {
            ShortcutDisk(Color(0xFFFFD562), Color(0xFFF7A43A)) { Icon(HomeArt.Sparkle, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
        }
    }
}

/** A shortcut ring ([top] to [bottom] gradient, faint top-lit rim) with its glyph and label. */
@Composable private fun Shortcut(label: String, top: Color, bottom: Color, modifier: Modifier, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.width(84.dp).pressScale(source).clip(RoundedCornerShape(18.dp))
            .clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(66.7.dp).background(Brush.verticalGradient(listOf(top, bottom)), CircleShape)
                .border(1.dp, Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x08FFFFFF))), CircleShape), contentAlignment = Alignment.Center) { glyph() }
            // Label baseline 739.5 px: 11.6 dp under the ring.
            Text(label, style = sansStyle(11.3f, 14f, FontWeight.Medium, Color(0xFFF2F6FF)), maxLines = 1, modifier = Modifier.padding(top = .8.dp))
        }
    }
}

@Composable private fun ShortcutDisk(light: Color, deep: Color, glyph: @Composable () -> Unit) {
    Box(Modifier.size(42.dp).drawBehind {
        // The disk's soft coloured glow inside the ring.
        drawCircle(Brush.radialGradient(listOf(deep.copy(alpha = .3f), deep.copy(alpha = 0f)), center, size.minDimension * .78f), size.minDimension * .78f)
    }.background(Brush.linearGradient(listOf(light, deep)), CircleShape), contentAlignment = Alignment.Center) { glyph() }
}

/**
 * Private, Bookmarks, History and Tools: 66.7 dp cards with a 43 dp icon tile 12.4 dp from the left, the title
 * baseline 24 dp and the subtitle baselines 38.8 and 51.2 dp from the top, and the chevron 12.4 dp from the right.
 */
@Composable private fun UtilityCard(title: String, subtitle: String, light: Color, deep: Color, modifier: Modifier, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val source = remember { MutableInteractionSource() }
    Row(modifier.pressScale(source).fillMaxHeight().heightIn(min = 66.7.dp).clip(shape).background(CardFill)
        .border(1.dp, CardEdge, shape).clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick).padding(start = 12.4.dp, end = 5.7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(43.dp).shadow(4.dp, RoundedCornerShape(13.dp), ambientColor = deep, spotColor = deep)
            .background(Brush.linearGradient(listOf(light, deep), Offset.Zero, Offset(120f, 140f)), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { glyph() }
        Column(Modifier.weight(1f).padding(start = 15.2.dp, top = 13.2.dp, bottom = 12.5.dp)) {
            Text(title, style = sansStyle(11.3f, 14f, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = sansStyle(10.4f, 12.4f, color = CardMuted), modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFD4DBF5), modifier = Modifier.size(17.dp))
    }
}

/**
 * Mylo Shield (target y 1077–1186 px). Its status is never simulated: "Connected" appears only for a verified
 * Mylo Shield tunnel or another app's VPN that Android reports; otherwise it offers Set up.
 */
@Composable private fun ShieldRow(active: Boolean, location: String?, detail: String?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(modifier.fillMaxWidth().heightIn(min = 51.9.dp).clip(shape)
        .background(Brush.horizontalGradient(0f to Color(0xFF112B41), .55f to Color(0xFF12253F), 1f to Color(0xFF132243)))
        .border(1.dp, Brush.verticalGradient(listOf(Color(0xFF2F4B61), Color(0xFF1C3650))), shape)
        .clickable(role = Role.Button, onClickLabel = "Open Mylo Shield", onClick = onClick).padding(start = 14.8.dp, end = 12.4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(rememberVectorPainter(HomeArt.ShieldBadge), null, Modifier.size(33.dp, 37.6.dp).drawBehind {
            // The badge's soft mint glow.
            drawCircle(Brush.radialGradient(listOf(Color(0x3846E0A6), Color(0x0046E0A6)), center, 24.dp.toPx()), 24.dp.toPx())
        })
        Column(Modifier.weight(1f).padding(start = 14.8.dp, top = 8.dp, bottom = 8.dp, end = 8.dp)) {
            // Centred, these put the title baseline 24 dp and the status baseline 37.4 dp from the top of the row.
            Text("Mylo Shield", style = sansStyle(12.3f, 15f, FontWeight.SemiBold), maxLines = 1)
            Text(detail ?: if (active) "Connected" else "Not connected", style = sansStyle(9.6f, 12f, color = ShieldMuted), maxLines = 2,
                modifier = Modifier.padding(top = 1.dp).testTag("home-shield-status"))
        }
        val action = when {
            location != null -> location
            active -> "Details"
            else -> "Set up"
        }
        Row(Modifier.height(37.1.dp).widthIn(min = 81.dp).background(Brush.verticalGradient(listOf(Color(0xFF285259), Color(0xFF214751))), CircleShape)
            .padding(start = 17.6.dp, end = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(action, style = sansStyle(10.7f, 14f, FontWeight.SemiBold), maxLines = 1)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(15.dp))
        }
    }
}

/** "A little more wonder" (target y 1203–1376 px): the approved lake scene with native text and button. */
@Composable private fun DiscoveryBanner(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val source = remember { MutableInteractionSource() }
    Box(modifier.pressScale(source).fillMaxWidth().height(82.4.dp).clip(shape).background(Color(0xFF14245C))
        .clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick)) {
        Image(painterResource(R.drawable.home_discovery), null, Modifier.matchParentSize().padding(1.dp), contentScale = ContentScale.FillBounds)
        Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(listOf(Color(0xFF3A4C86), Color(0xFF26346A))), shape))
        // Title baselines 26.9 and 44.5 dp, "Explore today" baseline 65 dp, button centre (110, 61) dp.
        Text("A little more\nwonder", fontFamily = MyloRounded, fontWeight = FontWeight.Black, fontSize = 18.1.sp, lineHeight = 17.6.sp, color = Color.White,
            style = TextStyle(shadow = Shadow(Color(0x66050B2A), Offset(0f, 2f), 6f), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
            modifier = Modifier.semantics { heading() }.baselineAt(21.6.dp, 26.9.dp))
        Text("Explore today", style = sansStyle(11.1f, 14f, FontWeight.Medium, Color(0xFFE4E9FF)), modifier = Modifier.baselineAt(22.9.dp, 65.dp))
        Box(Modifier.offset(x = 100.2.dp, y = 51.2.dp).size(19.6.dp).background(Color(0xFFDCDEFF), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFF181C5E), modifier = Modifier.size(15.dp))
        }
    }
}

/** Which bottom-navigation item is the current screen. */
enum class NavTab { Home, Search, None }

/**
 * Bottom-navigation geometry. The Home and browser targets draw it at different sizes (Home: 51 dp tall,
 * 13 dp side margins, 18 dp above the screen edge; browser: 67.8 dp tall, 8.5 dp margins, directly above the
 * gesture area), and each screen keeps its own.
 */
data class NavMetrics(
    val height: Dp, val side: Dp, val gestureGap: Dp, val top: Dp, val radius: Dp,
    val pillTop: Dp, val pillWidth: Dp, val pillHeight: Dp, val label: Float, val labelGap: Dp,
    val home: Dp, val search: Dp, val tabs: Dp, val mylo: Dp, val outlineHome: Boolean,
) {
    companion object {
        /** Home target: nav 1391–1498 px, pill 115 × 55 px, labels baseline 1481.5 px. */
        val Home = NavMetrics(51.dp, 12.9.dp, 18.dp, 7.1.dp, 14.dp, 5.2.dp, 54.8.dp, 26.2.dp, 10f, 1.6.dp,
            home = 21.dp, search = 26.5.dp, tabs = 19.5.dp, mylo = 29.7.dp, outlineHome = false)
        /** Browser target: nav 1365–1473 px, pill 107 × 48 px, labels baseline 1452.5 px (1.592 px per dp). */
        val Browser = NavMetrics(67.8.dp, 8.5.dp, 23.dp, 6.dp, 18.dp, 11.3.dp, 67.2.dp, 30.2.dp, 11.4f, 2.4.dp,
            home = 27.7.dp, search = 29.4.dp, tabs = 20.7.dp, mylo = 29.6.dp, outlineHome = true)
    }
}

@Composable fun BottomBar(
    selected: NavTab, tabs: Int, onHome: () -> Unit, onSearch: () -> Unit, onTabs: () -> Unit, onMylo: () -> Unit,
    metrics: NavMetrics = NavMetrics.Home,
    // For renders without system bars: the navigation-bar inset to lay out against.
    navigationInset: Dp? = null,
) {
    val shape = RoundedCornerShape(metrics.radius)
    // Gesture navigation: the bar sits [gestureGap] above the screen edge, over the bottom of the gesture area as
    // in the target. Three-button navigation: always above the buttons. With the keyboard open: just above it.
    val inset = navigationInset ?: WindowInsets.navigationBars.exclude(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    val gap = when {
        inset == 0.dp -> 6.dp
        inset <= 32.dp -> maxOf(metrics.gestureGap, inset - 6.dp)
        else -> inset + 4.dp
    }
    CompositionLocalProvider(LocalTextStyle provides sansStyle(metrics.label, metrics.label + 3f)) {
        Row(Modifier.padding(start = metrics.side, end = metrics.side, top = metrics.top, bottom = gap).fillMaxWidth().height(metrics.height).testTag("home-bottom-nav").clip(shape)
            .background(NavFill).border(1.dp, NavEdge, shape), verticalAlignment = Alignment.Top) {
            val homeIcon = if (selected == NavTab.Home || !metrics.outlineHome) Icons.Rounded.Home else HomeArt.HomeOutline
            NavItem("Home", selected == NavTab.Home, metrics, Modifier.weight(1f), onHome) { tint -> Icon(homeIcon, null, tint = tint, modifier = Modifier.size(metrics.home)) }
            NavItem("Search", selected == NavTab.Search, metrics, Modifier.weight(1f), onSearch) { tint -> Icon(HomeArt.SearchThin, null, tint = tint, modifier = Modifier.size(metrics.search)) }
            NavItem("Tabs", false, metrics, Modifier.weight(1f), onTabs) { tint ->
                Box(Modifier.size(metrics.tabs).border(1.6.dp, tint, RoundedCornerShape(4.5.dp)), contentAlignment = Alignment.Center) {
                    Text(if (tabs > 99) "99+" else tabs.toString(), style = sansStyle(metrics.label * .92f, metrics.label, FontWeight.Medium, tint))
                }
            }
            NavItem("Mylo", false, metrics, Modifier.weight(1f), onMylo) { tint ->
                Icon(if (metrics.outlineHome) HomeArt.MyloFaceTuft else HomeArt.MyloFaceRound, null, tint = tint, modifier = Modifier.size(metrics.mylo))
            }
        }
    }
}

@Composable private fun NavItem(label: String, selected: Boolean, metrics: NavMetrics, modifier: Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).selectable(selected, role = Role.Tab, onClick = onClick).padding(top = metrics.pillTop),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val pill by animateColorAsState(if (selected) NavPill else NavPill.copy(alpha = 0f), tween(220), label = "nav")
        Box(Modifier.size(metrics.pillWidth, metrics.pillHeight).background(pill, CircleShape), contentAlignment = Alignment.Center) {
            icon(if (selected) NavPillInk else Color(0xFFE2E6FF))
        }
        Text(label, style = sansStyle(metrics.label, metrics.label + 3f, if (selected) FontWeight.SemiBold else FontWeight.Normal,
            if (selected) Color(0xFFDCDCFF) else Color(0xFFD3DBF8)), maxLines = 1, modifier = Modifier.padding(top = metrics.labelGap))
    }
}

/** A very slight press-in for Home's tappable surfaces, alongside the ripple. */
@Composable private fun Modifier.pressScale(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(120), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

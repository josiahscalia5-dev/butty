package com.mylo.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Approved Home palette, sampled from the approved reference.
internal val HomeNight = Color(0xFF071530)
private val MyloRounded = FontFamily(Font(R.font.nunito_black, FontWeight.Black))
private val HomeInk = Color(0xFFF5F4FF)
private val HomeMuted = Color(0xFFAEB7DA)
private val SearchInk = Color(0xFF23286A)
private val CardBorder = Color(0xFF1E2C50)
/** Top-lit card edge: a faint highlight that fades into the card. */
private val CardEdge = Brush.verticalGradient(listOf(Color(0xFF34457A), Color(0xFF15213F)))
private val NavInk = Color(0xFFD2D6EE)

/** Width of the approved reference, in dp. Hero artwork scales from it; controls keep their dp sizes. */
private const val REFERENCE_WIDTH = 392.7f

/**
 * Approved Home screen. Dimensions follow the approved 393 × 851 dp reference. The artwork sits
 * behind the transparent status bar; greeting and search stay pinned while the middle content
 * scrolls on short screens, and on tall screens the spare height is shared between sections.
 */
@Composable fun HomeScreen(
    query: String = "", onQuery: (String) -> Unit = {}, onSearch: () -> Unit = {}, onVoice: () -> Unit = {},
    onPanel: (String) -> Unit = {}, onOpen: (String) -> Unit = {}, onPrivate: () -> Unit = {},
    vpnActive: Boolean = false,
    // Focus this screen's search box (bottom Search, new tab); [onSearchFocused] marks the request handled.
    focusSearch: Boolean = false, onSearchFocused: () -> Unit = {},
    // The connected Mylo Shield city (or the reference render's sample) and the strip's status line.
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
    HomeTextStyle {
        BoxWithConstraints(Modifier.fillMaxSize().background(HomeNight)) {
            val width = maxWidth
            val keyboardCompact = maxHeight < 440.dp
            val narrow = width < 380.dp
            Column(Modifier.fillMaxSize()) {
                if (keyboardCompact) Spacer(Modifier.height(statusBar + 8.dp))
                else HomeHero(width, statusBar) { onPanel("settings") }
                SearchBar(query, onQuery, onSearch, onVoice, onScan, requester, Modifier.padding(horizontal = 12.dp).testTag("home-search"))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewport = maxHeight
                    Column(Modifier.fillMaxSize().testTag("home-middle").verticalScroll(rememberScrollState())) {
                        Column(Modifier.fillMaxWidth().heightIn(min = viewport).padding(bottom = 5.dp)) {
                            Spacer(Modifier.height(21.dp)); Spacer(Modifier.weight(1f))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                                Shortcut("Explore", Color(0xFF172A5A), { onOpen("https://en.wikipedia.org/wiki/Special:Random") }) {
                                    ShortcutDisk(Color(0xFF6AADF7), Color(0xFF2763C4)) { Icon(HomeArt.Compass, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                                }
                                Shortcut("Videos", Color(0xFF25274D), { onOpen("https://m.youtube.com") }) {
                                    ShortcutDisk(Color(0xFFFF7A93), Color(0xFFE0436C)) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                                }
                                Shortcut("Shop", Color(0xFF17304E), { onOpen("https://www.amazon.com") }) {
                                    Image(rememberVectorPainter(HomeArt.ShopBag), null, Modifier.size(50.dp))
                                }
                                Shortcut("AI", Color(0xFF292F3F), { onOpen("https://chatgpt.com") }) {
                                    ShortcutDisk(Color(0xFFFFD86A), Color(0xFFF6A23F)) { Icon(HomeArt.Sparkle, null, tint = Color.White, modifier = Modifier.size(23.dp)) }
                                }
                            }
                            Spacer(Modifier.height(16.dp)); Spacer(Modifier.weight(1f))
                            Column(Modifier.padding(horizontal = 13.5.dp), verticalArrangement = Arrangement.spacedBy(8.5.dp)) {
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    UtilityCard("Private", "Browse without\na trace", Color(0xFF8A7BFA), Color(0xFF5A48E2), Modifier.weight(1f), narrow, onPrivate) {
                                        Icon(HomeArt.Incognito, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                    }
                                    UtilityCard("Bookmarks", "Save your\nfavorite places", Color(0xFF55A6FB), Color(0xFF2C6DE8), Modifier.weight(1f), narrow, { onPanel("bookmarks") }) {
                                        Icon(Icons.Rounded.Bookmark, null, tint = Color.White, modifier = Modifier.size(30.dp))
                                    }
                                }
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    UtilityCard("History", "Pick up where\nyou left off", Color(0xFF5ADDB0), Color(0xFF26B48C), Modifier.weight(1f), narrow, { onPanel("history") }) {
                                        Icon(HomeArt.Clock, null, tint = Color.White, modifier = Modifier.size(30.dp))
                                    }
                                    UtilityCard("Tools", "Useful tools\nfor your browsing", Color(0xFFFFDC80), Color(0xFFF5B850), Modifier.weight(1f), narrow, { onPanel("tools") }) {
                                        Icon(Icons.Rounded.GridView, null, tint = Color(0xFF2D3474), modifier = Modifier.size(28.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(13.dp)); Spacer(Modifier.weight(1f))
                            VpnStrip(vpnActive, vpnLocation, vpnDetail, Modifier.padding(horizontal = 13.5.dp)) { onPanel("vpn") }
                            Spacer(Modifier.height(13.dp)); Spacer(Modifier.weight(1f))
                            DiscoveryBanner(Modifier.padding(horizontal = 13.5.dp).testTag("home-discovery")) { onOpen("https://en.wikipedia.org/wiki/Special:Random") }
                        }
                    }
                }
            }
        }
    }
}

/** Home typography: no Material body tracking or 24 sp leading, so small labels sit like the reference. */
@Composable private fun HomeTextStyle(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified),
        content = content,
    )
}

/**
 * The approved composition assumes a 30 dp status bar. Taller bars (cutouts) push the greeting down
 * but move the artwork only beyond 42 dp, so the page below never loses room to a camera notch.
 */
private fun heroAnchor(statusBar: Dp): Dp = maxOf(30.dp, statusBar - 12.dp)

/** Height of the hero (art, greeting and wordmark) above the search field. */
internal fun heroBottom(width: Dp, statusBar: Dp): Dp = heroAnchor(statusBar) + 202.dp * (width.value / REFERENCE_WIDTH)

/** Mylo on the moon with the wordmark, composed exactly as in the approved reference. */
@Composable internal fun HeroBackdrop(width: Dp, statusBar: Dp, content: @Composable BoxScope.() -> Unit = {}) {
    val art = ImageBitmap.imageResource(R.drawable.mylo_night_hero)
    val k = width.value / REFERENCE_WIDTH
    val anchor = heroAnchor(statusBar)
    Box(Modifier.fillMaxWidth().height(heroBottom(width, statusBar)).drawBehind {
        // Registered against the reference: the art is 1.3825 screen widths wide, shifted left by 0.292 widths.
        val w = size.width
        val artWidth = w * 1.3825f
        val artHeight = artWidth * art.height / art.width
        val top = (anchor.toPx() - w * .0959f).coerceAtMost(0f)
        drawImage(art, dstOffset = IntOffset((-w * .292f).roundToInt(), top.roundToInt()),
            dstSize = IntSize(artWidth.roundToInt(), artHeight.roundToInt()), filterQuality = FilterQuality.High)
        // Let the bottom edge of the art melt into the page behind the search bar.
        val fadeEnd = top + artHeight
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, HomeNight), startY = fadeEnd - 34.dp.toPx(), endY = fadeEnd),
            topLeft = Offset(0f, fadeEnd - 34.dp.toPx()), size = Size(w, 34.dp.toPx()))
    }) {
        Box(Modifier.matchParentSize().clearAndSetSemantics { contentDescription = "Mylo, a cheerful corgi sitting on the moon above a moonlit lake" })
        // Glowing star beside Mylo.
        Box(Modifier.offset(x = width * .5959f - 24.dp * k, y = anchor + 52.dp * k - 24.dp * k).size(48.dp * k)
            .background(Brush.radialGradient(0f to Color(0x66FFD978), .5f to Color(0x24FFD978), 1f to Color(0x00FFD978))), contentAlignment = Alignment.Center) {
            Image(rememberVectorPainter(HomeArt.Star), null, Modifier.size(28.dp * k).graphicsLayer { rotationZ = -6f })
        }
        Image(rememberVectorPainter(HomeArt.Wordmark), "Mylo",
            Modifier.offset(x = width * .0925f, y = anchor + 70.4.dp * k).width(width * .391f).aspectRatio(HomeArt.WORDMARK_ASPECT))
        Text("A brighter web awaits", fontSize = 15.sp * k, fontWeight = FontWeight.Medium, color = Color(0xFFDCDDF6), maxLines = 1,
            style = TextStyle(shadow = Shadow(Color(0x80040A24), Offset(0f, 2f), 8f)),
            modifier = Modifier.offset(x = width * .107f, y = anchor + 136.5.dp * k))
        content()
    }
}

/** Greeting and settings over the hero. */
@Composable private fun HomeHero(width: Dp, statusBar: Dp, onSettings: () -> Unit) {
    HeroBackdrop(width, statusBar) {
        Row(Modifier.padding(top = statusBar + 2.dp, start = 16.dp, end = 8.5.dp).fillMaxWidth().height(46.dp).testTag("home-header"), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(33.dp).background(Color(0xD91E2D5D), CircleShape).border(1.dp, Color(0x12FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.WbSunny, null, tint = Color(0xFFFFD45E), modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f).padding(start = 7.dp)) {
                Text("Good evening!", fontSize = 10.2.sp, fontWeight = FontWeight.Medium, color = HomeInk, maxLines = 1)
                Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Have a brighter browse", fontSize = 8.8.sp, color = Color(0xFFCBCDEB), maxLines = 1)
                    // The approved ☀️, drawn as an icon so it is yellow on every device's emoji font.
                    Icon(Icons.Rounded.WbSunny, null, tint = Color(0xFFFFCF4A), modifier = Modifier.padding(start = 3.dp).size(10.5.dp))
                }
            }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Open settings", onClick = onSettings), contentAlignment = Alignment.Center) {
                Box(Modifier.size(33.dp).background(Color(0xD92A3469), CircleShape).border(1.dp, Color(0x12FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Settings, "Settings", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

internal val SearchTextStyle = TextStyle(color = Color(0xFF1E2150), fontSize = 15.5.sp)
internal val SearchPlaceholderStyle = TextStyle(color = Color(0xFF4A4E7E), fontSize = 15.5.sp, letterSpacing = .1.sp)

/** The large rounded search pill with a soft lavender glow; brighter edge while focused. */
internal fun Modifier.searchPill(focused: Boolean) = fillMaxWidth().height(58.dp)
    .shadow(if (focused) 22.dp else 16.dp, CircleShape, ambientColor = Color(0xFF8E7CFF), spotColor = Color(0xFF8E7CFF))
    .clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFF0F0FE), Color(0xFFE4E5FB))))
    .border(if (focused) 1.5.dp else 1.dp, if (focused) Color(0xFFB4A8FF) else Color(0x66FFFFFF), CircleShape)

/**
 * Home's own search box: the user types here and the keyboard's Search action submits. Words go to
 * the provider saved in Settings and web addresses open directly; there is no separate search page.
 */
@Composable private fun SearchBar(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit, onVoice: () -> Unit, onScan: () -> Unit, requester: FocusRequester, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.searchPill(focused).padding(start = 5.5.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { runCatching { requester.requestFocus() }; keyboard?.show() }) { Icon(Icons.Rounded.Search, "Focus search", tint = SearchInk, modifier = Modifier.size(31.dp)) }
        BasicTextField(value, onValue, Modifier.weight(1f).padding(start = 8.dp).focusRequester(requester).onFocusChanged { focused = it.isFocused }
            .testTag("search-input").semantics { contentDescription = "Search or enter address" }, singleLine = true,
            textStyle = SearchTextStyle, cursorBrush = SolidColor(Color(0xFF493B96)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (value.isEmpty()) Text("Search or enter address", style = SearchPlaceholderStyle, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } })
        if (value.isNotBlank()) IconButton(onClick = onSubmit) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Go", tint = SearchInk) }
        Box(Modifier.width(1.dp).height(23.dp).background(Color(0xFFBFC1E0)))
        Spacer(Modifier.width(5.dp))
        IconButton(onClick = onVoice, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Mic, "Voice search", tint = SearchInk, modifier = Modifier.size(26.dp)) }
        IconButton(onClick = onScan, modifier = Modifier.size(44.dp)) { Icon(HomeArt.Scanner, "Scan a code", tint = SearchInk, modifier = Modifier.size(26.dp)) }
    }
}

@Composable private fun Shortcut(label: String, ring: Color, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(Modifier.width(80.dp).pressScale(source).clip(RoundedCornerShape(18.dp)).clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(67.dp).background(Brush.radialGradient(listOf(ring, ring.copy(alpha = .92f).compositeOver(HomeNight))), CircleShape)
            .border(1.dp, Color(0x10FFFFFF), CircleShape), contentAlignment = Alignment.Center) { glyph() }
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEDEBF8), maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable private fun ShortcutDisk(light: Color, deep: Color, glyph: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).background(Brush.linearGradient(listOf(light, deep)), CircleShape), contentAlignment = Alignment.Center) { glyph() }
}

@Composable private fun UtilityCard(title: String, subtitle: String, light: Color, deep: Color, modifier: Modifier, narrow: Boolean, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    // Narrow phones give the two-line subtitles a little more room beside the icon.
    val textGap = if (narrow) 11.dp else 16.dp
    val shape = RoundedCornerShape(16.dp)
    val source = remember { MutableInteractionSource() }
    Row(modifier.pressScale(source).fillMaxHeight().heightIn(min = 74.dp).clip(shape).background(Brush.linearGradient(listOf(Color(0xFF1A2850), Color(0xFF101F41), Color(0xFF0F1E40))))
        .border(1.dp, CardEdge, shape).clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick).padding(start = 12.5.dp, end = 8.5.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Brush.linearGradient(listOf(light, deep)), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { glyph() }
        Column(Modifier.weight(1f).padding(start = textGap, top = 9.dp)) {
            Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = HomeInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 10.25.sp, lineHeight = 13.sp, color = HomeMuted, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFB9BFDE), modifier = Modifier.requiredSize(20.dp))
        }
    }
}

@Composable private fun VpnStrip(active: Boolean, location: String?, detail: String?, modifier: Modifier, onClick: () -> Unit) {
    // Live status is never simulated: "protected" appears only for a connected Mylo Shield tunnel or when
    // Android reports another app's VPN; [detail] carries Shield's real state.
    val on = active
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.fillMaxWidth().heightIn(min = 58.dp).clip(shape)
        .background(Brush.horizontalGradient(0f to Color(0xFF123049), .42f to Color(0xFF111F42), 1f to Color(0xFF14223F)))
        .border(1.dp, Color(0xFF1B3352), shape).clickable(role = Role.Button, onClick = onClick).padding(start = 5.5.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(48.dp).brushTint(Brush.verticalGradient(listOf(Color(0xFF4FD0A8), Color(0xFF22B087)))))
            Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.padding(bottom = 1.dp).size(15.dp))
        }
        Column(Modifier.weight(1f).padding(start = 4.5.dp, end = 6.dp)) {
            Text(if (on) "VPN protected" else "VPN protection", fontSize = 12.75.sp, fontWeight = FontWeight.SemiBold, color = HomeInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail ?: if (on) "Your connection is secure" else "Not connected", fontSize = 9.75.sp, color = HomeMuted, maxLines = 2, modifier = Modifier.padding(top = 1.dp))
        }
        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFF33456A)))
        Row(Modifier.padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                if (location == "Singapore") SingaporeFlag()
                else Box(Modifier.fillMaxSize().background(Color(0xFF22345A)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Public, null, tint = Color(0xFF9FC6F5), modifier = Modifier.size(15.dp)) }
            }
            Text(location ?: if (active) "System VPN" else "Set up", fontSize = 10.15.sp, fontWeight = FontWeight.Medium, color = HomeInk, maxLines = 1,
                modifier = Modifier.padding(start = 9.5.dp))
            Icon(Icons.Rounded.ExpandMore, null, tint = Color(0xFFC9CDE6), modifier = Modifier.padding(start = 3.dp).size(15.dp))
        }
        Spacer(Modifier.width(15.dp))
        MyloToggle(on, onClick)
    }
}

/** Compact switch in the approved proportions; it opens Mylo Shield. */
@Composable private fun MyloToggle(checked: Boolean, onClick: () -> Unit) {
    val track by animateColorAsState(if (checked) Color(0xFF34C18E) else Color(0xFF3A4766), label = "track")
    val thumbX by animateDpAsState(if (checked) 21.5.dp else 1.5.dp, label = "thumb")
    Box(Modifier.size(42.dp, 48.dp).toggleable(checked, role = Role.Switch, onValueChange = { onClick() }), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.size(42.dp, 22.5.dp).background(track, CircleShape)) {
            Box(Modifier.offset(x = thumbX, y = 1.5.dp).size(19.5.dp).shadow(1.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}

@Composable private fun DiscoveryBanner(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val source = remember { MutableInteractionSource() }
    Box(modifier.pressScale(source).fillMaxWidth().height(92.dp).clip(shape).background(Color(0xFF14245C)).clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick)) {
        Image(painterResource(R.drawable.mylo_discovery_night), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Box(Modifier.matchParentSize().border(1.dp, Color(0x33A9B7FF), shape))
        Column(Modifier.padding(start = 22.dp, top = 10.dp)) {
            Text("A little more\nwonder", fontFamily = MyloRounded, fontWeight = FontWeight.Black, fontSize = 19.5.sp, lineHeight = 20.5.sp, color = Color(0xFFF6F4FF),
                style = TextStyle(shadow = Shadow(Color(0x66050B2A), Offset(0f, 2f), 6f)))
            Row(Modifier.padding(top = 2.5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Explore today", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE3DFF8))
                Box(Modifier.padding(start = 7.5.dp).size(22.dp).background(Color(0xFFD9D3FB), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFF3B3478), modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable fun BottomBar(home: Boolean, tabs: Int, onHome: () -> Unit, onSearch: () -> Unit, onTabs: () -> Unit, onMylo: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    HomeTextStyle {
        Row(Modifier.padding(start = 13.5.dp, end = 13.5.dp, top = 6.dp, bottom = 6.5.dp).fillMaxWidth().height(62.dp).testTag("home-bottom-nav").clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF14244A), Color(0xFF101F40)))).border(1.dp, CardBorder, shape), verticalAlignment = Alignment.CenterVertically) {
            NavItem("Home", home, Modifier.weight(1f), onHome) { tint -> Icon(Icons.Rounded.Home, null, tint = tint, modifier = Modifier.size(21.dp)) }
            NavItem("Search", false, Modifier.weight(1f), onSearch) { tint -> Icon(HomeArt.SearchThin, null, tint = tint, modifier = Modifier.size(26.dp)) }
            NavItem("Tabs", false, Modifier.weight(1f), onTabs) { tint ->
                Box(Modifier.size(19.dp).border(1.6.dp, tint, RoundedCornerShape(4.5.dp)), contentAlignment = Alignment.Center) {
                    Text(if (tabs > 99) "99+" else tabs.toString(), fontSize = 9.5.sp, color = tint, fontWeight = FontWeight.Medium)
                }
            }
            NavItem("Mylo", false, Modifier.weight(1f), onMylo) { tint -> Icon(HomeArt.MyloFace, null, tint = tint, modifier = Modifier.size(28.dp)) }
        }
    }
}

@Composable private fun NavItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).selectable(selected, role = Role.Tab, onClick = onClick).padding(top = 6.5.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val pill by animateColorAsState(if (selected) Color(0xFFCFC9FB) else Color(0x00CFC9FB), tween(220), label = "nav")
        Box(Modifier.size(54.5.dp, 31.5.dp).background(pill, CircleShape), contentAlignment = Alignment.Center) {
            icon(if (selected) Color(0xFF2E2B7E) else NavInk)
        }
        Text(label, fontSize = 10.75.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color(0xFFC4BDFF) else Color(0xFFCDD0E6), modifier = Modifier.padding(top = 2.3.dp))
    }
}

/** A very slight press-in for Home's tappable surfaces, alongside the ripple. */
@Composable private fun Modifier.pressScale(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(120), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Paints the content in [brush], keeping its shape (used for the gradient VPN shield). */
private fun Modifier.brushTint(brush: Brush) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.SrcIn) }

/** Flag for the approved reference sample, or a connected Mylo Shield gateway that really is in Singapore. */
@Composable private fun SingaporeFlag() {
    Canvas(Modifier.fillMaxSize()) {
        val u = size.width / 22f
        drawRect(Color(0xFFEF3340), size = size.copy(height = size.height / 2))
        drawRect(Color.White, topLeft = Offset(0f, size.height / 2), size = size.copy(height = size.height / 2))
        drawCircle(Color.White, 3.7f * u, Offset(7.2f * u, 5.6f * u))
        drawCircle(Color(0xFFEF3340), 3.3f * u, Offset(8.5f * u, 5.6f * u))
        repeat(5) { i ->
            val a = Math.toRadians(-90.0 + i * 72.0)
            drawCircle(Color.White, .55f * u, Offset((11.6 + 2.1 * cos(a)).toFloat() * u, (5.7 + 2.1 * sin(a)).toFloat() * u))
        }
    }
}

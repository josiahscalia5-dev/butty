package com.mylo.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

internal val HomeNight = Color(0xFF071632)
private val HomeMuted = Color(0xFFB8B9DB)
private val HomeLavender = Color(0xFFD5C9FF)
private val HomeInk = Color(0xFF283370)
private val LocalHomeArtwork = staticCompositionLocalOf<ImageBitmap?> { null }

/** Closely related native previews; the approved hero and wording stay identical. */
enum class HomePolish(val displayName: String) {
    REFERENCE("Reference balance"),
    SEARCH_FOCUS("Search focus"),
    ROOMY_CARDS("Roomier cards"),
}

private data class HomePolishMetrics(
    val searchHeight: Float = 81f,
    val searchInset: Float = 18f,
    val searchElevation: Dp = 2.dp,
    val searchToShortcuts: Float = 25f,
    val shortcutDiameter: Float = 104f,
    val shortcutsToCards: Float = 17f,
    val cardHeight: Float = 110f,
    val cardGap: Float = 14f,
)

// Every variant retains the reference's 1120-unit height through the banner;
// small changes trade space between neighboring controls, never from the hero.
private fun HomePolish.metrics(): HomePolishMetrics = when (this) {
    HomePolish.REFERENCE -> HomePolishMetrics()
    HomePolish.SEARCH_FOCUS -> HomePolishMetrics(
        searchHeight = 90f, searchInset = 16f, searchElevation = 3.dp,
        shortcutDiameter = 98f, shortcutsToCards = 14f,
    )
    HomePolish.ROOMY_CARDS -> HomePolishMetrics(
        searchToShortcuts = 19f, shortcutDiameter = 96f, shortcutsToCards = 13f,
        cardHeight = 118f, cardGap = 16f,
    )
}

/** Native animation observes Compose's MotionDurationScale, including disabled animations. */
@Composable
private fun Modifier.homePressable(
    onClick: () -> Unit,
    shape: Shape? = null,
    rippleColor: Color = HomeLavender,
    pressedScale: Float = .985f,
): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "Home press feedback",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (shape != null) Modifier.clip(shape) else Modifier)
        .clickable(interactionSource = interactions, indication = ripple(color = rippleColor),
            role = Role.Button, onClick = onClick)
}

@Composable
private fun HomeArtwork(content: @Composable () -> Unit) {
    val artwork = ImageBitmap.imageResource(R.drawable.approved_home)
    CompositionLocalProvider(
        LocalHomeArtwork provides artwork,
        LocalTextStyle provides LocalTextStyle.current.copy(letterSpacing = 0.sp),
        LocalContentColor provides Color(0xFFF6F4FF),
        content = content,
    )
}

/**
 * Coordinates refer to the user's 589 × 1280 approved artwork, never to a device.
 * Original static artwork and lettering are sampled; search, cards, VPN and navigation
 * remain independent native controls. No status bar or complete UI screenshot is drawn.
 */
@Composable
private fun ApprovedArt(x: Int, y: Int, width: Int, height: Int, modifier: Modifier, description: String? = null) {
    val source = LocalHomeArtwork.current ?: ImageBitmap.imageResource(R.drawable.approved_home)
    val painter = remember(source, x, y, width, height) {
        BitmapPainter(source, srcOffset = IntOffset(x, y), srcSize = IntSize(width, height))
    }
    Image(painter, description, modifier, contentScale = ContentScale.FillBounds)
}

/** The hero and search stay available; only the middle scrolls on short/font-enlarged screens. */
@Composable
fun HomeScreen(
    query: String = "", onQuery: (String) -> Unit = {}, onSearch: () -> Unit = {}, onVoice: () -> Unit = {},
    onPanel: (String) -> Unit = {}, onOpen: (String) -> Unit = {}, onPrivate: () -> Unit = {},
    vpnActive: Boolean = false, searchRequest: Int = 0,
    polish: HomePolish = HomePolish.REFERENCE,
    onScanner: () -> Unit = { onPanel("tools") },
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchRequest) { if (searchRequest > 0) { requester.requestFocus(); keyboard?.show() } }
    HomeArtwork {
    BoxWithConstraints(Modifier.fillMaxSize().background(HomeNight)) {
        val unit = maxWidth / 589f
        val metrics = polish.metrics()
        Column(Modifier.fillMaxSize()) {
            HomeHero(unit, onPanel)
            HomeSearchBar(query, onQuery, onSearch, onVoice, requester,
                onScanner = onScanner, unit = unit, metrics = metrics)
            Column(Modifier.weight(1f).fillMaxWidth().testTag("home-middle")
                .verticalScroll(rememberScrollState()).padding(horizontal = unit * 20f)) {
                Spacer(Modifier.height(unit * metrics.searchToShortcuts))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HomeShortcut("Explore", 39, 467, unit, metrics) { onOpen("https://en.wikipedia.org/wiki/Special:Random") }
                    HomeShortcut("Videos", 175, 467, unit, metrics) { onOpen("https://m.youtube.com") }
                    HomeShortcut("Shop", 310, 467, unit, metrics) { onOpen("https://www.amazon.com") }
                    HomeShortcut("AI", 446, 467, unit, metrics) { onOpen("https://chatgpt.com") }
                }
                Spacer(Modifier.height(unit * metrics.shortcutsToCards))
                Row(horizontalArrangement = Arrangement.spacedBy(unit * 12f)) {
                    HomeCard("Private", "Browse without\na trace", 39, 641, unit, metrics, Modifier.weight(1f), onPrivate)
                    HomeCard("Bookmarks", "Save your\nfavorite places", 320, 641, unit, metrics, Modifier.weight(1f)) { onPanel("bookmarks") }
                }
                Spacer(Modifier.height(unit * metrics.cardGap))
                Row(horizontalArrangement = Arrangement.spacedBy(unit * 12f)) {
                    HomeCard("History", "Pick up where\nyou left off", 39, 765, unit, metrics, Modifier.weight(1f)) { onPanel("history") }
                    HomeCard("Tools", "Useful tools\nfor your browsing", 320, 765, unit, metrics, Modifier.weight(1f)) { onPanel("tools") }
                }
                Spacer(Modifier.height(unit * 14f))
                HomeVpnStrip(vpnActive, unit) { onPanel("vpn") }
                Spacer(Modifier.height(unit * 14f))
                HomeDiscovery(unit) { onOpen("https://en.wikipedia.org/wiki/Special:Random") }
            }
        }
    }
    }
}

@Composable
private fun HomeHero(unit: Dp, onPanel: (String) -> Unit) {
    val safeTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val headerTop = maxOf(unit * 55f, safeTop)
    Box(Modifier.fillMaxWidth().height(unit * 363f)) {
        // Preserve the uninterrupted original hero, including its fixed greeting and
        // hand-lettered wordmark. The sample status bar is deliberately outside this crop.
        // Match the clear sky behind Android's real status bar without stretching
        // a bitmap row or retaining JPEG traces of the reference's status icons.
        Box(Modifier.fillMaxWidth().height(unit * 44f).background(Brush.horizontalGradient(listOf(
            Color(0xFF0D1B40), Color(0xFF0B193E), Color(0xFF0A1A3E), Color(0xFF0C1D46),
            Color(0xFF0F204C), Color(0xFF101F4A), Color(0xFF09173A),
        ))))
        ApprovedArt(0, 44, 589, 319, Modifier.offset(y = unit * 44f).fillMaxWidth().height(unit * 319f),
            "Mylo. A brighter web awaits. A corgi on the moon above a nighttime lake.")
        // Accessible native hit targets over the fixed header artwork. Their bounds
        // remain below the actual cutout; the decorative sky can draw edge to edge.
        Row(Modifier.offset(y = headerTop).fillMaxWidth()
            .padding(horizontal = unit * 24f).height(48.dp).testTag("home-header"),
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(unit * 51f))
            Column(Modifier.weight(1f).align(Alignment.Top)
                .padding(start = unit * 9f, top = (unit * 68f - headerTop).coerceAtLeast(0.dp))) {
                Box(Modifier.fillMaxWidth().height(unit * 19f).semantics { text = AnnotatedString("Good evening!") })
                Box(Modifier.fillMaxWidth().height(unit * 18f).semantics { text = AnnotatedString("Have a brighter browse ☀️") })
            }
            Box(Modifier.size(48.dp).homePressable(onClick = { onPanel("settings") }, shape = CircleShape)
                .semantics { contentDescription = "Settings" })
        }
    }
}

@Composable
private fun HomeSearchBar(
    value: String, onValue: (String) -> Unit, onSubmit: () -> Unit, onVoice: () -> Unit,
    requester: FocusRequester, onScanner: () -> Unit, unit: Dp,
    metrics: HomePolishMetrics,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(50)
    Row(Modifier.padding(horizontal = unit * metrics.searchInset).fillMaxWidth().height(unit * metrics.searchHeight)
        .testTag("home-search")
        .shadow(metrics.searchElevation, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = .14f), spotColor = Color.Black.copy(alpha = .20f))
        .clip(shape)
        .background(Brush.horizontalGradient(listOf(Color(0xFFECE9FF), Color(0xFFE7E4FA)))),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(unit * 89f).fillMaxHeight().homePressable(onClick = {
            requester.requestFocus(); keyboard?.show()
        }, rippleColor = HomeInk, pressedScale = .96f)
            .semantics { contentDescription = "Focus search" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Search, null, tint = HomeInk, modifier = Modifier.size(unit * 42f))
        }
        BasicTextField(value, onValue, Modifier.weight(1f).fillMaxHeight().wrapContentHeight()
            .focusRequester(requester).testTag("home-search-input")
            .semantics { contentDescription = "Search or enter address" },
            singleLine = true,
            textStyle = TextStyle(color = Color(0xFF535777), fontSize = (unit.value * 23f).sp),
            cursorBrush = Brush.verticalGradient(listOf(HomeInk, HomeInk)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner -> Box {
                if (value.isEmpty()) Text("Search or enter address", color = Color(0xFF555976),
                    fontSize = (unit.value * 23f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            } })
        Box(Modifier.width(1.dp).height(unit * 34f).background(Color(0xFFC8C5DF)))
        Box(Modifier.width(unit * 76f).fillMaxHeight().homePressable(onVoice, rippleColor = HomeInk, pressedScale = .96f)
            .semantics { contentDescription = "Voice search" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, null, tint = HomeInk, modifier = Modifier.size(unit * 37f))
        }
        Box(Modifier.width(unit * 76f).fillMaxHeight().homePressable(onScanner, rippleColor = HomeInk, pressedScale = .96f)
            .semantics { contentDescription = "Scan QR code" }, contentAlignment = Alignment.Center) {
            ApprovedArt(508, 385, 37, 37, Modifier.size(unit * 37f))
        }
        Spacer(Modifier.width(unit * 1f))
    }
}

@Composable
private fun HomeShortcut(label: String, x: Int, y: Int, unit: Dp, metrics: HomePolishMetrics, onClick: () -> Unit) {
    Column(Modifier.width(unit * 137f).homePressable(onClick, shape = RoundedCornerShape(16.dp)),
        horizontalAlignment = Alignment.CenterHorizontally) {
        ApprovedArt(x, y, 104, 104, Modifier.size(unit * metrics.shortcutDiameter).clip(CircleShape))
        Text(label, fontSize = (unit.value * 18f).sp, lineHeight = (unit.value * 24f).sp,
            color = Color(0xFFF6F3FF), modifier = Modifier.padding(top = unit * 5f))
    }
}

@Composable
private fun HomeCard(title: String, subtitle: String, x: Int, y: Int, unit: Dp, metrics: HomePolishMetrics, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(unit * 22f)
    Row(modifier.heightIn(min = unit * metrics.cardHeight).homePressable(onClick, shape)
        .background(Brush.linearGradient(listOf(Color(0xFF162449), Color(0xFF111F40))))
        .border(.7.dp, Color(0xFF27335B), shape)
        .padding(start = unit * 19f, end = unit * 11f, top = unit * 15f, bottom = unit * 15f),
        verticalAlignment = Alignment.CenterVertically) {
        ApprovedArt(x, y, 66, 67, Modifier.size(unit * 66f, unit * 67f).clip(RoundedCornerShape(unit * 21f)))
        Column(Modifier.weight(1f).padding(start = unit * 22f)) {
            Text(title, fontSize = (unit.value * 18f).sp, lineHeight = (unit.value * 23f).sp,
                fontWeight = FontWeight.SemiBold, color = Color(0xFFF7F6FB), maxLines = 2)
            Text(subtitle, fontSize = (unit.value * 16f).sp, lineHeight = (unit.value * 20f).sp,
                color = HomeMuted, modifier = Modifier.padding(top = unit * 3f))
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFD8DCF8),
            modifier = Modifier.size(unit * 22f))
    }
}

@Composable
private fun HomeVpnStrip(active: Boolean, unit: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(unit * 21f)
    Row(Modifier.fillMaxWidth().heightIn(min = unit * 86f).testTag("home-shield")
        .homePressable(onClick, shape)
        .background(Brush.horizontalGradient(listOf(Color(0xFF10323F), Color(0xFF152443), Color(0xFF142140))))
        .border(.7.dp, Color(0xFF2B455D), shape)
        .padding(horizontal = unit * 19f, vertical = unit * 10f), verticalAlignment = Alignment.CenterVertically) {
        ApprovedArt(39, 884, 53, 59, Modifier.size(unit * 53f, unit * 59f))
        Column(Modifier.weight(1f).padding(start = unit * 15f, end = unit * 8f)) {
            Text("Mylo Shield", fontSize = (unit.value * 18f).sp,
                lineHeight = (unit.value * 23f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(if (active) "VPN connected" else "Not connected", fontSize = (unit.value * 14f).sp,
                lineHeight = (unit.value * 20f).sp,
                color = if (active) Color(0xFF8BE1C5) else HomeMuted, maxLines = 1)
        }
        // This opens system VPN setup; it never pretends to switch on a Mylo VPN.
        Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF264653))
            .padding(horizontal = unit * 16f, vertical = unit * 11f),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (active) "Manage" else "Set up", fontSize = (unit.value * 16f).sp,
                color = Color(0xFFD5F1E9), maxLines = 1)
            Spacer(Modifier.width(unit * 8f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                Modifier.size(unit * 20f), tint = Color(0xFF9BD8C7))
        }
    }
}

@Composable
private fun HomeDiscovery(unit: Dp, onClick: () -> Unit) {
    // Preserve the original illustrated campaign lettering as well as the entire lake/cabin scene.
    ApprovedArt(20, 968, 550, 153,
        Modifier.fillMaxWidth().height(unit * 153f).testTag("home-discovery")
            .homePressable(onClick, shape = RoundedCornerShape(unit * 21f)),
        "A little more wonder. Explore today.")
}

@Composable
fun BottomBar(home: Boolean, tabs: Int, onHome: () -> Unit, onSearch: () -> Unit, onTabs: () -> Unit, onMylo: () -> Unit) {
    HomeArtwork {
    BoxWithConstraints(Modifier.fillMaxWidth().background(HomeNight)) {
        val unit = maxWidth / 589f
        val shape = RoundedCornerShape(unit * 24f)
        Row(Modifier.padding(start = unit * 20f, end = unit * 20f, top = unit * 12f, bottom = unit * 8f)
            .fillMaxWidth().heightIn(min = unit * 97f).testTag("home-bottom-nav").clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF182646), Color(0xFF13213E))))
            .border(.7.dp, Color(0xFF2C3959), shape), verticalAlignment = Alignment.CenterVertically) {
            HomeNavItem("Home", home, unit, Modifier.weight(1f), onHome) {
                Icon(Icons.Rounded.Home, null, tint = if (home) Color(0xFF413591) else HomeMuted,
                    modifier = Modifier.size(unit * 32f))
            }
            HomeNavItem("Search", false, unit, Modifier.weight(1f), onSearch) {
                Icon(Icons.Rounded.Search, null, tint = Color(0xFFD1D0E8), modifier = Modifier.size(unit * 38f))
            }
            HomeNavItem("Tabs", false, unit, Modifier.weight(1f), onTabs) {
                Box(Modifier.size(unit * 28f, unit * 29f).border(1.3.dp, Color(0xFFCACDE6), RoundedCornerShape(unit * 5f)),
                    contentAlignment = Alignment.Center) {
                    Text(if (tabs > 99) "99+" else tabs.toString(), color = Color(0xFFDDDDF1), fontSize = (unit.value * 16f).sp)
                }
            }
            HomeNavItem("Mylo", false, unit, Modifier.weight(1f), onMylo) {
                ApprovedArt(486, 1154, 38, 38, Modifier.size(unit * 38f).clip(CircleShape))
            }
        }
    }
    }
}

@Composable
private fun HomeNavItem(label: String, selected: Boolean, unit: Dp, modifier: Modifier, onClick: () -> Unit,
    icon: @Composable () -> Unit) {
    val selectionColor by animateColorAsState(
        if (selected) HomeLavender else Color.Transparent,
        animationSpec = tween(150), label = "Home navigation selection",
    )
    Column(modifier.heightIn(min = unit * 97f).homePressable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(unit * 83f, unit * 49f).background(selectionColor, CircleShape),
            contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = (unit.value * 17f).sp, lineHeight = (unit.value * 24f).sp,
            color = if (selected) HomeLavender else Color(0xFFC9C8DF),
            modifier = Modifier.padding(top = unit * 1f))
    }
}

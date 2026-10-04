package com.mylo.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * Coordinates refer to the user's 589 × 1280 approved artwork, never to a device.
 * Only the original illustrations are sampled: search, text, cards, VPN and navigation
 * remain independent native controls. No status bar or complete UI screenshot is drawn.
 */
@Composable
private fun ApprovedArt(x: Int, y: Int, width: Int, height: Int, modifier: Modifier, description: String? = null) {
    val source = ImageBitmap.imageResource(R.drawable.approved_home)
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
    vpnActive: Boolean = false, searchRequest: Int = 0, onActivateSearch: (() -> Unit)? = null,
    // Production has no known endpoint location. The Android reference render supplies Singapore.
    vpnLocation: String? = null,
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchRequest) { if (searchRequest > 0) { requester.requestFocus(); keyboard?.show() } }
    BoxWithConstraints(Modifier.fillMaxSize().background(HomeNight)) {
        val unit = maxWidth / 589f
        Column(Modifier.fillMaxSize()) {
            HomeHero(unit, onPanel)
            HomeSearchBar(query, onQuery, onSearch, onVoice, requester, onActivateSearch,
                onScanner = { onPanel("tools") }, unit = unit)
            Column(Modifier.weight(1f).fillMaxWidth().testTag("home-middle")
                .verticalScroll(rememberScrollState()).padding(horizontal = unit * 20f)) {
                Spacer(Modifier.height(unit * 25f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HomeShortcut("Explore", 39, 467, unit) { onOpen("https://en.wikipedia.org/wiki/Special:Random") }
                    HomeShortcut("Videos", 175, 467, unit) { onOpen("https://m.youtube.com") }
                    HomeShortcut("Shop", 310, 467, unit) { onOpen("https://www.amazon.com") }
                    HomeShortcut("AI", 446, 467, unit) { onOpen("https://chatgpt.com") }
                }
                Spacer(Modifier.height(unit * 17f))
                Row(horizontalArrangement = Arrangement.spacedBy(unit * 12f)) {
                    HomeCard("Private", "Browse without\na trace", 39, 641, unit, Modifier.weight(1f), onPrivate)
                    HomeCard("Bookmarks", "Save your\nfavorite places", 320, 641, unit, Modifier.weight(1f)) { onPanel("bookmarks") }
                }
                Spacer(Modifier.height(unit * 14f))
                Row(horizontalArrangement = Arrangement.spacedBy(unit * 12f)) {
                    HomeCard("History", "Pick up where\nyou left off", 39, 765, unit, Modifier.weight(1f)) { onPanel("history") }
                    HomeCard("Tools", "Useful tools\nfor your browsing", 320, 765, unit, Modifier.weight(1f)) { onPanel("tools") }
                }
                Spacer(Modifier.height(unit * 14f))
                HomeVpnStrip(vpnActive, vpnLocation, unit) { onPanel("vpn") }
                Spacer(Modifier.height(unit * 14f))
                HomeDiscovery(unit) { onOpen("https://en.wikipedia.org/wiki/Special:Random") }
            }
        }
    }
}

@Composable
private fun HomeHero(unit: Dp, onPanel: (String) -> Unit) {
    val safeTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().height(unit * 363f)
        .background(Brush.verticalGradient(listOf(Color(0xFF0E1C42), Color(0xFF12234F), HomeNight)))) {
        // Original stylized wordmark, subtitle, corgi, moon, star, mountains and lake.
        // Split above the illustration so the reference's baked greeting/status icons are excluded.
        ApprovedArt(0, 111, 589, 252, Modifier.fillMaxWidth().height(unit * 252f).align(Alignment.BottomCenter),
            "Mylo. A brighter web awaits. A corgi on the moon above a nighttime lake.")
        ApprovedArt(300, 75, 212, 36, Modifier.offset(x = unit * 300f, y = unit * 75f).size(unit * 212f, unit * 36f))
        Row(Modifier.offset(y = maxOf(unit * 55f, safeTop + 2.dp)).fillMaxWidth()
            .padding(horizontal = unit * 24f).heightIn(min = unit * 58f).testTag("home-header"),
            verticalAlignment = Alignment.CenterVertically) {
            ApprovedArt(24, 58, 51, 51, Modifier.size(unit * 51f).clip(CircleShape))
            Column(Modifier.weight(1f).padding(start = unit * 9f)) {
                Text("Good evening!", fontSize = (unit.value * 16f).sp, lineHeight = (unit.value * 19f).sp,
                    color = Color.White, maxLines = 1)
                Text("Have a brighter browse ☀️", fontSize = (unit.value * 14f).sp, lineHeight = (unit.value * 18f).sp,
                    color = Color(0xFFD0D0E8), maxLines = 1)
            }
            Box(Modifier.size(48.dp).clickable(role = Role.Button) { onPanel("settings") }
                .semantics { contentDescription = "Settings" }, contentAlignment = Alignment.Center) {
                ApprovedArt(515, 57, 51, 52, Modifier.size(unit * 51f).clip(CircleShape))
            }
        }
    }
}

@Composable
private fun HomeSearchBar(
    value: String, onValue: (String) -> Unit, onSubmit: () -> Unit, onVoice: () -> Unit,
    requester: FocusRequester, onActivate: (() -> Unit)?, onScanner: () -> Unit, unit: Dp,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(Modifier.padding(horizontal = unit * 18f).fillMaxWidth().height(unit * 81f)
        .testTag("home-search").clip(RoundedCornerShape(50))
        .background(Brush.horizontalGradient(listOf(Color(0xFFECE9FF), Color(0xFFE7E4FA)))),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(unit * 89f).fillMaxHeight().clickable(role = Role.Button) {
            if (onActivate != null) onActivate() else { requester.requestFocus(); keyboard?.show() }
        }.semantics { contentDescription = "Focus search" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Search, null, tint = HomeInk, modifier = Modifier.size(unit * 42f))
        }
        BasicTextField(value, onValue, Modifier.weight(1f).fillMaxHeight().wrapContentHeight()
            .focusRequester(requester).onFocusChanged { if (it.isFocused) onActivate?.invoke() }
            .semantics { contentDescription = "Search or enter address" },
            singleLine = true, readOnly = onActivate != null,
            textStyle = TextStyle(color = Color(0xFF535777), fontSize = (unit.value * 23f).sp),
            cursorBrush = Brush.verticalGradient(listOf(HomeInk, HomeInk)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            decorationBox = { inner -> Box {
                if (value.isEmpty()) Text("Search or enter address", color = Color(0xFF555976),
                    fontSize = (unit.value * 23f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            } })
        Box(Modifier.width(1.dp).height(unit * 34f).background(Color(0xFFC8C5DF)))
        Box(Modifier.width(unit * 76f).fillMaxHeight().clickable(role = Role.Button, onClick = onVoice)
            .semantics { contentDescription = "Voice search" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, null, tint = HomeInk, modifier = Modifier.size(unit * 37f))
        }
        Box(Modifier.width(unit * 76f).fillMaxHeight().clickable(role = Role.Button, onClick = onScanner)
            .semantics { contentDescription = "Scanner tools" }, contentAlignment = Alignment.Center) {
            ApprovedArt(508, 385, 37, 37, Modifier.size(unit * 37f))
        }
        Spacer(Modifier.width(unit * 1f))
    }
}

@Composable
private fun HomeShortcut(label: String, x: Int, y: Int, unit: Dp, onClick: () -> Unit) {
    Column(Modifier.width(unit * 137f).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally) {
        ApprovedArt(x, y, 104, 104, Modifier.size(unit * 104f, unit * 104f).clip(CircleShape))
        Text(label, fontSize = (unit.value * 18f).sp, lineHeight = (unit.value * 24f).sp,
            color = Color(0xFFF6F3FF), modifier = Modifier.padding(top = unit * 5f))
    }
}

@Composable
private fun HomeCard(title: String, subtitle: String, x: Int, y: Int, unit: Dp, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(unit * 22f)
    Row(modifier.heightIn(min = unit * 110f).clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xFF162449), Color(0xFF111F40))))
        .border(.7.dp, Color(0xFF34415F), shape).clickable(role = Role.Button, onClick = onClick)
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
private fun HomeVpnStrip(active: Boolean, location: String?, unit: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(unit * 21f)
    Row(Modifier.fillMaxWidth().heightIn(min = unit * 86f).clip(shape)
        .background(Brush.horizontalGradient(listOf(Color(0xFF10323F), Color(0xFF152443), Color(0xFF142140))))
        .border(.7.dp, Color(0xFF2B455D), shape).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = unit * 19f, vertical = unit * 10f), verticalAlignment = Alignment.CenterVertically) {
        ApprovedArt(39, 884, 53, 59, Modifier.size(unit * 53f, unit * 59f))
        Column(Modifier.weight(1f).padding(start = unit * 15f, end = unit * 8f)) {
            Text(if (active) "VPN protected" else "VPN not connected", fontSize = (unit.value * 18f).sp,
                lineHeight = (unit.value * 23f).sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(if (active) "Your connection is secure" else "Manage connection", fontSize = (unit.value * 14f).sp,
                lineHeight = (unit.value * 20f).sp, color = HomeMuted, maxLines = 2)
        }
        Box(Modifier.width(.7.dp).height(unit * 36f).background(Color(0xFF41495F)))
        Row(Modifier.width(unit * 183f).padding(horizontal = unit * 18f), verticalAlignment = Alignment.CenterVertically) {
            if (active && location == "Singapore") {
                ApprovedArt(317, 893, 35, 37, Modifier.size(unit * 35f, unit * 37f).clip(CircleShape))
                Spacer(Modifier.width(unit * 12f))
            }
            Text(if (active) location ?: "System VPN" else "Set up", modifier = Modifier.weight(1f),
                fontSize = (unit.value * 16f).sp, maxLines = 2, color = Color(0xFFEDECF7))
            Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(unit * 16f), tint = HomeMuted)
        }
        // This is an indicator of the real ConnectivityManager state; its action opens VPN settings.
        Box(Modifier.size(unit * 66f, unit * 35f).clip(CircleShape)
            .background(if (active) Color(0xFF32CA9B) else Color(0xFF3A4760))
            .semantics { contentDescription = if (active) "VPN connected" else "VPN disconnected" }) {
            Box(Modifier.padding(unit * 3f).size(unit * 29f).align(if (active) Alignment.CenterEnd else Alignment.CenterStart)
                .background(if (active) Color.White else Color(0xFFBFC7DA), CircleShape))
        }
    }
}

@Composable
private fun HomeDiscovery(unit: Dp, onClick: () -> Unit) {
    // Preserve the original illustrated campaign lettering as well as the entire lake/cabin scene.
    ApprovedArt(20, 968, 550, 153,
        Modifier.fillMaxWidth().height(unit * 153f).testTag("home-discovery")
            .clip(RoundedCornerShape(unit * 21f)).clickable(role = Role.Button, onClick = onClick),
        "A little more wonder. Explore today.")
}

@Composable
fun BottomBar(home: Boolean, tabs: Int, onHome: () -> Unit, onSearch: () -> Unit, onTabs: () -> Unit, onMylo: () -> Unit) {
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

@Composable
private fun HomeNavItem(label: String, selected: Boolean, unit: Dp, modifier: Modifier, onClick: () -> Unit,
    icon: @Composable () -> Unit) {
    Column(modifier.heightIn(min = unit * 97f).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(unit * 83f, unit * 49f).background(if (selected) HomeLavender else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = (unit.value * 17f).sp, lineHeight = (unit.value * 24f).sp,
            color = if (selected) HomeLavender else Color(0xFFC9C8DF),
            modifier = Modifier.padding(top = unit * 1f))
    }
}

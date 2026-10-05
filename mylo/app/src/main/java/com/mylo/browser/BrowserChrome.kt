package com.mylo.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

/*
 * Mylo's browser chrome, reproduced from the approved target design/reference/browser-reference.jpg
 * (the phone screen is 655 px wide there: 1.592 px per dp on a 411.4 dp phone). Measurements in dp from the
 * screen's left edge: Back centre 27.6, Forward centre 75.4, address pill 103.6–317.2 (45.5 tall), Bookmark
 * centre 346.4, More centre 388.5; the pill sits 7.2 dp above the page.
 */

/** Toolbar navy; the status bar above it is the same colour. */
internal val BrowserBar = Color(0xFF06122A)
private val PillFill = Color(0xFF0E1E41)
private val PillEdge = Brush.verticalGradient(listOf(Color(0xFF2E3C63), Color(0xFF1E2D4E)))
private val ChromeLavender = Color(0xFFDCD2FF)
private val ChromeDisabled = Color(0xFF6B7694)
private val PillInk = Color(0xFFE8E9FF)

/** The address as the resting pill shows it: the site's host without "www.", or the address itself when it has none. */
internal fun cleanHost(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase()
    return host?.removePrefix("www.")?.takeIf { it.isNotEmpty() } ?: url
}

/**
 * Back, Forward, the address pill (security icon, clean host at rest, the full editable address when tapped,
 * Reload or Stop), Bookmark and More. [menu] supplies the More menu's items.
 */
@Composable fun BrowserToolbar(
    url: String, loading: Boolean, progress: Int, canGoForward: Boolean,
    editing: Boolean, address: String, onAddress: (String) -> Unit, onEditing: (Boolean) -> Unit,
    onBack: () -> Unit, onForward: () -> Unit, onReloadOrStop: () -> Unit, onSubmit: () -> Unit,
    bookmarked: Boolean, onBookmark: (() -> Unit)?, privateMode: Boolean,
    menu: @Composable (open: Boolean, onDismiss: () -> Unit) -> Unit,
) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(cleanHost(url))) }
    // At rest the pill shows the clean host; editing starts from the full address, all selected.
    LaunchedEffect(url, editing) { if (!editing) field = TextFieldValue(cleanHost(url)) }
    var menuOpen by remember { mutableStateOf(false) }
    val secure = url.startsWith("https://")
    Box(Modifier.fillMaxWidth().background(BrowserBar).testTag("browser-toolbar")) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 7.2.dp).height(45.5.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(3.6.dp))
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(HomeArt.ArrowThin, "Back", tint = ChromeLavender, modifier = Modifier.size(24.1.dp))
            }
            IconButton(onClick = onForward, enabled = canGoForward, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = ChromeLavender, disabledContentColor = ChromeDisabled)) {
                Icon(HomeArt.ArrowThin, "Forward", modifier = Modifier.size(24.1.dp).graphicsLayer { scaleX = -1f })
            }
            Spacer(Modifier.width(4.dp))
            Row(Modifier.weight(1f).fillMaxHeight().background(PillFill, CircleShape).border(1.dp, PillEdge, CircleShape),
                verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(if (privateMode) 10.dp else 14.4.dp))
                if (privateMode) Icon(PrivateArt.Incognito, "Private tab", tint = ChromeLavender, modifier = Modifier.padding(end = 4.dp).size(18.dp))
                Icon(if (secure) HomeArt.PadlockSolid else Icons.Rounded.Info, if (secure) "Secure connection" else "Not secure",
                    tint = if (secure) PillInk else Color(0xFFFFC8A8), modifier = Modifier.size(19.2.dp))
                Spacer(Modifier.width(10.1.dp))
                BasicTextField(field, { field = it; onAddress(it.text) },
                    Modifier.weight(1f).focusRequester(requester).onFocusChanged { state ->
                        if (state.isFocused && !editing) {
                            field = TextFieldValue(url, TextRange(0, url.length))
                            onAddress(url)
                        }
                        onEditing(state.isFocused)
                    }.semantics { contentDescription = "Browser address" }.testTag("browser-address"),
                    singleLine = true, textStyle = sansStyle(14.8f, 19f, color = Color(0xFFF6F7FF)), cursorBrush = SolidColor(ChromeLavender),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }))
                if (editing) IconButton(onClick = { field = TextFieldValue(""); onAddress("") }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Close, "Clear address", tint = PillInk, modifier = Modifier.size(20.dp))
                } else IconButton(onClick = onReloadOrStop, modifier = Modifier.size(44.dp)) {
                    Icon(if (loading) HomeArt.StopThin else HomeArt.Reload, if (loading) "Stop" else "Reload", tint = PillInk, modifier = Modifier.size(24.7.dp))
                }
                Spacer(Modifier.width(3.1.dp))
            }
            // Bookmark and More: centres 29.2 and 71.3 dp into the last 94.2 dp; their 48 dp targets overlap slightly.
            Box(Modifier.width(94.2.dp).fillMaxHeight()) {
                if (onBookmark != null) IconButton(onClick = onBookmark, modifier = Modifier.offset(x = 5.2.dp).size(48.dp).align(Alignment.CenterStart)) {
                    Icon(if (bookmarked) HomeArt.BookmarkSolid else HomeArt.BookmarkOutline, if (bookmarked) "Bookmarked: show bookmarks" else "Bookmark this page",
                        tint = Color.White, modifier = Modifier.size(27.4.dp))
                }
                Box(Modifier.offset(x = 47.3.dp).align(Alignment.CenterStart)) {
                    IconButton(onClick = { focus.clearFocus(); menuOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.MoreVert, "More page options", tint = Color.White, modifier = Modifier.size(25.5.dp))
                    }
                    menu(menuOpen) { menuOpen = false }
                }
            }
        }
        // Loading progress along the toolbar's bottom edge.
        if (loading) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).drawBehind {
            val fraction = (progress.coerceIn(5, 100)) / 100f
            drawLine(Brush.horizontalGradient(listOf(Color(0xFF9D8BFF), ChromeLavender)), Offset(0f, size.height / 2), Offset(size.width * fraction, size.height / 2), size.height)
        })
    }
}

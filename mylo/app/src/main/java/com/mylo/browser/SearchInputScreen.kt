package com.mylo.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SearchInk = Color(0xFF23286A)

/**
 * Focused search. Home's hero stays visible but dimmed and the field sits where Home's search bar
 * is, so focusing feels like the same screen. Every submission is resolved and loaded by the normal
 * browser: [provider] answers this search, [defaultProvider] is the saved choice.
 */
@Composable
fun SearchInputScreen(
    query: String,
    onQuery: (String) -> Unit,
    provider: SearchProvider,
    defaultProvider: SearchProvider,
    onUseOnce: (SearchProvider) -> Unit,
    onSetDefault: (SearchProvider) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onVoice: () -> Unit = {},
    onScan: () -> Unit = {},
    statusBarInset: Dp? = null,
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var choosingProvider by remember { mutableStateOf(false) }

    LaunchedEffect(choosingProvider) {
        if (!choosingProvider) {
            // Focus can fail while the field is detached; never let that cancel the screen's effects.
            runCatching { requester.requestFocus() }
            // Wait until the input connection has followed Compose focus.
            withFrameNanos { }
            runCatching { keyboard?.show() }
        }
    }
    BackHandler { if (choosingProvider) choosingProvider = false else onClose() }

    val statusBar = statusBarInset ?: WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    // Ease the panel in: the hero dims and the provider control fades up as focus arrives.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val reveal by animateFloatAsState(if (entered) 1f else 0f, tween(220), label = "reveal")

    BoxWithConstraints(Modifier.fillMaxSize().background(HomeNight)) {
        val width = maxWidth
        // In landscape or with very little room above the keyboard, the hero steps aside.
        val compact = maxHeight < 360.dp
        if (!compact) HeroBackdrop(width, statusBar, dim = .45f * reveal)
        Column(Modifier.fillMaxSize().padding(top = statusBar).testTag("search-input-mode")) {
            Spacer(Modifier.height(if (compact) 8.dp else heroBottom(width, statusBar) - statusBar))
            Row(Modifier.padding(horizontal = 12.dp).searchPill(focused = true).padding(start = 5.5.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search", tint = SearchInk, modifier = Modifier.size(25.dp)) }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.weight(1f).padding(start = 6.dp).focusRequester(requester).testTag("search-input")
                        .semantics { contentDescription = "Search or enter address" },
                    singleLine = true,
                    textStyle = SearchTextStyle,
                    cursorBrush = SolidColor(Color(0xFF5B4BD6)),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text("Search or enter address", style = SearchPlaceholderStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Close, "Clear search", tint = Color(0xFF5A5E8C), modifier = Modifier.size(20.dp))
                }
                Box(Modifier.width(1.dp).height(23.dp).background(Color(0xFFBFC1E0)))
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onVoice, modifier = Modifier.size(42.dp)) { Icon(Icons.Rounded.Mic, "Voice search", tint = SearchInk, modifier = Modifier.size(25.dp)) }
                IconButton(onClick = onScan, modifier = Modifier.size(42.dp)) { Icon(HomeArt.Scanner, "Scan a code", tint = SearchInk, modifier = Modifier.size(25.dp)) }
            }
            ProviderChip(provider, Modifier.padding(start = 18.dp, top = 12.dp).graphicsLayer { alpha = reveal; translationY = (1f - reveal) * 12f }) {
                choosingProvider = true
                keyboard?.hide()
            }
        }
    }

    if (choosingProvider) ProviderSheet(provider, defaultProvider, onDismiss = { choosingProvider = false },
        onUseOnce = { onUseOnce(it); choosingProvider = false },
        onSetDefault = { onSetDefault(it); choosingProvider = false })
}

@Composable private fun ProviderChip(provider: SearchProvider, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.height(36.dp).clip(CircleShape).background(Color(0xFF17244D)).border(1.dp, Color(0xFF2B3A6B), CircleShape)
        .clickable(onClickLabel = "Choose search engine", onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "Search provider: ${provider.displayName}" }
        .padding(start = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ProviderBadge(provider, 24.dp)
        Text("Search with ${provider.displayName}", color = Color(0xFFEDEBFF), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
        Icon(Icons.Rounded.ExpandMore, null, tint = Color(0xFFC9C3FF), modifier = Modifier.padding(start = 2.dp).size(18.dp))
    }
}

/** Rounded Mylo sheet listing every provider; the user picks one, then uses it once or saves it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ProviderSheet(
    active: SearchProvider,
    default: SearchProvider,
    onDismiss: () -> Unit,
    onUseOnce: (SearchProvider) -> Unit,
    onSetDefault: (SearchProvider) -> Unit,
) {
    var pending by remember(active) { mutableStateOf(active) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Color(0xFF111D42),
        scrimColor = Color(0xB3030918),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(36.dp, 4.dp).background(Color(0x66C9C3FF), CircleShape)) },
    ) {
        Column(Modifier.fillMaxWidth().testTag("search-provider-picker").padding(bottom = 16.dp)) {
            Text("Search engine", color = Color(0xFFF5F4FF), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 12.dp))
            Text("Results open on the provider's own page in Mylo.", color = Color(0xFF9FA8CF), fontSize = 12.5.sp,
                modifier = Modifier.padding(start = 24.dp, top = 3.dp, bottom = 10.dp))
            Column(Modifier.selectableGroup()) {
                SearchProvider.entries.forEach { provider ->
                    val selected = provider == pending
                    val shape = RoundedCornerShape(18.dp)
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().height(58.dp).clip(shape)
                        .background(if (selected) Color(0xFF26346A) else Color.Transparent)
                        .border(1.dp, if (selected) Color(0xFF6E62D9) else Color.Transparent, shape)
                        .selectable(selected, role = Role.RadioButton) { pending = provider }
                        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProviderBadge(provider, 34.dp)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(provider.displayName, color = Color(0xFFF2F1FF), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                if (provider == default) Text("Default", color = Color(0xFFC9C3FF), fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(start = 8.dp).background(Color(0xFF2F3B70), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                            Text(provider.domain, color = Color(0xFF8F98C0), fontSize = 12.sp)
                        }
                        if (selected) Box(Modifier.size(24.dp).background(Color(0xFFC6BCFF), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, "Selected", tint = Color(0xFF2B2370), modifier = Modifier.size(16.dp))
                        } else Box(Modifier.size(22.dp).border(1.5.dp, Color(0xFF46558A), CircleShape))
                    }
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(50.dp).clip(CircleShape).border(1.dp, Color(0xFF5A57A8), CircleShape)
                    .clickable(role = Role.Button) { onUseOnce(pending) }, contentAlignment = Alignment.Center) {
                    Text("Just this search", color = Color(0xFFE6E2FF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.weight(1f).height(50.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Color(0xFFD3CAFF), Color(0xFFB4A6FF))))
                    .clickable(role = Role.Button) { onSetDefault(pending) }, contentAlignment = Alignment.Center) {
                    Text("Set as default", color = Color(0xFF231C5C), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Brand-coloured letter badges. Official provider logos are not bundled: their trademark
 * guidelines restrict use without permission, so recognisable colours and initials stand in.
 */
@Composable fun ProviderBadge(provider: SearchProvider, size: Dp) {
    val (mark, background, ink) = when (provider) {
        SearchProvider.GOOGLE -> Triple("G", Brush.linearGradient(listOf(Color.White, Color(0xFFF1F3F4))), Color(0xFF4285F4))
        SearchProvider.BRAVE -> Triple("B", Brush.linearGradient(listOf(Color(0xFFFF7A3D), Color(0xFFE8421E))), Color.White)
        SearchProvider.DUCKDUCKGO -> Triple("D", Brush.linearGradient(listOf(Color(0xFFF0753F), Color(0xFFD64B2A))), Color.White)
        SearchProvider.BING -> Triple("b", Brush.linearGradient(listOf(Color(0xFF1FC3B9), Color(0xFF0A7C8A))), Color.White)
        SearchProvider.YAHOO -> Triple("Y!", Brush.linearGradient(listOf(Color(0xFF8B3DFF), Color(0xFF5A01C8))), Color.White)
        SearchProvider.STARTPAGE -> Triple("S", Brush.linearGradient(listOf(Color(0xFF7D8CFF), Color(0xFF4A5BE8))), Color.White)
    }
    Box(Modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Text(mark, color = ink, fontWeight = FontWeight.Bold, fontSize = (size.value * .46f).sp, lineHeight = (size.value * .46f).sp,
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.wrapContentSize(Alignment.Center))
    }
}

package com.mylo.browser

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mylo.browser.web.Origins
import com.mylo.browser.web.PageNotice
import com.mylo.browser.web.TabEngine
import com.mylo.browser.web.TabHost

private val BrowserLavender = Color(0xFFCEC5FF)

/**
 * One tab's page with its toolbar, for normal browsing and Private Mode alike (the same engine and
 * compatibility rules). [onBookmark] is null where bookmarks aren't offered (Private Mode, so nothing it
 * visits is written to normal storage); [privateMode] marks the toolbar so the user always knows.
 */
@Composable fun BrowserScreen(
    tab: BrowserTab,
    engine: TabEngine,
    host: TabHost,
    provider: SearchProvider,
    onHome: () -> Unit,
    onBookmark: ((url: String, title: String) -> Unit)?,
    onMessage: (String) -> Unit,
    privateMode: Boolean = false,
    /** Shown under the page (Mylo's Page Coach), when there is something to show. */
    underPage: (@Composable () -> Unit)? = null,
) {
    val page = engine.page(tab.id)
    val context = LocalContext.current
    var address by remember(tab.id) { mutableStateOf(page.url.ifBlank { tab.url }) }
    var editingAddress by remember(tab.id) { mutableStateOf(false) }
    var menuOpen by remember(tab.id) { mutableStateOf(false) }
    var siteSettings by remember(tab.id) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val currentUrl = page.url.ifBlank { tab.url }
    LaunchedEffect(currentUrl) { if (!editingAddress) address = currentUrl }

    fun back() {
        focus.clearFocus(); keyboard?.hide()
        // Back in the page's history; a pop-up with none closes back to the page that opened it.
        if (!engine.back(tab.id)) onHome()
    }
    fun submitAddress() {
        val url = resolveInput(address, provider)
        if (url == null) { onMessage("Enter a website address or search words."); return }
        address = url
        host.updateTab(tab.id, url, url)
        engine.load(tab.id, url)
        focus.clearFocus(); keyboard?.hide()
    }
    fun otherBrowser() = openInOtherBrowser(context, currentUrl) { onMessage("No other browser on this device can open this page.") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            IconButton(onClick = { engine.webViewIfLive(tab.id)?.goForward() }, enabled = page.canGoForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward") }
            OutlinedTextField(address, { address = it }, Modifier.weight(1f).padding(vertical = 5.dp).onFocusChanged { editingAddress = it.isFocused }.semantics { contentDescription = "Browser address" },
                textStyle = TextStyle(fontSize = 13.sp), singleLine = true, shape = RoundedCornerShape(20.dp),
                leadingIcon = if (privateMode) ({ Icon(PrivateArt.Incognito, "Private tab", tint = BrowserLavender, modifier = Modifier.size(20.dp)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submitAddress() }))
            IconButton(onClick = { engine.reload(tab) }) { Icon(Icons.Rounded.Refresh, "Reload") }
            if (onBookmark != null) IconButton(onClick = { onBookmark(currentUrl, page.title.ifBlank { tab.title }) }) { Icon(Icons.Rounded.BookmarkAdd, "Bookmark this page") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More page options") }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Open in another browser") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                        onClick = { menuOpen = false; otherBrowser() })
                    DropdownMenuItem(text = { Text("Site settings") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                        onClick = { menuOpen = false; siteSettings = true })
                }
            }
        }
        if (privateMode) PrivateStrip(page.trackersBlocked, engine.trackers?.enabled == true)
        if (page.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = BrowserLavender)
        page.error?.let { Text(it, modifier = Modifier.padding(16.dp), color = Color(0xFFFFCCCF)) }
        page.notice?.let { notice ->
            PageNoticeBar(notice, onDismiss = { engine.dismissNotice(tab.id) }, onAllowPopups = {
                (notice as? PageNotice.PopupBlocked)?.origin?.let(engine::allowPopups)
                engine.page(tab.id).notice = PageNotice.Info("Pop-ups are allowed on ${Origins.host(currentUrl)}. Tap the link again.")
            }, onOtherBrowser = { engine.dismissNotice(tab.id); otherBrowser() })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (page.crashed) CrashedPage { engine.reload(tab) }
            else key(tab.id) {
                // The tab's long-lived WebView is shown here and only detached (never destroyed) when hidden.
                AndroidView(factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val view = engine.webView(tab)
                        (view.parent as? ViewGroup)?.removeView(view)
                        addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                }, onRelease = { it.removeAllViews() }, modifier = Modifier.fillMaxSize())
            }
        }
        underPage?.invoke()
    }
    if (siteSettings) SiteSettingsSheet(engine, currentUrl) { siteSettings = false }
    BackHandler { back() }
    DisposableEffect(tab.id) {
        engine.setVisible(tab.id)
        onDispose { if (engine.visibleTab == tab.id) engine.setVisible(null) }
    }
}

/** A slim reminder under a private tab's toolbar, with this page's blocked-tracker count. */
@Composable private fun PrivateStrip(blocked: Int, blocking: Boolean) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF221C5E)).padding(horizontal = 14.dp, vertical = 4.dp).testTag("private-strip"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(Color(0xFF5EE6AB), CircleShape))
        Text("Private", Modifier.padding(start = 8.dp), color = Color(0xFFE6E0FF), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(when { !blocking -> "Tracker blocking is off"; blocked == 1 -> "1 tracker blocked on this page"; else -> "$blocked trackers blocked on this page" }, color = Color(0xFFB9B2EE), fontSize = 12.sp,
            modifier = Modifier.testTag("private-page-trackers"))
    }
}

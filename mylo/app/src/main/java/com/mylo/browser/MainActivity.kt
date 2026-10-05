package com.mylo.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Build
import android.speech.RecognizerIntent
import android.webkit.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.mylo.browser.shield.ExitStatus
import com.mylo.browser.shield.MyloShield
import com.mylo.browser.shield.ShieldProblem
import com.mylo.browser.shield.ShieldState
import com.mylo.browser.web.EngineEvent
import com.mylo.browser.web.ExternalApps
import com.mylo.browser.web.ExternalTarget
import com.mylo.browser.web.FileChooserRequest
import com.mylo.browser.web.NavigationDecision
import com.mylo.browser.web.WebPolicy
import com.mylo.browser.web.Origins
import com.mylo.browser.web.PageNotice
import com.mylo.browser.web.TabEngine
import android.view.ViewGroup
import android.widget.FrameLayout

val Night = Color(0xFF09142E)
private val Lavender = Color(0xFFCEC5FF)
private val Muted = Color(0xFFAEB9DB)
private val Panel = Color(0xFF162345)
private val Line = Color(0xFF303F67)

@Composable fun MyloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Lavender, onPrimary = Color(0xFF252058), background = Night,
        surface = Panel, onSurface = Color(0xFFF6F4FF), secondary = Color(0xFF67D9C2)), content = content)
}

/** Shared by the activity and the native portrait preview. Insets belong to the shell. */
@Composable fun MyloViewport(edgeToEdgeHome: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = if (edgeToEdgeHome) HomeNight else Night, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(if (edgeToEdgeHome) WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) else WindowInsets.safeDrawing)
                .imePadding(),
            content = content,
        )
    }
}

class MyloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().endsWith(":private")) WebView.setDataDirectorySuffix("private")
    }
}

class BrowserSession(application: Application) : AndroidViewModel(application) {
    val store = BrowserStore(application)
    /** Every normal tab's page, whichever search provider or link opened it. */
    val engine = TabEngine(application, store)

    override fun onCleared() = engine.destroyAll()
}

class MainActivity : ComponentActivity() {
    /** Counts "open Mylo Shield" requests from the Shield notification. */
    private val shieldRequests = mutableIntStateOf(0)
    private var engine: TabEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val session = ViewModelProvider(this)[BrowserSession::class.java]
        engine = session.engine.also { it.attach(this) }
        if (savedInstanceState == null && intent?.action == MyloShield.ACTION_OPEN_SHIELD) shieldRequests.intValue++
        setContent { MyloTheme { MyloApp(session, shieldRequests.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        engine?.onActivityResume()
    }

    override fun onPause() {
        engine?.onActivityPause()
        super.onPause()
    }

    override fun onDestroy() {
        engine?.detach(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == MyloShield.ACTION_OPEN_SHIELD) shieldRequests.intValue++
    }
}

@Composable fun MyloApp(session: BrowserSession, shieldRequest: Int = 0) {
    val store = session.store
    val engine = session.engine
    val context = LocalContext.current
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    val shield = remember { MyloShield.get(context) }
    val shieldState by shield.engine.state.collectAsState()
    var shieldOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(shieldRequest) { if (shieldRequest > 0) shieldOpen = true }
    LaunchedEffect(Unit) { shield.autoConnectOnLaunch(context) }
    var currentTab by rememberSaveable { mutableStateOf<Long?>(null) }
    var home by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // One-shot request (bottom Search, new tab) to focus Home's own search box; cleared once handled
    // so returning to Home later never reopens the keyboard by itself.
    var focusHomeSearch by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val vpn = rememberVpnStatus()
    fun dismissInput() { focus.clearFocus(); keyboard?.hide() }
    fun showHome() { dismissInput(); query = ""; home = true }
    fun searchFromHome() { showHome(); focusHomeSearch = true }
    fun open(input: String) {
        // Web addresses open directly; words search with the provider saved in Settings.
        val tab = store.navigateInCurrentTab(currentTab, input)
        if (tab == null) { error = "Enter a website address or search words."; return }
        // A Home submission is a navigation in the existing tab, never an implicit new tab.
        engine.load(tab.id, tab.url)
        currentTab = tab.id
        home = false
        query = ""
        dismissInput()
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { open(it) }
    }
    val voiceSearch: () -> Unit = {
        runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "Search with Mylo")) }
            .onFailure { error = "Voice search isn't available on this device. You can type your search." }
    }
    val scanCode: () -> Unit = {
        // Google's code scanner supplies its own camera UI; a scanned link or text opens like typed input.
        val unavailable = "The code scanner isn't available on this device. You can type the address instead."
        runCatching {
            GmsBarcodeScanning.getClient(context).startScan()
                .addOnSuccessListener { code -> code.rawValue?.takeIf { it.isNotBlank() }?.let { open(it) } }
                .addOnFailureListener { error = unavailable }
        }.onFailure { error = unavailable }
    }
    // Pages opening windows (pop-ups, target=_blank) show their new tab; a closing pop-up returns to its opener.
    LaunchedEffect(engine) {
        engine.events.collect { event ->
            when (event) {
                is EngineEvent.ShowTab -> { dismissInput(); panel = null; currentTab = event.tabId; home = false }
                EngineEvent.ShowHome -> showHome()
            }
        }
    }
    val onHome = home || store.tabs.none { it.id == currentTab }
    val homeVpn = homeVpnStatus(shieldState, vpn)
    // Mylo Shield replaces the browser while open; tabs keep their saved navigation state meanwhile.
    if (shieldOpen) {
        ShieldRoute(onClose = { shieldOpen = false })
        return
    }
    // Home draws its artwork behind the status bar; browser pages do not.
    Box(Modifier.fillMaxSize()) {
    MyloViewport(edgeToEdgeHome = onHome) {
            Box(Modifier.weight(1f)) {
                val tab = store.tabs.firstOrNull { it.id == currentTab }
                if (home || tab == null) {
                    HomeScreen(query, { query = it }, { open(query) }, voiceSearch, { if (it == "vpn") shieldOpen = true else panel = it }, { open(it) }, {
                        context.startActivity(Intent(context, PrivateActivity::class.java))
                    }, homeVpn.active, focusSearch = focusHomeSearch, onSearchFocused = { focusHomeSearch = false },
                        vpnLocation = homeVpn.location, vpnDetail = homeVpn.detail, onScan = scanCode)
                } else {
                    key(tab.id) {
                        BrowserScreen(tab, engine, store, ::showHome, { panel = "bookmarks" }, { error = it })
                    }
                }
            }
            BottomBar(onHome, store.tabs.size, ::showHome, ::searchFromHome, { panel = "tabs" }, { panel = "mylo" })
    }
    // Full-screen video sits above everything, with the page still attached underneath.
    engine.fullscreen?.let { FullscreenHost(it, engine::exitFullscreen) }
    }
    WebPromptHost(engine)
    FileChooserHost(engine)
    panel?.let { selected -> MyloPanel(selected, store, { panel = null }, { open(it) }, {
        dismissInput(); currentTab = it.id; home = it.url.isBlank() && !engine.isLive(it.id)
    }, { currentTab = store.createTab().id; searchFromHome() }, onOpenShield = { shieldOpen = true },
        onCloseTab = { engine.closeTab(it.id) }, favicon = { engine.page(it.id).favicon }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Mylo") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

/** What Home's VPN strip may say: only Mylo Shield's real state, or that another app's VPN is on. */
private data class HomeVpn(val active: Boolean, val location: String?, val detail: String)

private fun homeVpnStatus(shield: ShieldState, androidVpn: Boolean): HomeVpn {
    val idle = if (androidVpn) "Another VPN app is on" else null
    return when (shield) {
        is ShieldState.Connected -> HomeVpn(true, shield.server.city,
            if (shield.exit is ExitStatus.Verified) "Encrypted · exit verified" else "Encrypted · exit not verified")
        is ShieldState.Connecting -> HomeVpn(false, null, "Connecting…")
        is ShieldState.Reconnecting -> HomeVpn(false, null, "Reconnecting…")
        is ShieldState.Error -> HomeVpn(androidVpn, null, idle ?: if (shield.problem == ShieldProblem.NotConfigured) "Server setup required" else "Couldn't connect")
        is ShieldState.Disconnected -> HomeVpn(androidVpn, null, idle ?: "Not connected")
    }
}

@Composable private fun rememberVpnStatus(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(ConnectivityManager::class.java) }
    fun connected() = manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    var active by remember { mutableStateOf(connected()) }
    DisposableEffect(manager) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { handler.post { active = connected() } }
            override fun onLost(network: Network) { handler.post { active = connected() } }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { handler.post { active = connected() } }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return active
}

@Composable private fun BrowserScreen(
    tab: BrowserTab,
    engine: TabEngine,
    store: BrowserStore,
    onHome: () -> Unit,
    onBookmarks: () -> Unit,
    onMessage: (String) -> Unit,
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
        val url = resolveInput(address, store.provider)
        if (url == null) { onMessage("Enter a website address or search words."); return }
        address = url
        store.updateTab(tab.id, url, url)
        engine.load(tab.id, url)
        focus.clearFocus(); keyboard?.hide()
    }
    fun otherBrowser() = openInOtherBrowser(context, currentUrl) { onMessage("No other browser on this device can open this page.") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            IconButton(onClick = { engine.webViewIfLive(tab.id)?.goForward() }, enabled = page.canGoForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward") }
            OutlinedTextField(address, { address = it }, Modifier.weight(1f).padding(vertical = 5.dp).onFocusChanged { editingAddress = it.isFocused }.semantics { contentDescription = "Browser address" }, textStyle = TextStyle(fontSize = 13.sp), singleLine = true, shape = RoundedCornerShape(20.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submitAddress() }))
            IconButton(onClick = { engine.reload(tab) }) { Icon(Icons.Rounded.Refresh, "Reload") }
            IconButton(onClick = { store.addBookmark(currentUrl, page.title.ifBlank { tab.title }); onBookmarks() }) { Icon(Icons.Rounded.BookmarkAdd, "Bookmark this page") }
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
        if (page.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lavender)
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
    }
    if (siteSettings) SiteSettingsSheet(engine, currentUrl) { siteSettings = false }
    BackHandler { back() }
    DisposableEffect(tab.id) {
        engine.setVisible(tab.id)
        onDispose { if (engine.visibleTab == tab.id) engine.setVisible(null) }
    }
}

/**
 * Separate process and WebView storage directory keep private browsing apart from normal tabs. It is stricter
 * than normal browsing, and says so when that stops a site feature instead of failing silently: cookies and
 * site storage are blocked, pop-ups open in this same page, and camera, microphone, location and downloads
 * are off. Links follow the same safety policy as normal tabs, and every link to another app is confirmed.
 */
class PrivateActivity : ComponentActivity() {
    private var privateWebView: WebView? = null
    private val notice = mutableStateOf<String?>(null)
    private val pageError = mutableStateOf<String?>(null)
    private val crashed = mutableStateOf(false)
    private val externalTarget = mutableStateOf<ExternalTarget?>(null)
    private val fileChooser = mutableStateOf<FileChooserRequest?>(null)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().setAcceptCookie(false)
        WebStorage.getInstance().deleteAllData()
        setContent { MyloTheme {
            var query by rememberSaveable { mutableStateOf("") }
            var url by rememberSaveable { mutableStateOf<String?>(null) }
            val provider = remember { BrowserStore(this).provider }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                fileChooser.value?.complete(FileChoices.parse(result.resultCode, result.data))
                fileChooser.value = null
            }
            LaunchedEffect(fileChooser.value) {
                val request = fileChooser.value ?: return@LaunchedEffect
                if (request.launched) return@LaunchedEffect
                request.launched = true
                runCatching { picker.launch(FileChoices.intent(request.params)) }.onFailure { request.complete(null); fileChooser.value = null }
            }
            BackHandler { if (privateWebView?.canGoBack() == true) privateWebView?.goBack() else finish() }
            val keyboard = LocalSoftwareKeyboardController.current
            fun go(target: String) {
                pageError.value = null
                if (privateWebView == null || crashed.value) { crashed.value = false; url = target; privateWebView?.loadUrl(target) } else privateWebView?.loadUrl(target)
            }
            Surface(color = Night, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.Rounded.Close, "Close private session") }
                        Text("Private browsing", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Search or enter address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { resolveInput(query, provider)?.let { go(it); keyboard?.hide() } }))
                    pageError.value?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = Color(0xFFFFCCCF)) }
                    notice.value?.let { message ->
                        PageNoticeBar(PageNotice.Info(message), onDismiss = { notice.value = null }, onAllowPopups = {}, onOtherBrowser = {})
                    }
                    if (url == null) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Rounded.Masks, null, tint = Lavender, modifier = Modifier.size(48.dp))
                            Text("A little space of your own", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text("Mylo won't save this session to history. Cookies are blocked and website storage is separate from your regular tabs. Some sites may not work with cookies blocked.", color = Muted)
                            Text("Private browsing is stricter: sign-ins that need cookies won't work, pop-ups open in this same page, and camera, microphone, location and downloads are off.", color = Muted, fontSize = 13.sp)
                            Text("Private browsing doesn't hide your activity from websites, your network, or your internet provider.", color = Muted, fontSize = 13.sp)
                        }
                    } else if (crashed.value) {
                        Box(Modifier.weight(1f)) { CrashedPage { go(url!!) } }
                    } else AndroidView(factory = { ctx -> WebView(ctx).apply {
                        privateWebView = this
                        clearCache(true); clearHistory()
                        settings.javaScriptEnabled = true; settings.domStorageEnabled = false; settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.allowFileAccess = false; settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.safeBrowsingEnabled = true
                        settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
                        settings.useWideViewPort = true; settings.loadWithOverviewMode = true
                        // Without multiple-window support, target=_blank and window.open load in this page.
                        settings.setSupportMultipleWindows(false)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = PrivateClient()
                        webChromeClient = PrivateChrome()
                        setDownloadListener { _, _, _, _, _ -> notice.value = "Downloads are off in private browsing, so nothing is left on this device." }
                        loadUrl(url!!)
                    } }, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
            externalTarget.value?.let { target ->
                AlertDialog(onDismissRequest = { externalTarget.value = null }, title = { Text("Leave private browsing?") },
                    text = { Text("This link opens ${target.kind.label} outside Mylo. The other app can see what you open.") },
                    confirmButton = { TextButton(onClick = {
                        externalTarget.value = null
                        when (val outcome = ExternalApps.launch(this@PrivateActivity, target)) {
                            is ExternalApps.Outcome.LoadInMylo -> go(outcome.url)
                            ExternalApps.Outcome.NoApp -> notice.value = "No app on this device can open this ${target.kind.label} link."
                            ExternalApps.Outcome.Launched -> Unit
                        }
                    }) { Text("Open") } },
                    dismissButton = { TextButton(onClick = { externalTarget.value = null }) { Text("Cancel") } })
            }
        } }
    }

    private inner class PrivateClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            when (val decision = WebPolicy.decide(request.url.toString(), request.isForMainFrame, request.hasGesture(), request.isRedirect)) {
                NavigationDecision.LoadInMylo -> false
                is NavigationDecision.OpenExternal -> { externalTarget.value = decision.target; true }
                is NavigationDecision.Blocked -> true
            }
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { pageError.value = null }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) pageError.value = "This page couldn't load. Check your connection and try again."
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            pageError.value = "Mylo stopped loading this page because its security certificate couldn't be verified."
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            if (privateWebView === view) privateWebView = null
            crashed.value = true
            return true
        }
    }

    private inner class PrivateChrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
            notice.value = "Camera and microphone are off in private browsing. Use a regular tab for this site."
        }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
            notice.value = "Location is off in private browsing. Use a regular tab for this site."
        }
        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<android.net.Uri>>, fileChooserParams: FileChooserParams): Boolean {
            fileChooser.value?.complete(null)
            fileChooser.value = FileChooserRequest(0, fileChooserParams, filePathCallback)
            return true
        }
        override fun onConsoleMessage(consoleMessage: ConsoleMessage) = true
    }

    override fun onDestroy() {
        fileChooser.value?.complete(null)
        privateWebView?.apply { stopLoading(); clearCache(true); clearHistory(); destroy() }
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }
}

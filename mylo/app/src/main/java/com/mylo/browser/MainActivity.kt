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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    val tabStates = mutableMapOf<Long, Bundle>()
    val pendingNavigations = mutableMapOf<Long, String>()
}

class MainActivity : ComponentActivity() {
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
        setContent { MyloTheme { MyloApp(session) } }
    }
}

@Composable fun MyloApp(session: BrowserSession) {
    val store = session.store
    val context = LocalContext.current
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var currentTab by rememberSaveable { mutableStateOf<Long?>(null) }
    var home by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchProvider by rememberSaveable { mutableStateOf(store.provider) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val vpn = rememberVpnStatus()
    fun startSearch() { query = ""; searchProvider = store.provider; searching = true }
    fun closeSearch() { searching = false; query = ""; focus.clearFocus(); keyboard?.hide() }
    fun showHome() { closeSearch(); home = true }
    fun open(input: String, usingProvider: SearchProvider = store.provider) {
        val tab = store.navigateInCurrentTab(currentTab, input, searchProvider = usingProvider)
        if (tab == null) { error = "Enter a website address or search words."; return }
        // A Home submission is a navigation in the existing tab, never an implicit new tab.
        session.pendingNavigations[tab.id] = tab.url
        currentTab = tab.id
        home = false
        searching = false
        query = ""
        focus.clearFocus()
        keyboard?.hide()
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            open(it, if (searching) searchProvider else store.provider)
        }
    }
    val scan = rememberMyloScanner(
        onResult = { open(it, if (searching) searchProvider else store.provider) },
        onError = { error = it },
    )
    MyloViewport(edgeToEdgeHome = home || store.tabs.none { it.id == currentTab }) {
            Box(Modifier.weight(1f)) {
                Box(Modifier.fillMaxSize().then(if (searching) Modifier.clearAndSetSemantics { } else Modifier)) {
                val tab = store.tabs.firstOrNull { it.id == currentTab }
                if (home || tab == null) {
                    HomeScreen(query, { query = it }, { open(query) }, {
                        runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "Search with Mylo")) }
                            .onFailure { error = "Voice search isn't available on this device. You can type your search." }
                    }, { panel = it }, { open(it) }, {
                        context.startActivity(Intent(context, PrivateActivity::class.java))
                    }, vpn, onActivateSearch = { if (!searching) startSearch() }, onScanner = scan)
                } else {
                    key(tab.id) {
                        BrowserScreen(tab, session, ::showHome, { panel = "bookmarks" }, !searching)
                    }
                }
                }
                AnimatedVisibility(visible = searching, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                        SearchInputScreen(query, { query = it }, searchProvider, { searchProvider = it },
                            { open(query, searchProvider) }, ::closeSearch,
                            defaultProvider = store.provider,
                            onSetDefault = { store.setProvider(it); searchProvider = it })
                    }
                }
            }
            if (!searching) BottomBar(home || store.tabs.none { it.id == currentTab }, store.tabs.size,
                ::showHome, ::startSearch, { panel = "tabs" }, { panel = "mylo" })
    }
    panel?.let { selected -> MyloPanel(selected, store, { panel = null }, { open(it) }, {
        closeSearch(); currentTab = it.id; home = it.url.isBlank()
    }, { currentTab = store.createTab().id; home = true; startSearch() }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Mylo") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
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

@SuppressLint("SetJavaScriptEnabled")
@Composable private fun BrowserScreen(tab: BrowserTab, session: BrowserSession, onHome: () -> Unit, onBookmarks: () -> Unit, handleBack: Boolean = true) {
    val store = session.store
    var address by remember(tab.id) { mutableStateOf(tab.url) }
    var editingAddress by remember(tab.id) { mutableStateOf(false) }
    var webView by remember(tab.id) { mutableStateOf<WebView?>(null) }
    var loading by remember(tab.id) { mutableStateOf(false) }
    var pageError by remember(tab.id) { mutableStateOf<String?>(null) }
    var canForward by remember(tab.id) { mutableStateOf(false) }
    var active by remember(tab.id) { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val latestOnHome by rememberUpdatedState(onHome)

    fun back() {
        focus.clearFocus(); keyboard?.hide()
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else latestOnHome()
    }
    fun submitAddress() {
        val url = resolveInput(address, store.provider)
        if (url == null) { pageError = "Enter a website address or search words."; return }
        webView?.stopLoading()
        address = url
        store.updateTab(tab.id, url, url)
        webView?.loadUrl(url)
        focus.clearFocus(); keyboard?.hide()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            IconButton(onClick = { webView?.goForward() }, enabled = canForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward") }
            OutlinedTextField(address, { address = it }, Modifier.weight(1f).padding(vertical = 5.dp).onFocusChanged { editingAddress = it.isFocused }.semantics { contentDescription = "Browser address" }, textStyle = TextStyle(fontSize = 13.sp), singleLine = true, shape = RoundedCornerShape(20.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submitAddress() }))
            IconButton(onClick = { webView?.reload() }) { Icon(Icons.Rounded.Refresh, "Reload") }
            IconButton(onClick = { webView?.let { store.addBookmark(it.url.orEmpty(), it.title.orEmpty()) }; onBookmarks() }) { Icon(Icons.Rounded.BookmarkAdd, "Bookmark this page") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lavender)
        pageError?.let { Text(it, modifier = Modifier.padding(16.dp), color = Color(0xFFFFCCCF)) }
        key(tab.id) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    contentDescription = "Mylo web page"
                    setBackgroundColor(android.graphics.Color.WHITE)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme !in listOf("http", "https")
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            if (!active) return
                            loading = true; pageError = null
                        }
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            if (active) canForward = view.canGoForward()
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            if (!active || url != view.url) return
                            loading = false; canForward = view.canGoForward()
                            if (!editingAddress) address = url
                            store.updateTab(tab.id, url, view.title.orEmpty())
                            if (pageError == null) store.recordVisit(url, view.title.orEmpty())
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (active && request.isForMainFrame) { loading = false; pageError = "This page couldn't load. Check your connection and try Reload." }
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                            if (active && request.isForMainFrame && errorResponse.statusCode >= 400) {
                                pageError = "The website returned an error (${errorResponse.statusCode}). Try Reload or another search engine."
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView, title: String?) {
                            if (active && !view.url.isNullOrBlank()) store.updateTab(tab.id, view.url!!, title.orEmpty())
                        }
                    }
                    val requested = session.pendingNavigations.remove(tab.id)
                    val restored = session.tabStates[tab.id]?.let { restoreState(it) }
                    if (requested != null) {
                        loadUrl(requested)
                    } else if (restored == null) loadUrl(tab.url)
                    canForward = canGoForward()
                }
            }, update = { view ->
                // Search mode overlays an existing WebView. Consume a submission
                // once, without recreating the view or losing its navigation list.
                session.pendingNavigations.remove(tab.id)?.let { url ->
                    view.stopLoading()
                    address = url
                    view.loadUrl(url)
                }
            }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
    BackHandler(enabled = handleBack) { back() }
    DisposableEffect(tab.id) { onDispose {
        active = false
        webView?.apply {
            if (store.tabs.any { it.id == tab.id }) session.tabStates[tab.id] = Bundle().also { saveState(it) }
            else { session.tabStates.remove(tab.id); session.pendingNavigations.remove(tab.id) }
            stopLoading(); destroy()
        }
        webView = null
    } }
}

/** Separate process and WebView storage directory keep private cookies apart from normal tabs. */
class PrivateActivity : ComponentActivity() {
    private var privateWebView: WebView? = null
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
            BackHandler { if (privateWebView?.canGoBack() == true) privateWebView?.goBack() else finish() }
            val keyboard = LocalSoftwareKeyboardController.current
            Surface(color = Night, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.Rounded.Close, "Close private session") }
                        Text("Private browsing", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Search or enter address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { resolveInput(query, provider)?.let { if (privateWebView == null) url = it else privateWebView?.loadUrl(it); keyboard?.hide() } }))
                    if (url == null) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Rounded.Masks, null, tint = Lavender, modifier = Modifier.size(48.dp))
                            Text("A little space of your own", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text("Mylo won't save this session to history. Cookies are blocked and website storage is separate from your regular tabs. Some sites may not work with cookies blocked.", color = Muted)
                            Text("Private browsing doesn't hide your activity from websites, your network, or your internet provider.", color = Muted, fontSize = 13.sp)
                        }
                    } else AndroidView(factory = { ctx -> WebView(ctx).apply {
                        privateWebView = this
                        clearCache(true); clearHistory()
                        settings.javaScriptEnabled = true; settings.domStorageEnabled = false; settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.allowFileAccess = false; settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.scheme !in listOf("http", "https") }
                        loadUrl(url!!)
                    } }, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        } }
    }
    override fun onDestroy() {
        privateWebView?.apply { stopLoading(); clearCache(true); clearHistory(); destroy() }
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }
}

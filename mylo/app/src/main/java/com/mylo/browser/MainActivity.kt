package com.mylo.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import com.mylo.browser.ai.AiContext
import com.mylo.browser.ai.AiConversation
import com.mylo.browser.ai.AiDataSource
import com.mylo.browser.ai.Gathered
import com.mylo.browser.ai.MyloAi
import com.mylo.browser.ai.PageContext
import com.mylo.browser.ai.PageReader
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
    /** Private Mode's session; exists only in the separate private process. */
    val privateSession: com.mylo.browser.privacy.PrivateSession by lazy { com.mylo.browser.privacy.PrivateSession(this) }

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().endsWith(":private")) {
            WebView.setDataDirectorySuffix(com.mylo.browser.privacy.PrivateDataJanitor.SUFFIX)
            // A new private process is a new session: nothing from an earlier one (even one Android ended
            // without warning) may carry over. No WebView exists yet, so its files can be removed.
            com.mylo.browser.privacy.PrivateDataJanitor.wipe(this)
        } else {
            com.mylo.browser.privacy.PrivateDataJanitor.wipeIfPrivateProcessGone(this)
        }
    }
}

class BrowserSession(application: Application) : AndroidViewModel(application) {
    val store = BrowserStore(application)
    /** Every normal tab's page, whichever search provider or link opened it. */
    val engine = TabEngine(application, store)
    /** Mylo AI's switchboard for normal browsing ("What Mylo can see"). */
    val switchboard = MyloAi.switchboard(application)
    /** The tab on screen, which is the page Mylo AI may read. */
    var aiTab: Long? = null
    /** One conversation for typing and talking; kept in memory only. */
    val conversation = AiConversation(MyloAi.service(application), switchboard,
        viewModelScope, private = false, gather = ::gather)

    /** Reads exactly the sources a question was allowed, from the live tabs and normal history. */
    private suspend fun gather(allowed: Set<AiDataSource>): Gathered {
        val current = store.tabs.firstOrNull { it.id == aiTab && it.url.isNotBlank() }
        val page = if (current != null && (AiDataSource.CurrentPage in allowed || AiDataSource.SelectedText in allowed)) {
            engine.webViewIfLive(current.id)?.let { PageReader.read(it) }?.let { read ->
                PageContext(
                    url = if (AiDataSource.CurrentPage in allowed) read.url else "",
                    title = if (AiDataSource.CurrentPage in allowed) read.title.ifBlank { current.title } else "",
                    text = if (AiDataSource.CurrentPage in allowed) read.text else "",
                    selection = read.selection.takeIf { AiDataSource.SelectedText in allowed && it.isNotEmpty() },
                ).takeIf { it.url.isNotEmpty() || it.text.isNotEmpty() || it.selection != null }
            }
        } else null
        val tabs = if (AiDataSource.OtherTabs in allowed) store.tabs.filter { it.id != current?.id && it.url.isNotBlank() }.take(6).map { tab ->
            val read = engine.webViewIfLive(tab.id)?.let { PageReader.read(it) }
            PageContext(tab.url, read?.title?.ifBlank { null } ?: tab.title, read?.text?.take(4_000).orEmpty())
        } else emptyList()
        val history = if (AiDataSource.History in allowed) store.history.take(30).map { it.url to it.title } else emptyList()
        // Screenshot, location and saved memory aren't read by this version, whatever the switchboard says.
        val unavailable = allowed.intersect(setOf(AiDataSource.Screenshot, AiDataSource.Location, AiDataSource.MyloMemory))
        return Gathered(AiContext(page = page, tabs = tabs, history = history), unavailable)
    }

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
    // Voice Mode (the Mylo button) covers the browser; the page underneath stays as it was.
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
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
    // Mylo AI may read only the page on screen (and only what its switchboard allows).
    session.aiTab = if (onHome) null else currentTab
    val homeVpn = homeVpnStatus(shieldState, vpn)
    // Mylo Shield replaces the browser while open; tabs keep their saved navigation state meanwhile.
    if (shieldOpen) {
        ShieldRoute(onClose = { shieldOpen = false })
        return
    }
    // Home draws its artwork behind the status bar; browser pages do not.
    Box(Modifier.fillMaxSize()) {
    if (voiceOpen) {
        VoiceRoute(session.conversation, session.switchboard, hasPage = !onHome, tabs = store.tabs.size, onClose = { voiceOpen = false },
            onNav = { destination ->
                voiceOpen = false
                when (destination) {
                    VoiceNav.Home -> showHome()
                    VoiceNav.Search -> searchFromHome()
                    VoiceNav.Tabs -> panel = "tabs"
                    VoiceNav.Private -> context.startActivity(Intent(context, PrivateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    VoiceNav.Mylo -> voiceOpen = true
                }
            }, onMoreSettings = { panel = "mylo" })
    } else {
    MyloViewport(edgeToEdgeHome = onHome) {
            Box(Modifier.weight(1f)) {
                val tab = store.tabs.firstOrNull { it.id == currentTab }
                if (home || tab == null) {
                    HomeScreen(query, { query = it }, { open(query) }, voiceSearch, { if (it == "vpn") shieldOpen = true else panel = it }, { open(it) }, {
                        // Reopens a private session that is still running (Burn session on exit off) instead of starting another.
                        context.startActivity(Intent(context, PrivateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }, homeVpn.active, focusSearch = focusHomeSearch, onSearchFocused = { focusHomeSearch = false },
                        vpnLocation = homeVpn.location, vpnDetail = homeVpn.detail, onScan = scanCode)
                } else {
                    key(tab.id) {
                        BrowserScreen(tab, engine, store, store.provider, ::showHome, { url, title -> store.addBookmark(url, title); panel = "bookmarks" }, { error = it })
                    }
                }
            }
            BottomBar(onHome, store.tabs.size, ::showHome, ::searchFromHome, { panel = "tabs" }, { dismissInput(); voiceOpen = true })
    }
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

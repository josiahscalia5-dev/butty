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
@Composable fun MyloViewport(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Night, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val vpn = rememberVpnStatus()
    fun startSearch() { query = ""; searching = true }
    fun closeSearch() { searching = false; query = ""; focus.clearFocus(); keyboard?.hide() }
    fun showHome() { closeSearch(); home = true }
    fun open(input: String) {
        val tab = store.navigateInCurrentTab(currentTab, input)
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
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { open(it) }
    }
    MyloViewport {
            Box(Modifier.weight(1f)) {
                Box(Modifier.fillMaxSize().then(if (searching) Modifier.clearAndSetSemantics { } else Modifier)) {
                val tab = store.tabs.firstOrNull { it.id == currentTab }
                if (home || tab == null) {
                    HomeScreen(query, { query = it }, { open(query) }, {
                        runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "Search with Mylo")) }
                            .onFailure { error = "Voice search isn't available on this device. You can type your search." }
                    }, { panel = it }, { open(it) }, {
                        context.startActivity(Intent(context, PrivateActivity::class.java))
                    }, vpn, onActivateSearch = { if (!searching) startSearch() })
                } else {
                    key(tab.id) {
                        BrowserScreen(tab, session, ::showHome, { panel = "bookmarks" }, !searching)
                    }
                }
                }
                if (searching) {
                    SearchInputScreen(query, { query = it }, store.provider, store::setProvider,
                        { open(query) }, ::closeSearch)
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

/** Native responsive layout. Only the middle content scrolls; search and navigation stay usable. */
@Composable fun HomeScreen(
    query: String = "", onQuery: (String) -> Unit = {}, onSearch: () -> Unit = {}, onVoice: () -> Unit = {},
    onPanel: (String) -> Unit = {}, onOpen: (String) -> Unit = {}, onPrivate: () -> Unit = {},
    vpnActive: Boolean = false, searchRequest: Int = 0, onActivateSearch: (() -> Unit)? = null
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchRequest) { if (searchRequest > 0) { requester.requestFocus(); keyboard?.show() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Match the artwork's 2:1 aspect ratio so the moon and mascot stay intact.
        // The middle content scrolls on short portraits instead of hiding the header.
        val heroHeight = maxWidth / 2f
        val brandWidth = maxWidth * .47f
        // The wordmark is decorative branding; supporting copy still follows font scale.
        val brandSize = with(LocalDensity.current) { (maxWidth * .145f).coerceAtMost(60.dp).toSp() }
        Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().testTag("home-header").padding(horizontal = 20.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(Color(0xFF1A2951), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.WbSunny, null, tint = Color(0xFFFFD264), modifier = Modifier.size(20.dp)) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Good evening!", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("A little wonder awaits", fontSize = 10.sp, color = Muted)
                    }
                    IconButton(onClick = { onPanel("settings") }) { Icon(Icons.Rounded.Settings, "Settings", tint = Color(0xFFDBD7F7), modifier = Modifier.size(23.dp)) }
                }
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    Image(painterResource(R.drawable.mylo_night_hero), "Mylo, a cheerful corgi by a moonlit lake", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Night.copy(alpha = .05f), Color.Transparent, Night.copy(alpha = .55f)))))
                    Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp, bottom = 16.dp).width(brandWidth)) {
                        Text("Mylo", fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = brandSize, letterSpacing = (-2).sp, maxLines = 1,
                            style = TextStyle(brush = Brush.horizontalGradient(listOf(Color.White, Color(0xFFB5A4FF)))))
                        Text("A brighter web awaits", fontSize = 12.sp, color = Color(0xFFD2CEED), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            SearchBar(query, onQuery, onSearch, onVoice, requester, Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 20.dp).testTag("home-search"), onActivateSearch)
            Column(Modifier.weight(1f).testTag("home-middle").verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    Shortcut("Explore", Icons.Rounded.Explore, Color(0xFF5DAAFF), Modifier.weight(1f)) { onOpen("https://en.wikipedia.org/wiki/Special:Random") }
                    Shortcut("Videos", Icons.Rounded.PlayArrow, Color(0xFFFF6D98), Modifier.weight(1f)) { onOpen("https://m.youtube.com") }
                    Shortcut("Shop", Icons.Rounded.ShoppingBag, Color(0xFF4CD9B5), Modifier.weight(1f)) { onOpen("https://www.amazon.com") }
                    Shortcut("AI", Icons.Rounded.AutoAwesome, Color(0xFFFFCB67), Modifier.weight(1f)) { onOpen("https://chatgpt.com") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        UtilityCard("Private", "Browse without\na trace", Icons.Rounded.Masks, Color(0xFF9475FF), Modifier.weight(1f), onPrivate)
                        UtilityCard("Bookmarks", "Your favorite\nplaces", Icons.Rounded.Bookmark, Color(0xFF559EF5), Modifier.weight(1f)) { onPanel("bookmarks") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        UtilityCard("History", "Pick up where\nyou left off", Icons.Rounded.History, Color(0xFF50CCB6), Modifier.weight(1f)) { onPanel("history") }
                        UtilityCard("Tools", "A little more\npossibility", Icons.Rounded.GridView, Color(0xFFEAC16D), Modifier.weight(1f)) { onPanel("tools") }
                    }
                }
                VpnStrip(vpnActive) { onPanel("vpn") }
                DiscoveryBanner { onOpen("https://en.wikipedia.org/wiki/Special:Random") }
            }
        }
    }
}

@Composable private fun SearchBar(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit, onVoice: () -> Unit, requester: FocusRequester, modifier: Modifier = Modifier, onActivate: (() -> Unit)? = null) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(30.dp)).background(Brush.horizontalGradient(listOf(Color(0xFFF6F3FF), Color(0xFFE3DFFF)))).padding(start = 7.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (onActivate != null) onActivate() else { requester.requestFocus(); keyboard?.show() } }) { Icon(Icons.Rounded.Search, "Focus search", tint = Color(0xFF302B70), modifier = Modifier.size(27.dp)) }
        BasicTextField(value, onValue, Modifier.weight(1f).focusRequester(requester).onFocusChanged { if (it.isFocused) onActivate?.invoke() }.semantics { contentDescription = "Search or enter address" }.padding(vertical = 16.dp), singleLine = true, readOnly = onActivate != null,
            textStyle = TextStyle(color = Color(0xFF252750), fontSize = 15.sp), cursorBrush = Brush.verticalGradient(listOf(Color(0xFF493B96), Color(0xFF493B96))),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text("Search or enter address", color = Color(0xFF626487), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } })
        Box(Modifier.width(1.dp).height(24.dp).background(Color(0xFFC8C4E6)))
        IconButton(onClick = onVoice) { Icon(Icons.Rounded.Mic, "Voice search", tint = Color(0xFF302B70), modifier = Modifier.size(24.dp)) }
        if (value.isNotBlank()) IconButton(onClick = onSubmit) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Go", tint = Color(0xFF302B70)) }
    }
}

@Composable private fun Shortcut(label: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.widthIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(44.dp).background(Brush.linearGradient(listOf(color.copy(alpha = .18f), Color(0xFF18223E))), CircleShape).border(1.dp, color.copy(alpha = .17f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(30.dp).background(Brush.linearGradient(listOf(color, color.copy(alpha = .6f))), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEEEAF9), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable private fun UtilityCard(title: String, subtitle: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 82.dp).clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(listOf(color.copy(alpha = .13f), Panel))).border(1.dp, Line.copy(alpha = .8f), RoundedCornerShape(17.dp)).clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(35.dp).background(Brush.linearGradient(listOf(color, color.copy(alpha = .65f))), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
        Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 11.sp, lineHeight = 14.sp, color = Muted)
        }
    }
}

@Composable private fun VpnStrip(active: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).clip(RoundedCornerShape(17.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF14303D), Panel))).border(1.dp, Color(0xFF2B435C), RoundedCornerShape(17.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VerifiedUser, null, tint = Color(0xFF65D7B9), modifier = Modifier.size(29.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
            Text(if (active) "VPN active" else "VPN protection", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(if (active) "Connected on this device" else "Manage connection", color = Muted, fontSize = 10.sp)
        }
        Box(Modifier.height(28.dp).width(1.dp).background(Line))
        Row(Modifier.widthIn(min = 58.dp, max = 85.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (active) "System VPN" else "Set up", modifier = Modifier.weight(1f), fontSize = 11.sp, color = Color(0xFFDEDDF4), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(15.dp), tint = Muted)
        }
        // Status is never simulated: tapping opens the real Android VPN controls.
        Switch(checked = active, onCheckedChange = { onClick() }, modifier = Modifier.heightIn(min = 48.dp), colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF32C998), checkedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF35425F), uncheckedThumbColor = Color(0xFFBBC3D8)))
    }
}

@Composable private fun DiscoveryBanner(onClick: () -> Unit) {
    // Render only the landscape illustration from the approved reference; all controls/text are native.
    val reference = ImageBitmap.imageResource(R.drawable.approved_design)
    val landscape = remember(reference) {
        BitmapPainter(reference, srcOffset = IntOffset(242, 968), srcSize = IntSize(328, 152))
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("home-discovery").clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF263366), Color(0xFF182445)))).border(1.dp, Line, RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Image(landscape, null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd, alpha = .92f)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xFF1B2858), Color(0xFF192651).copy(alpha = .25f)))))
        Column(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("A little more wonder", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Explore today", fontSize = 12.sp, color = Color(0xFFD7D1F3))
                Box(Modifier.size(23.dp).background(Lavender, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color(0xFF322B62), modifier = Modifier.size(15.dp)) }
            }
        }
    }
}

@Composable fun BottomBar(home: Boolean, tabs: Int, onHome: () -> Unit, onSearch: () -> Unit, onTabs: () -> Unit, onMylo: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 66.dp).testTag("home-bottom-nav").clip(RoundedCornerShape(21.dp)).background(Brush.verticalGradient(listOf(Color(0xFF1A274A), Color(0xFF14203D)))).border(1.dp, Line.copy(alpha = .7f), RoundedCornerShape(21.dp)), verticalAlignment = Alignment.CenterVertically) {
        NavItem("Home", Icons.Rounded.Home, home, Modifier.weight(1f), onHome)
        NavItem("Search", Icons.Rounded.Search, false, Modifier.weight(1f), onSearch)
        Column(Modifier.weight(1f).heightIn(min = 66.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onTabs).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(24.dp).border(1.8.dp, Muted, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) { Text(if (tabs > 99) "99+" else tabs.toString(), fontSize = 11.sp, color = Muted) }
            Text("Tabs", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
        }
        NavItem("Mylo", Icons.Rounded.Pets, false, Modifier.weight(1f), onMylo)
    }
}
@Composable private fun NavItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 66.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.width(51.dp).height(32.dp).background(if (selected) Lavender else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (selected) Color(0xFF4A398F) else Muted, modifier = Modifier.size(24.dp)) }
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Lavender else Muted, modifier = Modifier.padding(top = 2.dp))
    }
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

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
import android.speech.RecognizerIntent
import android.webkit.*
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.math.roundToInt

val Night = Color(0xFF071530)
private val Lavender = Color(0xFFCEC5FF)
private val Muted = Color(0xFFAEB9DB)
private val Panel = Color(0xFF162345)
private val Line = Color(0xFF303F67)
private val MyloRounded = FontFamily(Font(R.font.nunito_black, FontWeight.Black))

@Composable fun MyloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Lavender, onPrimary = Color(0xFF252058), background = Night,
        surface = Panel, onSurface = Color(0xFFF6F4FF), secondary = Color(0xFF67D9C2)), content = content)
}

/**
 * Shared by the activity and the native portrait preview. Insets belong to the shell, except the
 * top inset: Home draws its artwork behind the transparent status bar, other screens pad for it.
 */
@Composable fun MyloViewport(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Night, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .imePadding(),
            content = content,
        )
    }
}

/** Top safe-area padding for screens that don't draw behind the status bar. */
fun Modifier.topSafeArea() = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

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
        enableEdgeToEdge()
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
                    }, vpn, onActivateSearch = { if (!searching) startSearch() }, onScan = {
                        // Google's code scanner supplies its own camera UI; a scanned link or text opens like typed input.
                        val unavailable = "The code scanner isn't available on this device. You can type the address instead."
                        runCatching {
                            GmsBarcodeScanning.getClient(context).startScan()
                                .addOnSuccessListener { code -> code.rawValue?.takeIf { it.isNotBlank() }?.let { open(it) } }
                                .addOnFailureListener { error = unavailable }
                        }.onFailure { error = unavailable }
                    })
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

/** Design-comparison only: renders the approved mock VPN location. Live status never uses this. */
class VpnDesignPreview(val location: String, val badge: @Composable () -> Unit)

// Approved Home palette, sampled from the approved reference.
private val HomeInk = Color(0xFFF5F4FF)
private val HomeMuted = Color(0xFFAEB7DA)
private val SearchInk = Color(0xFF23286A)
private val CardBorder = Color(0xFF1E2C50)
private val NavInk = Color(0xFFD2D6EE)

/** Width of the approved reference, in dp. Hero artwork scales from it; controls keep their dp sizes. */
private const val REFERENCE_WIDTH = 392.7f

/**
 * Approved Home screen. Dimensions follow the approved 393 × 851 dp reference. The artwork sits
 * behind the transparent status bar; on short screens the page scrolls above the fixed navigation,
 * and on tall screens the spare height is shared between the sections.
 */
@Composable fun HomeScreen(
    query: String = "", onQuery: (String) -> Unit = {}, onSearch: () -> Unit = {}, onVoice: () -> Unit = {},
    onPanel: (String) -> Unit = {}, onOpen: (String) -> Unit = {}, onPrivate: () -> Unit = {},
    vpnActive: Boolean = false, searchRequest: Int = 0, onActivateSearch: (() -> Unit)? = null,
    onScan: () -> Unit = {}, vpnDesignPreview: VpnDesignPreview? = null, statusBarInset: Dp? = null,
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchRequest) { if (searchRequest > 0) { requester.requestFocus(); keyboard?.show() } }
    val statusBar = statusBarInset ?: WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    val scroll = rememberScrollState()
    HomeTextStyle {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            val width = maxWidth
            val keyboardCompact = viewport < 440.dp
            val narrow = width < 380.dp
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Column(Modifier.fillMaxWidth().heightIn(min = viewport).padding(bottom = 5.dp)) {
                    if (keyboardCompact) Spacer(Modifier.height(statusBar + 8.dp))
                    else HomeHero(width, statusBar) { onPanel("settings") }
                    SearchBar(query, onQuery, onSearch, onVoice, onScan, requester, Modifier.padding(horizontal = 12.dp), onActivateSearch)
                    Spacer(Modifier.height(17.dp)); Spacer(Modifier.weight(1f))
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
                    Spacer(Modifier.height(14.dp)); Spacer(Modifier.weight(1f))
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
                    Spacer(Modifier.height(11.dp)); Spacer(Modifier.weight(1f))
                    VpnStrip(vpnActive, vpnDesignPreview, Modifier.padding(horizontal = 13.5.dp)) { onPanel("vpn") }
                    Spacer(Modifier.height(9.dp)); Spacer(Modifier.weight(1f))
                    DiscoveryBanner(Modifier.padding(horizontal = 13.5.dp)) { onOpen("https://en.wikipedia.org/wiki/Special:Random") }
                }
            }
            // Once the page scrolls, keep the status bar legible over the content beneath it.
            val scrim by remember { derivedStateOf { (scroll.value / 120f).coerceIn(0f, 1f) } }
            Box(Modifier.fillMaxWidth().height(statusBar).graphicsLayer { alpha = scrim * .94f }.background(Night))
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

/** Greeting, wordmark and Mylo on the moon, composed exactly as in the approved reference. */
@Composable private fun HomeHero(width: Dp, statusBar: Dp, onSettings: () -> Unit) {
    val art = ImageBitmap.imageResource(R.drawable.mylo_night_hero)
    val k = width.value / REFERENCE_WIDTH
    val heroBottom = statusBar + 211.5.dp * k
    Box(Modifier.fillMaxWidth().height(heroBottom).drawBehind {
        // Registered against the reference: the art is 1.3825 screen widths wide, shifted left by 0.292 widths.
        val w = size.width
        val artWidth = w * 1.3825f
        val artHeight = artWidth * art.height / art.width
        val top = (statusBar.toPx() - w * .0959f).coerceAtMost(0f)
        drawImage(art, dstOffset = IntOffset((-w * .292f).roundToInt(), top.roundToInt()),
            dstSize = IntSize(artWidth.roundToInt(), artHeight.roundToInt()), filterQuality = FilterQuality.High)
        // Let the bottom edge of the art melt into the page behind the search bar.
        val fadeEnd = top + artHeight
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Night), startY = fadeEnd - 34.dp.toPx(), endY = fadeEnd),
            topLeft = Offset(0f, fadeEnd - 34.dp.toPx()), size = Size(w, 34.dp.toPx()))
    }) {
        Box(Modifier.matchParentSize().clearAndSetSemantics { contentDescription = "Mylo, a cheerful corgi sitting on the moon above a moonlit lake" })
        // Glowing star beside Mylo.
        Box(Modifier.offset(x = width * .5959f - 24.dp * k, y = statusBar + 52.dp * k - 24.dp * k).size(48.dp * k)
            .background(Brush.radialGradient(0f to Color(0x66FFD978), .5f to Color(0x24FFD978), 1f to Color(0x00FFD978))), contentAlignment = Alignment.Center) {
            Image(rememberVectorPainter(HomeArt.Star), null, Modifier.size(28.dp * k).graphicsLayer { rotationZ = -6f })
        }
        Row(Modifier.padding(top = statusBar + 2.dp, start = 16.dp, end = 8.5.dp).fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(33.dp).background(Color(0xD91E2D5D), CircleShape).border(1.dp, Color(0x12FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.WbSunny, null, tint = Color(0xFFFFD45E), modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f).padding(start = 7.dp)) {
                Text("Good evening!", fontSize = 10.2.sp, fontWeight = FontWeight.Medium, color = HomeInk, maxLines = 1)
                Text("Have a brighter browse 🌟", fontSize = 8.8.sp, color = Color(0xFFCBCDEB), maxLines = 1, modifier = Modifier.padding(top = 1.dp))
            }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Open settings", onClick = onSettings), contentAlignment = Alignment.Center) {
                Box(Modifier.size(33.dp).background(Color(0xD92A3469), CircleShape).border(1.dp, Color(0x12FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Settings, "Settings", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Image(rememberVectorPainter(HomeArt.Wordmark), "Mylo",
            Modifier.offset(x = width * .0925f, y = statusBar + 70.4.dp * k).width(width * .391f).aspectRatio(HomeArt.WORDMARK_ASPECT))
        Text("A brighter web awaits", fontSize = 15.sp * k, fontWeight = FontWeight.Medium, color = Color(0xFFDCDDF6), maxLines = 1,
            style = TextStyle(shadow = Shadow(Color(0x80040A24), Offset(0f, 2f), 8f)),
            modifier = Modifier.offset(x = width * .107f, y = statusBar + 136.5.dp * k))
    }
}

@Composable private fun SearchBar(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit, onVoice: () -> Unit, onScan: () -> Unit, requester: FocusRequester, modifier: Modifier = Modifier, onActivate: (() -> Unit)? = null) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(modifier.fillMaxWidth().height(54.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFEBECFD), Color(0xFFE4E5FB)))).padding(start = 5.5.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (onActivate != null) onActivate() else { requester.requestFocus(); keyboard?.show() } }) { Icon(Icons.Rounded.Search, "Focus search", tint = SearchInk, modifier = Modifier.size(31.dp)) }
        BasicTextField(value, onValue, Modifier.weight(1f).padding(start = 8.dp).focusRequester(requester).onFocusChanged { if (it.isFocused) onActivate?.invoke() }.semantics { contentDescription = "Search or enter address" }, singleLine = true, readOnly = onActivate != null,
            textStyle = TextStyle(color = Color(0xFF252750), fontSize = 14.75.sp), cursorBrush = SolidColor(Color(0xFF493B96)),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (value.isEmpty()) Text("Search or enter address", color = Color(0xFF4D5180), fontSize = 14.75.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } })
        if (value.isNotBlank()) IconButton(onClick = onSubmit) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Go", tint = SearchInk) }
        Box(Modifier.width(1.dp).height(23.dp).background(Color(0xFFBFC1E0)))
        Spacer(Modifier.width(5.dp))
        IconButton(onClick = onVoice, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Mic, "Voice search", tint = SearchInk, modifier = Modifier.size(26.dp)) }
        IconButton(onClick = onScan, modifier = Modifier.size(44.dp)) { Icon(HomeArt.Scanner, "Scan a code", tint = SearchInk, modifier = Modifier.size(26.dp)) }
    }
}

@Composable private fun Shortcut(label: String, ring: Color, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Column(Modifier.width(80.dp).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(67.dp).background(Brush.radialGradient(listOf(ring, ring.copy(alpha = .92f).compositeOver(Night))), CircleShape)
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
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxHeight().heightIn(min = 74.dp).clip(shape).background(Brush.linearGradient(listOf(Color(0xFF1A2850), Color(0xFF101F41), Color(0xFF0F1E40))))
        .border(1.dp, CardBorder, shape).clickable(role = Role.Button, onClick = onClick).padding(start = 12.5.dp, end = 8.5.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(43.dp).background(Brush.linearGradient(listOf(light, deep)), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { glyph() }
        Column(Modifier.weight(1f).padding(start = textGap, top = 9.dp)) {
            Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = HomeInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 10.25.sp, lineHeight = 13.sp, color = HomeMuted, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color(0xFFB9BFDE), modifier = Modifier.requiredSize(20.dp))
        }
    }
}

@Composable private fun VpnStrip(active: Boolean, preview: VpnDesignPreview?, modifier: Modifier, onClick: () -> Unit) {
    // Live status is never simulated: "protected" appears only for a real VPN (or the design preview).
    val on = preview != null || active
    val shape = RoundedCornerShape(12.dp)
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
            Text(if (on) "Your connection is secure" else "Not connected", fontSize = 9.75.sp, color = HomeMuted, maxLines = 2, modifier = Modifier.padding(top = 1.dp))
        }
        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFF33456A)))
        Row(Modifier.padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                if (preview != null) preview.badge()
                else Box(Modifier.fillMaxSize().background(Color(0xFF22345A)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Public, null, tint = Color(0xFF9FC6F5), modifier = Modifier.size(15.dp)) }
            }
            Text(preview?.location ?: if (active) "System VPN" else "Set up", fontSize = 10.15.sp, fontWeight = FontWeight.Medium, color = HomeInk, maxLines = 1,
                modifier = Modifier.padding(start = 9.5.dp))
            Icon(Icons.Rounded.ExpandMore, null, tint = Color(0xFFC9CDE6), modifier = Modifier.padding(start = 3.dp).size(15.dp))
        }
        Spacer(Modifier.width(15.dp))
        MyloToggle(on, onClick)
    }
}

/** Compact switch in the approved proportions; it opens the real Android VPN controls. */
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
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.fillMaxWidth().height(101.dp).clip(shape).background(Color(0xFF14245C)).clickable(role = Role.Button, onClick = onClick)) {
        Image(painterResource(R.drawable.mylo_discovery_night), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Box(Modifier.matchParentSize().border(1.dp, Color(0x33A9B7FF), shape))
        Column(Modifier.padding(start = 22.dp, top = 13.dp)) {
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
        Row(Modifier.padding(start = 13.5.dp, end = 13.5.dp, top = 6.dp, bottom = 6.5.dp).fillMaxWidth().height(62.dp).clip(shape)
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
        Box(Modifier.size(54.5.dp, 31.5.dp).background(if (selected) Color(0xFFCFC9FB) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            icon(if (selected) Color(0xFF2E2B7E) else NavInk)
        }
        Text(label, fontSize = 10.75.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color(0xFFC4BDFF) else Color(0xFFCDD0E6), modifier = Modifier.padding(top = 2.3.dp))
    }
}

/** Paints the content in [brush], keeping its shape (used for the gradient VPN shield). */
private fun Modifier.brushTint(brush: Brush) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.SrcIn) }

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
    Column(Modifier.fillMaxSize().topSafeArea()) {
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

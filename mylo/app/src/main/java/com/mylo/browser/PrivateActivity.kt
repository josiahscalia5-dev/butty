package com.mylo.browser

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.mylo.browser.privacy.LockAvailability
import com.mylo.browser.privacy.PrivateLock
import com.mylo.browser.privacy.PrivateSession

/**
 * Private Mode, in Mylo's separate private process (its own WebView data directory, so cookies, storage and
 * cache never mix with normal tabs). Shows the approved Private Mode screen, then private tabs on the shared
 * browser engine. Leaving burns the session when Burn session on exit is on; Lock tabs hides private tabs
 * behind the device's screen lock whenever Private Mode leaves the screen. Screenshots and the Recents
 * preview are blocked (FLAG_SECURE). Private Mode does not hide activity from websites or networks.
 */
class PrivateActivity : FragmentActivity() {
    private lateinit var session: PrivateSession
    private val locked = mutableStateOf(false)
    /** Bumped to ask the screen to show Android's unlock prompt. */
    private val unlockRequests = mutableIntStateOf(0)
    private var authenticating = false
    /** The session was burned on the way out; the private process ends after the activity is gone. */
    private var burnedOnExit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screenshots and the Recents preview are blocked. Debug builds let device tests capture evidence
        // when the test itself opts in (release builds never do).
        val testScreenshots = BuildConfig.DEBUG && getSharedPreferences(TEST_PREFS, MODE_PRIVATE).getBoolean(ALLOW_SCREENSHOTS, false)
        if (!testScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        session = (application as MyloApplication).privateSession
        session.engine.attach(this)
        // After Android recreated this screen in a new process the old session is gone: nothing to unlock.
        locked.value = (savedInstanceState?.getBoolean(KEY_LOCKED) ?: false) && session.tabs.tabs.isNotEmpty()
        setContent {
            MyloTheme {
                PrivateApp(
                    session = session,
                    locked = locked.value,
                    unlockRequests = unlockRequests.intValue,
                    onUnlock = ::unlock,
                    onLeave = ::leave,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (locked.value) unlockRequests.intValue++
    }

    override fun onResume() {
        super.onResume()
        session.engine.onActivityResume()
    }

    override fun onPause() {
        session.engine.onActivityPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Lock whenever Private Mode leaves the screen with private tabs open (not for Android's own unlock UI).
        if (!isChangingConfigurations && !authenticating && !burnedOnExit && session.settings.lockTabs &&
            session.tabs.tabs.isNotEmpty() && PrivateLock.availability(this) == LockAvailability.Ready) {
            locked.value = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LOCKED, locked.value)
    }

    override fun onDestroy() {
        session.engine.detach(this)
        super.onDestroy()
        if (burnedOnExit && isFinishing) {
            // Nothing of the burned session may stay in memory: end the private process (normal tabs live in
            // the main process and are unaffected). The next private session starts in a fresh process.
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 350)
        }
    }

    private fun unlock() {
        if (authenticating) return
        authenticating = true
        PrivateLock.authenticate(this, "Unlock private tabs", "Use your screen lock to see your private tabs") { ok, _ ->
            authenticating = false
            if (ok) locked.value = false
        }
    }

    /** Leaves Private Mode: burns the session (Burn session on exit), or keeps it running behind normal browsing. */
    private fun leave() {
        if (session.settings.burnOnExit) {
            session.burn()
            burnedOnExit = true
            locked.value = false
            finish()
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    /** Lock tabs is turned on only after the user proves they can unlock (Android's prompt succeeds). */
    fun confirmLockOn(onResult: (Boolean) -> Unit) {
        authenticating = true
        PrivateLock.authenticate(this, "Turn on Lock tabs", "Confirm with your screen lock") { ok, _ ->
            authenticating = false
            onResult(ok)
        }
    }

    companion object {
        private const val KEY_LOCKED = "locked"
        /** Debug-build test switch (see onCreate). */
        const val TEST_PREFS = "mylo_test"
        const val ALLOW_SCREENSHOTS = "allow_private_screenshots"
    }
}

private enum class PrivateScreen { Mode, Browse }

/** The private session's screens: Private Mode, private browsing, sheets and dialogs, and the lock. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable private fun PrivateApp(
    session: PrivateSession,
    locked: Boolean,
    unlockRequests: Int,
    onUnlock: () -> Unit,
    onLeave: () -> Unit,
) {
    val engine = session.engine
    val tabs = session.tabs
    val settings = session.settings
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity() as? PrivateActivity
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    var screen by rememberSaveable { mutableStateOf(PrivateScreen.Mode) }
    var currentTab by rememberSaveable { mutableStateOf<Long?>(null) }
    var sheet by remember { mutableStateOf<PrivateSheet?>(null) }
    var dialog by remember { mutableStateOf<PrivateDialog?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var focusSearch by remember { mutableStateOf(false) }
    // Play the entrance once per visit to the Private Mode screen (instant when Android's animations are off).
    var entrances by rememberSaveable { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The provider chosen in Settings; only that preference is read, nothing from normal browsing.
    val provider = remember { savedSearchProvider(context) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val tab = tabs.tabs.firstOrNull { it.id == currentTab }
    // A tab can disappear (closed, burned, or the process was recreated): fall back to Private Mode.
    LaunchedEffect(tab, screen) { if (screen == PrivateScreen.Browse && currentTab != null && tab == null) currentTab = null }
    LaunchedEffect(session.burns) { if (session.burns > 0 && tabs.tabs.isEmpty()) { currentTab = null; screen = PrivateScreen.Mode } }
    LaunchedEffect(unlockRequests) { if (unlockRequests > 0 && locked) onUnlock() }

    fun dismissInput() { focus.clearFocus(); keyboard?.hide() }
    fun showMode() { dismissInput(); screen = PrivateScreen.Mode; entrances++ }
    fun leave() {
        dismissInput()
        if (settings.burnOnExit && tabs.tabs.isNotEmpty()) dialog = PrivateDialog.LeaveAndBurn else onLeave()
    }
    fun newTabPage() { dismissInput(); screen = PrivateScreen.Browse; currentTab = tabs.tabs.firstOrNull { it.url.isBlank() }?.id; focusSearch = true }
    fun open(input: String) {
        val url = resolveInput(input, provider)
        if (url == null) { message = "Enter a website address or search words."; return }
        val target = tabs.tabs.firstOrNull { it.id == currentTab } ?: tabs.create()
        tabs.updateTab(target.id, url, url)
        engine.load(target.id, url)
        currentTab = target.id
        screen = PrivateScreen.Browse
        dismissInput()
    }
    fun burnNow() {
        val report = session.burn()
        currentTab = null
        screen = PrivateScreen.Mode
        message = burnMessage(report)
    }

    Surface(color = PrivateNight, modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        when {
            screen == PrivateScreen.Mode -> {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                    key(entrances) {
                        PrivateModeScreen(
                            ui = PrivateModeUi(settings.blockTrackers, settings.lockTabs, settings.burnOnExit, engine.trackersBlocked, tabs.tabs.size),
                            onFeature = { sheet = PrivateSheet.Feature(it) },
                            onEnter = { dismissInput(); screen = PrivateScreen.Browse; currentTab = currentTab ?: tabs.tabs.lastOrNull()?.id; focusSearch = currentTab == null },
                            onBurnNow = { dialog = PrivateDialog.BurnNow },
                            onSettings = { sheet = PrivateSheet.Settings },
                            onNav = { nav ->
                                when (nav) {
                                    PrivateNav.Home -> leave()
                                    PrivateNav.Search -> newTabPage()
                                    PrivateNav.Tabs -> sheet = PrivateSheet.Tabs
                                    PrivateNav.Mylo -> Unit
                                }
                            },
                        )
                    }
                }
                BackHandler { leave() }
            }
            else -> {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    Box(Modifier.weight(1f)) {
                        if (tab == null || tab.url.isBlank() && !engine.isLive(tab.id)) {
                            PrivateNewTab(session, focusSearch, onFocused = { focusSearch = false }, onSubmit = ::open)
                            BackHandler { showMode() }
                        } else key(tab.id) {
                            BrowserScreen(tab, engine, tabs, provider, onHome = ::showMode, onBookmark = null, onMessage = { message = it }, privateMode = true)
                        }
                    }
                    BoxWithConstraints {
                        val layout = PrivateLayout(maxWidth, screenHeight, 0.dp)
                        Column {
                            Spacer(Modifier.height(4.dp))
                            PrivateBottomBar(layout, tabs.tabs.size, selected = null, onNav = { nav ->
                                when (nav) {
                                    PrivateNav.Home -> leave()
                                    PrivateNav.Search -> newTabPage()
                                    PrivateNav.Tabs -> sheet = PrivateSheet.Tabs
                                    PrivateNav.Mylo -> showMode()
                                }
                            })
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
        engine.fullscreen?.let { FullscreenHost(it, engine::exitFullscreen) }
        if (locked) PrivateLockScreen(onUnlock = onUnlock, onBurnAndLeave = { onLeave() }, burnOnExit = settings.burnOnExit)
    }
    if (!locked) {
        WebPromptHost(engine)
        FileChooserHost(engine)
        sheet?.let { current ->
            PrivateSheetHost(current, session, onClose = { sheet = null },
                onSelectTab = { sheet = null; currentTab = it.id; screen = PrivateScreen.Browse },
                onNewTab = { sheet = null; newTabPage() },
                onBurnNow = { sheet = null; dialog = PrivateDialog.BurnNow },
                onLockChange = { on ->
                    if (!on) settings.updateLockTabs(false)
                    else activity?.confirmLockOn { ok -> if (ok) settings.updateLockTabs(true) else message = "Lock tabs stays off: Android's screen lock wasn't confirmed." }
                },
                onMessage = { message = it })
        }
    }
    dialog?.let { current ->
        PrivateDialogHost(current, tabs.tabs.size, onDismiss = { dialog = null }, onConfirm = {
            dialog = null
            when (current) {
                PrivateDialog.BurnNow -> burnNow()
                PrivateDialog.LeaveAndBurn -> onLeave()
            }
        })
    }
    message?.let { text -> PrivateMessage(text) { message = null } }
}

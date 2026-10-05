package com.mylo.browser

import android.content.ActivityNotFoundException
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mylo.browser.privacy.BurnReport
import com.mylo.browser.privacy.LockAvailability
import com.mylo.browser.privacy.PrivateLock
import com.mylo.browser.privacy.PrivateSession
import com.mylo.browser.web.TrackerList
import kotlinx.coroutines.delay

private val SheetNavy = Color(0xFF0E1A36)
private val SheetCard = Color(0xFF16244A)
private val SheetText = Color(0xFFF4F2FF)
private val SheetMuted = Color(0xFFB2BAD6)
private val SheetAccent = Color(0xFFCDBEFE)

internal sealed interface PrivateSheet {
    data class Feature(val feature: PrivateFeature) : PrivateSheet
    data object Settings : PrivateSheet
    data object Tabs : PrivateSheet
}

internal enum class PrivateDialog { BurnNow, LeaveAndBurn }

/** The honest limit of Private Mode, shown wherever it is explained. */
internal const val PRIVATE_LIMIT = "Private Mode doesn’t hide your activity from the websites you visit, your employer or school, or your internet provider. For network privacy, use a VPN such as Mylo Shield."

internal fun burnMessage(report: BurnReport): String = buildString {
    append("Private session cleared: ")
    append(if (report.tabs == 1) "1 tab" else "${report.tabs} tabs")
    append(", cookies, site data, permissions and tracker log")
    if (report.extras.isNotEmpty()) append(", ").append(report.extras.joinToString(", "))
    append(".")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PrivateSheetHost(
    sheet: PrivateSheet,
    session: PrivateSession,
    onClose: () -> Unit,
    onSelectTab: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onBurnNow: () -> Unit,
    onLockChange: (Boolean) -> Unit,
    onMessage: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetNavy, contentColor = SheetText, scrimColor = Color(0xB3030817)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp).testTag("private-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (sheet) {
                is PrivateSheet.Feature -> when (sheet.feature) {
                    PrivateFeature.NoHistory -> HistorySheet(session)
                    PrivateFeature.BlockTrackers -> TrackersSheet(session)
                    PrivateFeature.LockTabs -> LockSheet(session, onLockChange)
                    PrivateFeature.BurnOnExit -> BurnSheet(session, onBurnNow)
                }
                PrivateSheet.Settings -> {
                    SheetTitle("Private Mode settings")
                    SettingSwitch("Block trackers", "Third-party ads, analytics and social trackers are blocked on every private page.", session.settings.blockTrackers, "private-setting-trackers") { session.setBlockTrackers(it) }
                    SettingSwitch("Lock tabs", lockSummary(session), session.settings.lockTabs, "private-setting-lock") { onLockChange(it) }
                    SettingSwitch("Burn session on exit", "Leaving Private Mode clears its tabs, cookies, site data and permissions.", session.settings.burnOnExit, "private-setting-burn") { session.settings.updateBurnOnExit(it) }
                    SheetNote(PRIVATE_LIMIT)
                }
                PrivateSheet.Tabs -> TabsSheet(session, onSelectTab, onNewTab, onBurnNow)
            }
        }
    }
}

@Composable private fun HistorySheet(session: PrivateSession) {
    SheetTitle("No history saved")
    SheetBody("Pages you open in Private Mode are never written to Mylo history, and private tabs aren’t kept after the session ends. Searches and addresses you type here stay out of normal browsing too.")
    StatCard(listOf(
        "${session.tabs.visits}" to if (session.tabs.visits == 1) "page opened this session" else "pages opened this session",
        "0" to "written to Mylo history",
    ))
    SheetBody("Website data (cookies and storage) lives in Private Mode’s own space, separate from your regular tabs, and is destroyed when the session burns.")
    SheetNote(PRIVATE_LIMIT)
}

@Composable private fun TrackersSheet(session: PrivateSession) {
    val blocked = session.engine.trackersBlocked
    SheetTitle("Block trackers")
    SettingSwitch("Block trackers", "Blocks requests to known ad, analytics, social and cross-site tracking services on every private page.",
        session.settings.blockTrackers, "private-trackers-switch") { session.setBlockTrackers(it) }
    StatCard(listOf("$blocked" to if (blocked == 1) "tracker request blocked this session" else "tracker requests blocked this session"))
    val summary = remember(blocked) { session.blockedSummary() }
    if (summary.isNotEmpty()) {
        Text("Blocked most often", color = SheetMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        summary.take(8).forEach { (domain, count, category) ->
            Row(Modifier.fillMaxWidth().testTag("private-blocked-domain"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(domain, color = SheetText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    category?.let { Text(it.label, color = SheetMuted, fontSize = 12.sp) }
                }
                Text("$count", color = SheetAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    SheetBody("The page you asked for always loads. A company’s own trackers aren’t blocked on its own sites, so they keep working. Mylo’s list covers ${TrackerList.domains.size} widely documented tracking domains.")
    SheetNote("Some “Log in with…” buttons from social networks use tracking scripts. If one doesn’t work, turn Block trackers off for this session.")
}

@Composable private fun LockSheet(session: PrivateSession, onLockChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val availability = remember { PrivateLock.availability(context) }
    SheetTitle("Lock tabs")
    SheetBody("When Private Mode leaves the screen, your private tabs are hidden until you unlock them with your fingerprint, face, PIN, pattern or password. Mylo never sees your screen lock.")
    SettingSwitch("Lock tabs", lockSummary(session), session.settings.lockTabs, "private-lock-switch", enabled = availability == LockAvailability.Ready || session.settings.lockTabs) { onLockChange(it) }
    when (availability) {
        LockAvailability.Ready -> Unit
        LockAvailability.NoScreenLock -> {
            SheetNote("Set up a screen lock (PIN, pattern or password) in Android settings to lock private tabs.")
            TextButton(onClick = {
                try { context.startActivity(PrivateLock.screenLockSettings()) } catch (_: ActivityNotFoundException) { }
            }) { Text("Open screen lock settings", color = SheetAccent) }
        }
        LockAvailability.Unavailable -> SheetNote("This device can’t lock apps with its screen lock.")
    }
    SheetBody("Private Mode also keeps its screen out of screenshots and the Recents preview.")
}

@Composable private fun BurnSheet(session: PrivateSession, onBurnNow: () -> Unit) {
    SheetTitle("Burn session on exit")
    SettingSwitch("Burn session on exit", if (session.settings.burnOnExit) "On: leaving Private Mode clears everything below." else "Off: private tabs stay open while you browse normally, until you burn them.",
        session.settings.burnOnExit, "private-burn-switch") { session.settings.updateBurnOnExit(it) }
    Text("A burn clears", color = SheetMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    listOf("Private tabs and their back/forward history", "Cookies and sign-ins", "Site storage, caches and service workers",
        "Camera, microphone and location permissions given this session", "Mylo’s tracker log for the session",
        "Anything Mylo kept for this session, such as temporary AI conversations and captures").forEach {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 7.dp).size(6.dp).background(SheetAccent, CircleShape))
            Text(it, Modifier.padding(start = 10.dp), color = SheetText, fontSize = 14.sp)
        }
    }
    SheetNote("If Android closes Mylo before a burn, Private Mode’s data is removed the next time Mylo starts.")
    Button(onClick = onBurnNow, modifier = Modifier.fillMaxWidth().testTag("private-burn-sheet-button"), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2464E), contentColor = Color.White)) { Text("Burn now", fontWeight = FontWeight.SemiBold) }
}

@Composable private fun TabsSheet(session: PrivateSession, onSelectTab: (BrowserTab) -> Unit, onNewTab: () -> Unit, onBurnNow: () -> Unit) {
    SheetTitle("Private tabs")
    Button(onClick = onNewTab, modifier = Modifier.fillMaxWidth().testTag("private-new-tab"), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SheetAccent, contentColor = SheetNavy)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("New private tab", fontWeight = FontWeight.SemiBold)
    }
    if (session.tabs.tabs.isEmpty()) SheetBody("No private tabs open.")
    session.tabs.tabs.forEach { tab ->
        Surface(color = SheetCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("private-tab-row")) {
            Row(Modifier.clickable { onSelectTab(tab) }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(PrivateArt.Incognito, null, tint = SheetAccent, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(tab.title.ifBlank { "Private tab" }, color = SheetText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tab.url.ifBlank { "New private tab" }, color = SheetMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { session.engine.closeTab(tab.id) }) { Icon(Icons.Outlined.Close, "Close ${tab.title.ifBlank { "private tab" }}", tint = SheetMuted) }
            }
        }
    }
    if (session.tabs.tabs.isNotEmpty()) TextButton(onClick = onBurnNow) { Text("Close all and burn the session", color = Color(0xFFFF9AA0)) }
}

private fun lockSummary(session: PrivateSession) =
    if (session.settings.lockTabs) "On: unlock with your screen lock after leaving Private Mode." else "Off: private tabs show as soon as you return."

@Composable private fun SheetTitle(text: String) = Text(text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = SheetText, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
@Composable private fun SheetBody(text: String) = Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = SheetMuted)

@Composable private fun SheetNote(text: String) {
    Row(Modifier.fillMaxWidth().background(SheetCard, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, tint = SheetAccent, modifier = Modifier.size(18.dp))
        Text(text, Modifier.padding(start = 10.dp), fontSize = 13.sp, lineHeight = 18.sp, color = SheetMuted)
    }
}

@Composable private fun StatCard(stats: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF2A2D82), Color(0xFF16245A))), RoundedCornerShape(18.dp))
        .border(1.dp, Color(0xFF3D45A0), RoundedCornerShape(18.dp)).padding(16.dp).testTag("private-stats"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        stats.forEach { (value, label) ->
            Column(Modifier.weight(1f)) {
                Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = SheetText)
                Text(label, fontSize = 12.sp, color = SheetMuted)
            }
        }
    }
}

@Composable private fun SettingSwitch(title: String, summary: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(SheetCard, RoundedCornerShape(18.dp)).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = SheetText, fontWeight = FontWeight.Medium)
            Text(summary, color = SheetMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(checkedThumbColor = SheetNavy, checkedTrackColor = SheetAccent, uncheckedTrackColor = Color(0xFF2A3658)))
    }
}

@Composable internal fun PrivateDialogHost(dialog: PrivateDialog, tabs: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val tabsText = if (tabs == 1) "your private tab" else "$tabs private tabs"
    AlertDialog(onDismissRequest = onDismiss, containerColor = SheetNavy, titleContentColor = SheetText, textContentColor = SheetMuted,
        modifier = Modifier.testTag("private-dialog"),
        title = { Text(if (dialog == PrivateDialog.BurnNow) "Clear everything now?" else "Leave Private Mode?") },
        text = {
            Text(if (dialog == PrivateDialog.BurnNow) "This closes ${if (tabs == 0) "Private Mode’s session" else tabsText} and clears cookies, site data, permissions and anything Mylo kept for this session."
                else "Burn session on exit is on, so $tabsText, cookies and site data will be cleared.")
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("private-dialog-confirm")) {
            Text(if (dialog == PrivateDialog.BurnNow) "Clear now" else "Leave and burn", color = Color(0xFFFF9AA0))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (dialog == PrivateDialog.BurnNow) "Cancel" else "Stay", color = SheetAccent) } })
}

/** A short confirmation that dismisses itself. */
@Composable internal fun PrivateMessage(text: String, onDone: () -> Unit) {
    LaunchedEffect(text) { delay(5_000); onDone() }
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
        Surface(color = Color(0xFF26306A), contentColor = SheetText, shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp,
            modifier = Modifier.clickable(onClick = onDone).testTag("private-message")) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 14.sp)
        }
    }
}

/** Covers private tabs until Android's screen lock is passed. */
@Composable internal fun PrivateLockScreen(onUnlock: () -> Unit, onBurnAndLeave: () -> Unit, burnOnExit: Boolean) {
    val activity = LocalContext.current.findActivity()
    // Back never reveals or leaves through the lock: it only sends Mylo to the background.
    androidx.activity.compose.BackHandler { activity?.moveTaskToBack(true) }
    Column(Modifier.fillMaxSize().background(PrivateNight).clickable(enabled = false) {}.windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp)
        .testTag("private-locked"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).background(Brush.verticalGradient(listOf(Color(0xFFB7A9FF), Color(0xFF6A55F2))), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Lock, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Private tabs are locked", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = SheetText)
        Spacer(Modifier.height(8.dp))
        Text("Unlock with your screen lock to continue.", color = SheetMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock, shape = RoundedCornerShape(24.dp), modifier = Modifier.testTag("private-unlock"),
            colors = ButtonDefaults.buttonColors(containerColor = SheetAccent, contentColor = SheetNavy)) { Text("Unlock", fontWeight = FontWeight.SemiBold) }
        if (burnOnExit) TextButton(onClick = onBurnAndLeave) { Text("Burn session and leave", color = Color(0xFFFF9AA0)) }
    }
}

/** A new private tab: search with the provider from Settings, the session's protections at a glance. */
@Composable internal fun PrivateNewTab(session: PrivateSession, focus: Boolean, onFocused: () -> Unit, onSubmit: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focus) {
        if (focus) { runCatching { requester.requestFocus() }; keyboard?.show(); onFocused() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).testTag("private-new-tab-page")) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Brush.verticalGradient(listOf(Color(0xFFB7A9FF), Color(0xFF6A55F2))), CircleShape), contentAlignment = Alignment.Center) {
                Icon(PrivateArt.Incognito, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text("Private session", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = SheetText)
                Text("Same curious you. Just more private.", fontSize = 13.sp, color = SheetMuted)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.searchPill(false).padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = Color(0xFF23286A), modifier = Modifier.padding(start = 6.dp).size(26.dp))
            BasicTextField(query, { query = it }, Modifier.weight(1f).padding(start = 10.dp).focusRequester(requester).testTag("private-search-input")
                .semantics { contentDescription = "Search privately or enter address" }, singleLine = true, textStyle = SearchTextStyle,
                cursorBrush = SolidColor(Color(0xFF493B96)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSubmit(query) }),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (query.isEmpty()) Text("Search privately or enter address", style = SearchPlaceholderStyle, maxLines = 1); inner() } })
            if (query.isNotBlank()) IconButton(onClick = { onSubmit(query) }) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Go", tint = Color(0xFF23286A)) }
        }
        Spacer(Modifier.height(20.dp))
        StatCard(listOf(
            "${session.engine.trackersBlocked}" to if (session.settings.blockTrackers) "trackers blocked this session" else "tracker blocking is off",
            "${session.tabs.tabs.size}" to if (session.tabs.tabs.size == 1) "private tab" else "private tabs",
        ))
        Spacer(Modifier.height(14.dp))
        Text(if (session.settings.burnOnExit) "Leaving Private Mode burns this session: tabs, cookies, site data and permissions."
            else "Burn session on exit is off: private tabs stay open until you burn them.", color = SheetMuted, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
        SheetNote(PRIVATE_LIMIT)
        Spacer(Modifier.height(24.dp))
    }
}

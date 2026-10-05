package com.mylo.browser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mylo.browser.shield.ConnectStep
import com.mylo.browser.shield.DisconnectReason
import com.mylo.browser.shield.ExitStatus
import com.mylo.browser.shield.MyloShield
import com.mylo.browser.shield.ReconnectCause
import com.mylo.browser.shield.ServerChoice
import com.mylo.browser.shield.ServerDirectory
import com.mylo.browser.shield.ShieldProblem
import com.mylo.browser.shield.ShieldEndpoint
import com.mylo.browser.shield.ShieldServer
import com.mylo.browser.shield.ShieldState
import com.mylo.browser.shield.SystemVpnSettings
import com.mylo.browser.shield.FastestServer
import kotlinx.coroutines.delay

private val ShieldInk = Color(0xFFF5F4FF)
private val ShieldMuted = Color(0xFFAEB7DA)
private val ShieldLavender = Color(0xFFCEC5FF)
private val ShieldTeal = Color(0xFF34C18E)
private val ShieldTealLight = Color(0xFF4FD0A8)
private val ShieldAmber = Color(0xFFFFC56B)
private val ShieldRose = Color(0xFFFF9AA8)
private val ShieldIdle = Color(0xFF3A4766)
private val ShieldCard = Brush.linearGradient(listOf(Color(0xFF1A2850), Color(0xFF101F41), Color(0xFF0F1E40)))
private val ShieldCardEdge = Brush.verticalGradient(listOf(Color(0xFF34457A), Color(0xFF15213F)))
private val SheetNavy = Color(0xFF111D42)

/** Everything the Shield screen shows; built only from the engine's real state and this device's measurements. */
data class ShieldUi(
    val state: ShieldState,
    val directory: ServerDirectory,
    val choice: ServerChoice,
    val autoConnect: Boolean,
    val systemVpn: SystemVpnSettings?,
    val nowMillis: Long,
    /** Debug builds only: the test gateway entered on this device (null in release builds). */
    val testGatewayUrl: String? = null,
)

/** Mylo Shield with its real engine: disclosure, Android's VPN permission, then connect. */
@Composable fun ShieldRoute(onClose: () -> Unit) {
    val context = LocalContext.current
    val shield = remember { MyloShield.get(context) }
    val state by shield.engine.state.collectAsState()
    val directory by shield.engine.directory.collectAsState()
    val autoConnect by shield.preferences.autoConnect.collectAsState()
    val systemVpn by shield.systemVpnSettings.collectAsState()
    var choice by remember { mutableStateOf(shield.preferences.choice) }
    var consentFor by remember { mutableStateOf<ServerChoice?>(null) }
    var pendingPermission by remember { mutableStateOf<ServerChoice?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { if (shield.configured) shield.engine.refreshServers(measure = true) }
    val ticking = state is ShieldState.Connected || state is ShieldState.Reconnecting
    LaunchedEffect(ticking) {
        while (ticking) { now = System.currentTimeMillis(); delay(1_000) }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // The Shield notification is optional; ask once, after Android's VPN permission, so dialogs never overlap.
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !shield.preferences.notificationPromptShown) {
            shield.preferences.markNotificationPromptShown()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requested = pendingPermission
        pendingPermission = null
        if (requested != null && result.resultCode == Activity.RESULT_OK) { shield.connect(context, requested); askForNotifications() }
        else shield.engine.onPermissionDenied()
    }
    fun connect(target: ServerChoice) {
        choice = target
        if (!shield.preferences.consented) { consentFor = target; return }
        val prompt = VpnService.prepare(context)
        if (prompt == null) { shield.connect(context, target); askForNotifications() }
        else { pendingPermission = target; vpnPermission.launch(prompt) }
    }

    var testGatewayUrl by remember { mutableStateOf(shield.preferences.testGateway?.baseUrl) }

    BackHandler(onBack = onClose)
    ShieldScreen(
        ui = ShieldUi(state, directory, choice, autoConnect, systemVpn, now, testGatewayUrl),
        onClose = onClose,
        onConnect = { connect(choice) },
        onDisconnect = { shield.disconnect() },
        onChoose = { connect(it) },
        onMeasure = { shield.engine.refreshServers(measure = true) },
        onAutoConnect = { shield.preferences.setAutoConnect(it) },
        onOpenVpnSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } },
        onTestGateway = if (BuildConfig.DEBUG) { endpoint ->
            shield.setTestGateway(endpoint).also { saved ->
                if (saved) {
                    testGatewayUrl = endpoint?.baseUrl?.trim()
                    if (shield.configured) shield.engine.refreshServers(measure = true)
                }
            }
        } else null,
    )
    consentFor?.let { target ->
        ShieldDisclosureSheet(
            onAgree = {
                shield.preferences.recordConsent()
                consentFor = null
                connect(target)
            },
            onDismiss = { consentFor = null },
        )
    }
}

/** Exposes test tags to UiAutomator, which drives Shield alongside Android's own VPN dialog. */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.automationIds() = semantics { testTagsAsResourceId = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ShieldScreen(
    ui: ShieldUi,
    onClose: () -> Unit = {},
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onChoose: (ServerChoice) -> Unit = {},
    onMeasure: () -> Unit = {},
    onAutoConnect: (Boolean) -> Unit = {},
    onOpenVpnSettings: () -> Unit = {},
    /** Debug builds only: saves (or clears, with null) the device's test gateway; false when refused. */
    onTestGateway: ((ShieldEndpoint?) -> Boolean)? = null,
) {
    var picking by remember { mutableStateOf(false) }
    var editingGateway by remember { mutableStateOf(false) }
    var guidance by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val state = ui.state
    val unavailable = state is ShieldState.Error && state.problem == ShieldProblem.NotConfigured
    Column(Modifier.fillMaxSize().background(HomeNight).windowInsetsPadding(WindowInsets.safeDrawing).automationIds().testTag("shield-screen")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = ShieldInk) }
            Text("Mylo Shield", color = ShieldInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            ConnectButton(state, enabled = !unavailable, onConnect = onConnect, onDisconnect = onDisconnect)
            Spacer(Modifier.height(18.dp))
            StatusText(state, ui.nowMillis)
            if (state is ShieldState.Error && !unavailable) {
                Spacer(Modifier.height(12.dp))
                ShieldPill("Try again", Icons.Rounded.Refresh, onConnect)
            }
            Spacer(Modifier.height(24.dp))
            if (unavailable) SetupRequiredCard()
            else LocationCard(ui) { picking = true }
            val connected = state as? ShieldState.Connected
            if (connected != null) { Spacer(Modifier.height(10.dp)); DetailsCard(connected) }
            Spacer(Modifier.height(10.dp))
            ShieldCardBox {
                OptionRow("Auto-connect when Mylo opens", "Connects to your last choice; Android's VPN permission is needed first",
                    trailing = {
                        Switch(ui.autoConnect, onAutoConnect, enabled = !unavailable, modifier = Modifier.testTag("shield-auto-connect"),
                            colors = SwitchDefaults.colors(checkedTrackColor = ShieldTeal, uncheckedTrackColor = ShieldIdle, uncheckedBorderColor = ShieldIdle))
                    })
                Divider()
                OptionRow("Always-on VPN & kill switch", ui.systemVpn?.let {
                    "Always-on ${if (it.alwaysOn) "on" else "off"} · Block without VPN ${if (it.blockWithoutVpn) "on" else "off"}"
                } ?: "Set up in Android's VPN settings", onClick = { guidance = true })
                Divider()
                OptionRow("How Mylo Shield works", "What is sent through the tunnel and who can see it", onClick = { about = true })
                if (onTestGateway != null) {
                    Divider()
                    OptionRow("Test gateway (debug build)", ui.testGatewayUrl ?: "None: enter your test Mylo Shield service",
                        onClick = { editingGateway = true })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (picking) ServerSheet(ui, onDismiss = { picking = false }, onMeasure = onMeasure) { picked -> picking = false; onChoose(picked) }
    if (guidance) GuidanceSheet(ui.systemVpn, onDismiss = { guidance = false }, onOpenVpnSettings = onOpenVpnSettings)
    if (about) ShieldDisclosureSheet(onAgree = null, onDismiss = { about = false })
    if (editingGateway && onTestGateway != null) TestGatewaySheet(ui.testGatewayUrl, onDismiss = { editingGateway = false }) { endpoint ->
        onTestGateway(endpoint).also { if (it) editingGateway = false }
    }
}

/** Debug builds only: point this device at a test Mylo Shield service without putting secrets in the APK. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TestGatewaySheet(current: String?, onDismiss: () -> Unit, onSave: (ShieldEndpoint?) -> Boolean) {
    var url by remember { mutableStateOf(current ?: "https://") }
    var token by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = ShieldInk, unfocusedTextColor = ShieldInk,
        focusedBorderColor = ShieldLavender, unfocusedBorderColor = Color(0xFF46558A), focusedLabelColor = ShieldLavender, unfocusedLabelColor = ShieldMuted, cursorColor = ShieldLavender)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetNavy, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("shield-test-gateway")) {
            Text("Test gateway", color = ShieldInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("Debug builds only. The address and access token stay in Mylo's private storage on this device and are never part of the app or its source.",
                color = ShieldMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
            OutlinedTextField(url, { url = it; refused = false }, Modifier.fillMaxWidth().padding(top = 14.dp), label = { Text("Mylo Shield service URL") },
                singleLine = true, colors = fieldColors)
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Access token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors)
            if (refused) Text("Use an https:// address (http:// only for a development service on 10.0.2.2 or this device).",
                color = ShieldRose, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (current != null) ShieldPill("Remove", Icons.Rounded.ErrorOutline) { onSave(null) }
                ShieldPill("Save", Icons.Rounded.CheckCircle) { refused = !onSave(ShieldEndpoint(url, token.ifBlank { null })) }
            }
        }
    }
}

@Composable private fun ConnectButton(state: ShieldState, enabled: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val busy = state is ShieldState.Connecting || state is ShieldState.Reconnecting
    val on = state is ShieldState.Connected
    val ring = when {
        !enabled -> ShieldIdle
        on -> ShieldTeal
        state is ShieldState.Reconnecting -> ShieldAmber
        busy -> ShieldLavender
        state is ShieldState.Error -> ShieldRose
        else -> ShieldIdle
    }
    // Spins only while connecting, so an idle screen has no running animation.
    val spin = if (busy) rememberInfiniteTransition(label = "spin")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart), label = "arc").value else 0f
    val label = when {
        !enabled -> "VPN unavailable"
        on || busy -> "Disconnect Mylo Shield"
        else -> "Connect Mylo Shield"
    }
    Box(Modifier.size(184.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { if (on || busy) onDisconnect() else onConnect() }
        .semantics { contentDescription = label; stateDescription = shieldTitle(state) }
        .testTag("shield-connect"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(ring.copy(alpha = .22f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (busy) drawArc(ring, spin, 80f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            else drawArc(ring, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        }
        Box(Modifier.size(140.dp).background(Brush.radialGradient(listOf(Color(0xFF22336A), Color(0xFF111D42))), CircleShape)
            .border(1.dp, Color(0x22FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shield, null, Modifier.size(64.dp), tint = if (on) ShieldTealLight else if (enabled) ShieldLavender else ShieldIdle)
            Icon(Icons.Rounded.Lock, null, Modifier.padding(top = 4.dp).size(22.dp), tint = HomeNight)
        }
    }
}

private fun shieldTitle(state: ShieldState): String = when (state) {
    is ShieldState.Disconnected -> "Not connected"
    is ShieldState.Connecting -> "Connecting…"
    is ShieldState.Connected -> "Connected"
    is ShieldState.Reconnecting -> "Reconnecting…"
    is ShieldState.Error -> if (state.problem == ShieldProblem.NotConfigured) "VPN unavailable" else "Couldn't connect"
}

@Composable private fun StatusText(state: ShieldState, now: Long) {
    val detail = when (state) {
        is ShieldState.Disconnected -> when (state.reason) {
            DisconnectReason.Revoked -> "Android turned Mylo Shield off (another VPN or a change in Settings)"
            DisconnectReason.ServiceStopped -> "The VPN service stopped"
            else -> "Tap to connect"
        }
        is ShieldState.Connecting -> when (state.step) {
            ConnectStep.LoadingServers -> "Getting servers from the Mylo Shield service"
            ConnectStep.MeasuringServers -> "Measuring servers from this device"
            ConnectStep.RequestingSession -> "Requesting a session for ${state.server?.label ?: "the server"}"
            ConnectStep.StartingTunnel -> "Starting WireGuard"
            ConnectStep.Handshaking -> "Waiting for ${state.server?.city ?: "the server"} to answer"
            ConnectStep.VerifyingExit -> "Checking where your traffic leaves the internet"
        }
        is ShieldState.Connected -> "${state.server.label} · ${duration(now - state.sinceMillis)}"
        is ShieldState.Reconnecting -> when (state.cause) {
            ReconnectCause.NetworkLost -> "Waiting for a network"
            ReconnectCause.HandshakeStale -> "${state.server.city} stopped answering"
            ReconnectCause.RenewingSession -> "Renewing the session with ${state.server.city}"
        }
        is ShieldState.Error -> problemText(state.problem)
    }
    Text(shieldTitle(state), color = ShieldInk, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("shield-status"))
    Text(detail, color = ShieldMuted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp).testTag("shield-detail"))
}

private fun problemText(problem: ShieldProblem): String = when (problem) {
    ShieldProblem.NotConfigured -> "Server setup required"
    ShieldProblem.PermissionDenied -> "Android's VPN permission wasn't granted"
    ShieldProblem.Unauthorized -> "The Mylo Shield service didn't accept this device"
    ShieldProblem.ServiceUnreachable -> "Couldn't reach the Mylo Shield service"
    ShieldProblem.InvalidResponse -> "The service's answer failed Mylo's checks, so nothing was connected"
    ShieldProblem.NoReachableServer -> "No Mylo Shield server answered from this network"
    ShieldProblem.ServerUnavailable -> "That server isn't available right now"
    ShieldProblem.TunnelFailed -> "Android couldn't start the WireGuard tunnel"
    ShieldProblem.HandshakeTimeout -> "The server didn't complete a WireGuard handshake"
    ShieldProblem.ExitMismatch -> "Traffic wasn't leaving through the chosen server, so Mylo disconnected"
    ShieldProblem.ReconnectFailed -> "The connection dropped and couldn't be restored"
}

private fun duration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

@Composable private fun SetupRequiredCard() {
    ShieldCardBox(Modifier.testTag("shield-setup-required")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, tint = ShieldLavender, modifier = Modifier.size(22.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text("Server setup required", color = ShieldInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("This build of Mylo has no Mylo Shield server, so it can't connect and nothing is protected by Mylo Shield. " +
                    "Locations appear here once a real server is set up.", color = ShieldMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable private fun LocationCard(ui: ShieldUi, onClick: () -> Unit) {
    val loaded = ui.directory as? ServerDirectory.Loaded
    val chosen = (ui.choice as? ServerChoice.Specific)?.let { choice -> loaded?.servers?.firstOrNull { it.id == choice.serverId } }
    val fastest = loaded?.let { FastestServer.pick(it.servers, it.measurements) }
    val (title, subtitle) = when {
        ui.directory is ServerDirectory.Loading -> "Location" to "Getting servers…"
        ui.directory is ServerDirectory.Failed -> "Location" to problemText((ui.directory as ServerDirectory.Failed).problem)
        loaded != null && loaded.servers.isEmpty() -> "Location" to "The Mylo Shield service lists no servers"
        chosen != null -> chosen.label to latencyText(loaded, chosen)
        else -> "Fastest available" to (fastest?.let { "${it.label} · ${latencyText(loaded, it)}" } ?: "Measured when you connect")
    }
    ShieldCardBox(Modifier.clickable(role = Role.Button, onClickLabel = "Choose a location", onClick = onClick).testTag("shield-location")) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(chosen?.countryCode, fastest = chosen == null)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, color = ShieldInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = ShieldMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ShieldMuted)
        }
    }
}

private fun latencyText(directory: ServerDirectory.Loaded?, server: ShieldServer): String {
    val measured = directory?.measurements?.get(server.id) ?: return "Not measured yet"
    return if (measured.reachable && measured.latencyMs != null) "${measured.latencyMs} ms from this device" else "Didn't answer from this network"
}

@Composable private fun Badge(countryCode: String?, fastest: Boolean) {
    Box(Modifier.size(38.dp).background(if (fastest) Brush.linearGradient(listOf(Color(0xFF8A7BFA), Color(0xFF5A48E2))) else Brush.linearGradient(listOf(Color(0xFF22345A), Color(0xFF1A2A4E))), CircleShape),
        contentAlignment = Alignment.Center) {
        if (fastest || countryCode == null) Icon(Icons.Rounded.Bolt, null, tint = Color.White, modifier = Modifier.size(20.dp))
        else Text(flag(countryCode), fontSize = 18.sp)
    }
}

/** Regional-indicator flag for an ISO country code, as the Shield service reports it. */
private fun flag(countryCode: String): String =
    countryCode.uppercase().filter { it in 'A'..'Z' }.take(2).map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

@Composable private fun DetailsCard(state: ShieldState.Connected) {
    ShieldCardBox(Modifier.testTag("shield-details")) {
        DetailRow("Exit IP", when (val exit = state.exit) {
            is ExitStatus.Verified -> exit.ip
            is ExitStatus.Unverified -> "Not verified"
        }, verified = state.exit is ExitStatus.Verified)
        (state.exit as? ExitStatus.Unverified)?.let { Text(it.why, color = ShieldMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) }
        Divider()
        DetailRow("Server", state.server.hostname)
        Divider()
        DetailRow("Protocol", "WireGuard")
        Divider()
        DetailRow("IPv6", if (state.ipv6) "Through the tunnel" else "Blocked while connected")
    }
}

@Composable private fun DetailRow(label: String, value: String, verified: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ShieldMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = ShieldInk, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
        if (verified) Icon(Icons.Rounded.CheckCircle, "Verified by the Mylo Shield service", tint = ShieldTeal, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

@Composable private fun OptionRow(title: String, subtitle: String, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ShieldInk, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = ShieldMuted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Box(Modifier.padding(start = 10.dp)) { trailing() }
        else if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ShieldMuted)
    }
}

@Composable private fun ShieldCardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(ShieldCard).border(1.dp, ShieldCardEdge, shape).then(modifier), content = content)
}

@Composable private fun Divider() = Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Color(0x1FAEB7DA)))

@Composable private fun ShieldPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.height(40.dp).clip(CircleShape).background(Color(0xFF26346A)).border(1.dp, Color(0xFF6E62D9), CircleShape)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ShieldLavender, modifier = Modifier.size(18.dp))
        Text(text, color = ShieldInk, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ServerSheet(ui: ShieldUi, onDismiss: () -> Unit, onMeasure: () -> Unit, onPick: (ServerChoice) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetNavy, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).automationIds().testTag("shield-servers")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Choose a location", color = ShieldInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("From the Mylo Shield service. Times are measured from this device.", color = ShieldMuted, fontSize = 12.5.sp)
                }
                if (ui.directory is ServerDirectory.Loaded) IconButton(onClick = onMeasure) { Icon(Icons.Rounded.Refresh, "Measure again", tint = ShieldLavender) }
            }
            Spacer(Modifier.height(12.dp))
            when (val directory = ui.directory) {
                ServerDirectory.NotConfigured -> SheetNote("Server setup required. This build has no Mylo Shield server, so there are no locations to show.")
                ServerDirectory.Loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = ShieldLavender, strokeWidth = 2.dp)
                    Text("Getting servers…", color = ShieldMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                }
                is ServerDirectory.Failed -> SheetNote(problemText(directory.problem))
                is ServerDirectory.Loaded -> {
                    if (directory.servers.isEmpty()) SheetNote("The Mylo Shield service lists no servers.")
                    else {
                        val fastest = FastestServer.pick(directory.servers, directory.measurements)
                        ServerRow("Fastest available", fastest?.let { "${it.label} · ${latencyText(directory, it)}" } ?: "Measured when you connect",
                            countryCode = null, selected = ui.choice == ServerChoice.Fastest, enabled = true) { onPick(ServerChoice.Fastest) }
                        directory.servers.groupBy { it.country }.toSortedMap().forEach { (country, servers) ->
                            Text(country, color = ShieldMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp))
                            servers.sortedBy { it.city }.forEach { server ->
                                val measured = latencyText(directory, server) + (server.load?.let { " · load $it%" } ?: "")
                                ServerRow(server.city, if (server.available) measured else "Unavailable", server.countryCode,
                                    selected = (ui.choice as? ServerChoice.Specific)?.serverId == server.id, enabled = server.available) {
                                    onPick(ServerChoice.Specific(server.id))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ServerRow(title: String, subtitle: String, countryCode: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape).background(if (selected) Color(0xFF26346A) else Color.Transparent)
        .border(1.dp, if (selected) Color(0xFF6E62D9) else Color.Transparent, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Badge(countryCode, fastest = countryCode == null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = if (enabled) ShieldInk else ShieldMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = ShieldMuted, fontSize = 12.sp)
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", tint = ShieldLavender, modifier = Modifier.size(20.dp))
    }
}

@Composable private fun SheetNote(text: String) {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = ShieldLavender, modifier = Modifier.size(20.dp))
        Text(text, color = ShieldMuted, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun GuidanceSheet(settings: SystemVpnSettings?, onDismiss: () -> Unit, onOpenVpnSettings: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetNavy, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Icon(Icons.Rounded.SettingsSuggest, null, tint = ShieldLavender, modifier = Modifier.size(30.dp))
            Text("Always-on VPN & kill switch", color = ShieldInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text("Android itself can keep Mylo Shield on and block all traffic whenever the tunnel is down. Mylo can't turn this on for you.",
                color = ShieldMuted, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
            listOf("Open Android's VPN settings.", "Tap the gear next to Mylo.", "Turn on Always-on VPN and Block connections without VPN.").forEachIndexed { index, step ->
                Text("${index + 1}. $step", color = ShieldInk, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            }
            Text("While both are on, apps can't reach the internet until Mylo Shield connects. Switching servers or renewing a session briefly takes the tunnel down; with Block connections without VPN, nothing leaks during that moment.",
                color = ShieldMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp))
            settings?.let {
                Text("Now: Always-on ${if (it.alwaysOn) "on" else "off"} · Block connections without VPN ${if (it.blockWithoutVpn) "on" else "off"}",
                    color = ShieldLavender, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(16.dp))
            ShieldPill("Open Android VPN settings", Icons.Rounded.SettingsSuggest, onOpenVpnSettings)
        }
    }
}

/**
 * The prominent disclosure shown before Android's VPN permission (and again from "How Mylo Shield works").
 * It describes what the implementation does and claims nothing the app or its servers can't back up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ShieldDisclosureSheet(onAgree: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetNavy, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).automationIds().testTag("shield-disclosure")) {
            Icon(Icons.Rounded.Shield, null, tint = ShieldTealLight, modifier = Modifier.size(30.dp))
            Text(if (onAgree != null) "Before you turn on Mylo Shield" else "How Mylo Shield works",
                color = ShieldInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            SHIELD_DISCLOSURE.forEach { paragraph ->
                Text(paragraph, color = ShieldMuted, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
            }
            if (onAgree != null) {
                Text("Android will ask you to allow the VPN connection next.", color = ShieldInk, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(48.dp).clip(CircleShape).border(1.dp, Color(0xFF5A57A8), CircleShape)
                        .clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) {
                        Text("Not now", color = Color(0xFFE6E2FF), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Box(Modifier.weight(1f).height(48.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Color(0xFFD3CAFF), Color(0xFFB4A6FF))))
                        .clickable(role = Role.Button, onClick = onAgree).testTag("shield-disclosure-agree"), contentAlignment = Alignment.Center) {
                        Text("Agree and continue", color = Color(0xFF231C5C), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Mylo Shield's disclosure text; kept in sync with docs/shield/PLAY_VPN_DECLARATION.md. */
val SHIELD_DISCLOSURE = listOf(
    "Mylo Shield uses Android's VPN feature. While it is on, this device's internet traffic, from Mylo and from your other apps, " +
        "goes through an encrypted WireGuard tunnel to the Mylo Shield server you choose, and leaves the internet from that server.",
    "The Mylo Shield server receives your device's IP address and can see which sites and services your traffic goes to, as with any VPN. " +
        "What the server keeps is set out in the Mylo Shield privacy policy.",
    "On your device, Mylo logs only whether Shield is connecting, connected or stopped. It doesn't log your browsing, DNS requests or page contents.",
    "Mylo Shield doesn't make you anonymous: websites can still recognise you through accounts, cookies and your browser.",
)

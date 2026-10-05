package com.mylo.browser.shield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mylo.browser.BuildConfig
import com.mylo.browser.MainActivity
import com.mylo.browser.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/** Android's own Always-on VPN settings for Mylo, read from the running VPN service (Android 10+). */
data class SystemVpnSettings(val alwaysOn: Boolean, val blockWithoutVpn: Boolean)

/** Shield preferences kept on this device. */
class ShieldPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("mylo_shield", Context.MODE_PRIVATE)
    private val _autoConnect = MutableStateFlow(prefs.getBoolean(AUTO_CONNECT, false))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    var choice: ServerChoice
        get() = ServerChoice.decode(prefs.getString(CHOICE, null))
        set(value) = prefs.edit().putString(CHOICE, value.encode()).apply()

    /** Whether the user agreed to the current version of the Shield disclosure. */
    val consented: Boolean get() = prefs.getInt(CONSENT, 0) >= CONSENT_VERSION

    fun recordConsent() = prefs.edit().putInt(CONSENT, CONSENT_VERSION).apply()

    val notificationPromptShown: Boolean get() = prefs.getBoolean(NOTIFICATION_PROMPT, false)
    fun markNotificationPromptShown() = prefs.edit().putBoolean(NOTIFICATION_PROMPT, true).apply()

    fun setAutoConnect(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO_CONNECT, enabled).apply()
        _autoConnect.value = enabled
    }

    /** Debug builds only: a test gateway entered on this device, kept in Mylo's private storage. */
    val testGateway: ShieldEndpoint?
        get() = prefs.getString(TEST_URL, null)?.takeIf { it.isNotBlank() }?.let { ShieldEndpoint(it, prefs.getString(TEST_TOKEN, null)) }

    fun setTestGateway(endpoint: ShieldEndpoint?) {
        prefs.edit().apply {
            if (endpoint == null) { remove(TEST_URL); remove(TEST_TOKEN) }
            else { putString(TEST_URL, endpoint.baseUrl.trim()); putString(TEST_TOKEN, endpoint.accessToken?.trim()) }
        }.commit()
    }

    companion object {
        /** Raise when the disclosure text changes materially, so users see it again. */
        const val CONSENT_VERSION = 1
        private const val CHOICE = "choice"
        private const val CONSENT = "consent_version"
        private const val AUTO_CONNECT = "auto_connect"
        private const val NOTIFICATION_PROMPT = "notification_prompt_shown"
        private const val TEST_URL = "test_gateway_url"
        private const val TEST_TOKEN = "test_gateway_token"
    }
}

/** Process-wide Mylo Shield: one engine, one WireGuard backend and one VPN service per app process. */
object MyloShield {
    const val NOTIFICATION_ID = 7_001
    const val ACTION_OPEN_SHIELD = "com.mylo.browser.OPEN_SHIELD"
    private const val CHANNEL = "mylo_shield"

    @Volatile private var runtime: ShieldRuntime? = null

    fun get(context: Context): ShieldRuntime =
        runtime ?: synchronized(this) { runtime ?: ShieldRuntime(context.applicationContext).also { runtime = it } }

    /** The ongoing notification while a tunnel carries traffic; it states only what the engine has verified. */
    fun notification(context: Context, state: ShieldState): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Mylo Shield", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Mylo Shield's VPN tunnel is on"
                setShowBadge(false)
            })
        }
        val (title, text) = when (state) {
            is ShieldState.Connected -> "Mylo Shield is on" to buildString {
                append(state.server.label)
                append(if (state.exit is ExitStatus.Verified) " · exit verified" else " · exit not verified")
            }
            is ShieldState.Reconnecting -> "Mylo Shield is reconnecting" to state.server.label
            is ShieldState.Connecting -> "Mylo Shield is connecting" to (state.server?.label ?: "Finding a server")
            else -> "Mylo Shield" to "Not connected"
        }
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_SHIELD).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val disconnect = PendingIntent.getService(context, 1,
            Intent(context, MyloVpnService::class.java).setAction(MyloVpnService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Disconnect", disconnect)
            .build()
    }
}

class ShieldRuntime internal constructor(private val app: Context) {
    val preferences = ShieldPreferences(app)
    private val clock = { System.currentTimeMillis() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = HttpShieldService(endpoint = ::endpoint, allowDevCleartext = BuildConfig.DEBUG, now = clock)
    val configured: Boolean get() = service.configured

    /** A debug test gateway entered on the device wins; otherwise the build's own setting, if any. */
    private fun endpoint(): ShieldEndpoint? =
        (if (BuildConfig.DEBUG) preferences.testGateway else null)
            ?: BuildConfig.SHIELD_API_BASE_URL.takeIf { it.isNotBlank() }?.let { ShieldEndpoint(it, BuildConfig.SHIELD_DEV_TOKEN.ifBlank { null }) }

    /** Debug builds only: point Shield at a test gateway, or clear it. Refused when the URL isn't acceptable. */
    fun setTestGateway(endpoint: ShieldEndpoint?): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (endpoint != null && HttpShieldService.validatedBase(endpoint.baseUrl.trim(), allowDevCleartext = true) == null) return false
        preferences.setTestGateway(endpoint)
        engine.configurationChanged()
        return true
    }
    val engine = ShieldEngine(
        service = service,
        driver = WireGuardDriver(app, ::holdService),
        meter = TcpLatencyMeter(app, clock),
        keys = WireGuardKeys,
        clock = clock,
        scope = scope,
        log = { Log.i("MyloShield", it) },
    )

    private val _systemVpnSettings = MutableStateFlow<SystemVpnSettings?>(null)
    /** Android's Always-on / "Block connections without VPN" settings for Mylo, known while the service runs. */
    val systemVpnSettings: StateFlow<SystemVpnSettings?> = _systemVpnSettings.asStateFlow()

    /** Starts the VPN service and connects to [choice]. Call only after consent and [VpnService.prepare]. */
    fun connect(context: Context, choice: ServerChoice) {
        preferences.choice = choice
        context.startService(Intent(context, MyloVpnService::class.java)
            .setAction(MyloVpnService.ACTION_CONNECT)
            .putExtra(MyloVpnService.EXTRA_CHOICE, choice.encode()))
    }

    fun disconnect() { engine.disconnect() }

    /**
     * Connecting without a tap (auto-connect at launch, Android's Always-on VPN) requires the disclosure
     * to have been accepted, a configured service, and Android's VPN permission already granted.
     */
    fun canConnectUnattended(): Boolean =
        preferences.consented && configured && VpnService.prepare(app) == null

    @Volatile private var launchHandled = false

    /** Auto-connect once per app launch (not again after rotation or a deliberate disconnect). */
    fun autoConnectOnLaunch(context: Context) {
        if (launchHandled) return
        launchHandled = true
        val idle = engine.state.value.let { it is ShieldState.Disconnected || it is ShieldState.Error }
        if (preferences.autoConnect.value && idle && canConnectUnattended()) connect(context, preferences.choice)
    }

    internal fun reportSystemVpnSettings(settings: SystemVpnSettings) { _systemVpnSettings.value = settings }

    // --- keeping the VPN service alive across WireGuard reconfiguration ----------------------------

    private val holdLock = Any()
    private var hold: ServiceConnection? = null
    private var holdReady = CompletableDeferred<Unit>()

    /**
     * Binds to [MyloVpnService] so it outlives GoBackend's internal stopSelf() while a tunnel is
     * replaced (server switch, session renewal), and waits until the service exists.
     */
    private suspend fun holdService() {
        val ready = synchronized(holdLock) {
            if (hold == null) {
                holdReady = CompletableDeferred()
                val deferred = holdReady
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { deferred.complete(Unit) }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                    override fun onNullBinding(name: ComponentName?) { deferred.complete(Unit) }
                }
                val intent = Intent(app, MyloVpnService::class.java).setAction(MyloVpnService.ACTION_HOLD)
                if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    throw ShieldException(ShieldProblem.TunnelFailed, "The VPN service could not be started")
                }
                hold = connection
            }
            holdReady
        }
        try {
            withTimeout(5_000) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            throw ShieldException(ShieldProblem.TunnelFailed, "The VPN service did not start")
        }
    }

    internal fun releaseService() = synchronized(holdLock) {
        hold?.let { runCatching { app.unbindService(it) } }
        hold = null
    }

    internal fun serviceStopped() {
        _systemVpnSettings.value = null
        releaseService()
    }
}

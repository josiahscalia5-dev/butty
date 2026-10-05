package com.mylo.browser.shield

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mylo's Android [android.net.VpnService] (through WireGuard's [GoBackend.VpnService], which hands this
 * service's VPN builder to wireguard-go). Declared with BIND_VPN_SERVICE; Android's VPN permission dialog
 * ([android.net.VpnService.prepare]) runs in the UI before any connect reaches it.
 *
 * It runs in the foreground with an ongoing notification while a tunnel is carrying traffic, reports
 * [onRevoke] and network changes to the engine, starts the remembered choice for Android's Always-on VPN,
 * and stops itself once Shield is idle.
 */
class MyloVpnService : GoBackend.VpnService() {
    private val binder = Binder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var foreground = false
    private var sawActivity = false
    /** The latest start request; a stop for an older one is ignored, so a quick retry is never cut off. */
    private var lastStartId = 0

    private val runtime get() = MyloShield.get(this)

    override fun onCreate() {
        super.onCreate()
        watchUnderlyingNetworks()
        scope.launch { runtime.engine.state.collect(::render) }
    }

    /** Android binds with [SERVICE_INTERFACE]; Mylo itself holds the service with a plain in-process binding. */
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        val job = when (intent?.action) {
            ACTION_CONNECT -> runtime.engine.connect(ServerChoice.decode(intent.getStringExtra(EXTRA_CHOICE)))
            ACTION_DISCONNECT -> runtime.engine.disconnect()
            // Android's Always-on VPN (or a restart) started the service without a request from the UI.
            else -> if (runtime.canConnectUnattended()) runtime.engine.connect(runtime.preferences.choice) else null
        }
        if (job == null) stopIfIdle(startId) else job.invokeOnCompletion { scope.launch { stopIfIdle(startId) } }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        Log.i(TAG, "Shield: VPN revoked by Android")
        runtime.engine.onRevoked()
        super.onRevoke()
    }

    override fun onDestroy() {
        scope.cancel()
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        removeForeground()
        super.onDestroy() // GoBackend turns WireGuard off here if it is still running.
        runtime.engine.onServiceStopped()
        runtime.serviceStopped()
    }

    private fun render(state: ShieldState) {
        if (state !is ShieldState.Disconnected && state !is ShieldState.Error) sawActivity = true
        if (state.tunnelActive) {
            showForeground(MyloShield.notification(this, state))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runtime.reportSystemVpnSettings(SystemVpnSettings(isAlwaysOn, isLockdownEnabled))
        } else {
            removeForeground()
        }
        if (sawActivity) stopIfIdle()
    }

    private fun stopIfIdle(startId: Int = lastStartId) {
        val state = runtime.engine.state.value
        if (state is ShieldState.Disconnected || state is ShieldState.Error) {
            removeForeground()
            // Stops only if no newer start arrived meanwhile; only then let go of the hold as well.
            if (stopSelfResult(startId)) runtime.releaseService()
        }
    }

    private fun showForeground(notification: Notification) {
        try {
            if (!foreground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(MyloShield.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                } else {
                    startForeground(MyloShield.NOTIFICATION_ID, notification)
                }
                foreground = true
            } else {
                getSystemService(NotificationManager::class.java).notify(MyloShield.NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // The tunnel keeps running (Android shows its own VPN key icon); only the notification is missing.
            Log.w(TAG, "Shield: foreground notification unavailable (${e.javaClass.simpleName})")
        }
    }

    private fun removeForeground() {
        if (!foreground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    /** Tells the engine whether any real (non-VPN) network is available underneath the tunnel. */
    private fun watchUnderlyingNetworks() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val available = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { synchronized(available) { available += network; report() } }
            override fun onLost(network: Network) { synchronized(available) { available -= network; report() } }
            private fun report() = runtime.engine.onUnderlyingNetwork(available.isNotEmpty())
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }.onSuccess { networkCallback = callback }
    }

    companion object {
        const val ACTION_CONNECT = "com.mylo.browser.shield.CONNECT"
        const val ACTION_DISCONNECT = "com.mylo.browser.shield.DISCONNECT"
        const val ACTION_HOLD = "com.mylo.browser.shield.HOLD"
        const val EXTRA_CHOICE = "choice"
        private const val TAG = "MyloShield"
    }
}


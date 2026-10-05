package com.mylo.browser.shield

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Runs the tunnel with the official WireGuard implementation (wireguard-go via [GoBackend]). The VPN
 * interface itself belongs to [MyloVpnService], which [holdService] keeps alive while a tunnel is wanted.
 */
class WireGuardDriver(context: Context, private val holdService: suspend () -> Unit) : TunnelDriver {
    private val app = context.applicationContext
    @Volatile private var backend: GoBackend? = null
    @Volatile private var peerKey: Key? = null

    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN) peerKey = null
        }
    }

    private fun backend(): GoBackend = backend ?: synchronized(this) { backend ?: GoBackend(app).also { backend = it } }

    override suspend fun up(spec: TunnelSpec, keys: DeviceKeys) = withContext(Dispatchers.IO) {
        holdService()
        val config = Config.Builder()
            .setInterface(
                Interface.Builder()
                    .parsePrivateKey(keys.privateKey)
                    .parseAddresses(spec.addresses.joinToString(", "))
                    .parseDnsServers(spec.dnsServers.joinToString(", "))
                    .apply { spec.mtu?.let { setMtu(it) } }
                    .build(),
            )
            .addPeer(
                Peer.Builder()
                    .parsePublicKey(spec.peerPublicKey)
                    .parseEndpoint(spec.endpoint)
                    .parseAllowedIPs(spec.allowedIps.joinToString(", "))
                    .setPersistentKeepalive(spec.keepaliveSeconds)
                    .apply { spec.presharedKey?.let { parsePreSharedKey(it) } }
                    .build(),
            )
            .build()
        // Replacing a running tunnel takes it down and up again inside GoBackend; the held service survives that.
        backend().setState(tunnel, Tunnel.State.UP, config)
        peerKey = Key.fromBase64(spec.peerPublicKey)
    }

    override suspend fun down() = withContext(Dispatchers.IO) {
        backend?.setState(tunnel, Tunnel.State.DOWN, null)
        peerKey = null
    }

    override fun latestHandshakeMillis(): Long? {
        val key = peerKey ?: return null
        return runCatching { backend?.getStatistics(tunnel)?.peer(key)?.latestHandshakeEpochMillis() }.getOrNull()?.takeIf { it > 0L }
    }

    companion object {
        const val TUNNEL_NAME = "mylo-shield"
    }
}

/** Fresh WireGuard keys for every session; the private key stays in this process's memory only. */
object WireGuardKeys : KeyMaker {
    override fun newKeys(): DeviceKeys = KeyPair().let { DeviceKeys(it.publicKey.toBase64(), it.privateKey.toBase64()) }
}

/**
 * Measures TCP connect time from this device to each gateway's probe port, outside the tunnel when one is
 * up, so the numbers describe the path to each gateway rather than the current tunnel.
 */
class TcpLatencyMeter(context: Context, private val now: () -> Long) : LatencyMeter {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override suspend fun measure(servers: List<ShieldServer>): Map<String, Measurement> = withContext(Dispatchers.IO) {
        val network = underlyingNetwork()
        coroutineScope { servers.map { server -> async { server.id to measure(server, network) } }.awaitAll().toMap() }
    }

    private fun measure(server: ShieldServer, network: Network?): Measurement {
        val probe = server.probe ?: return Measurement(null, false, now())
        val samples = (1..3).mapNotNull { connectMillis(probe, network) }.sorted()
        return Measurement(samples.getOrNull(samples.size / 2)?.toInt(), samples.isNotEmpty(), now())
    }

    private fun connectMillis(probe: ProbeTarget, network: Network?): Long? {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        return try {
            val started = SystemClock.elapsedRealtime()
            socket.connect(InetSocketAddress(probe.host, probe.port), 2_000)
            SystemClock.elapsedRealtime() - started
        } catch (e: IOException) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun underlyingNetwork(): Network? = connectivity?.allNetworks?.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
    }
}

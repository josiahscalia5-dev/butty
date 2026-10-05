package com.mylo.browser.shield

/** The only tunnel protocol Mylo Shield speaks: standard WireGuard. Mylo adds no protocol or encryption of its own. */
enum class VpnProtocol(val wireName: String) { WIREGUARD("wireguard") }

/** Where latency is measured for one gateway: a TCP port the gateway answers on, such as its HTTPS health port. */
data class ProbeTarget(val host: String, val port: Int)

/**
 * One real Mylo Shield gateway, exactly as the Shield service lists it. [country] and [city] are where the
 * gateway actually runs; Mylo never shows a location that is not in the service's list.
 */
data class ShieldServer(
    val id: String,
    /** ISO 3166-1 alpha-2, upper case. */
    val countryCode: String,
    val country: String,
    val city: String,
    /** The gateway's DNS name, for display and diagnostics only. */
    val hostname: String,
    /** Numeric `ip:port` (or `[ipv6]:port`) WireGuard connects to, so no DNS lookup happens while connecting. */
    val endpoint: String,
    /** The gateway's WireGuard public key, base64. */
    val publicKey: String,
    val protocol: VpnProtocol,
    val supportsIpv6: Boolean,
    val probe: ProbeTarget?,
    /** Load from 0 to 100 as reported by the service, or null when it does not report one. */
    val load: Int?,
    val available: Boolean,
) {
    val label: String get() = "$city, $country"
}

/** A latency measurement taken on this device; never estimated. */
data class Measurement(val latencyMs: Int?, val reachable: Boolean, val measuredAtMillis: Long)

/** What the user picked: the measured fastest reachable gateway, or one specific gateway. */
sealed interface ServerChoice {
    data object Fastest : ServerChoice
    data class Specific(val serverId: String) : ServerChoice

    fun encode(): String = when (this) { Fastest -> FASTEST; is Specific -> "server:$serverId" }

    companion object {
        private const val FASTEST = "fastest"
        fun decode(value: String?): ServerChoice =
            value?.removePrefix("server:")?.takeIf { value.startsWith("server:") && it.isNotBlank() }?.let(::Specific) ?: Fastest
    }
}

/**
 * A short-lived WireGuard session the Shield service issued for one device key and one gateway. The
 * device's private key is never part of it: that key is generated on the device and kept in memory only.
 */
data class TunnelSpec(
    val sessionId: String,
    val serverId: String,
    /** Tunnel addresses assigned to this device, in CIDR form. */
    val addresses: List<String>,
    /** Resolvers reached through the tunnel, so DNS does not leave the tunnel. */
    val dnsServers: List<String>,
    val mtu: Int?,
    val peerPublicKey: String,
    val presharedKey: String?,
    val endpoint: String,
    /** Routes sent into the tunnel; always includes 0.0.0.0/0 (and ::/0 when IPv6 is assigned). */
    val allowedIps: List<String>,
    val keepaliveSeconds: Int,
    val expiresAtMillis: Long,
    /** Public addresses this gateway's traffic leaves from, used to verify the exit. */
    val exitIps: List<String>,
) {
    val carriesIpv6: Boolean get() = addresses.any { ':' in it }
}

/** What the Shield service saw when this device asked, through the tunnel, where the request came from. */
data class ExitReport(val ip: String, val viaServerId: String?)

/** Device WireGuard keys for one session. The private key is kept out of [toString] and any log. */
class DeviceKeys(val publicKey: String, val privateKey: String) {
    override fun toString() = "DeviceKeys(publicKey=$publicKey)"
}

/** Why the Shield service or the tunnel could not do what was asked. */
enum class ShieldProblem {
    /** This build has no Shield service address, so there is no server to connect to. */
    NotConfigured,
    PermissionDenied,
    Unauthorized,
    ServiceUnreachable,
    InvalidResponse,
    NoReachableServer,
    ServerUnavailable,
    TunnelFailed,
    HandshakeTimeout,
    /** The service saw this device's traffic arrive from somewhere other than the chosen gateway. */
    ExitMismatch,
    ReconnectFailed,
}

class ShieldException(val problem: ShieldProblem, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: problem.name, cause)

enum class ConnectStep { LoadingServers, MeasuringServers, RequestingSession, StartingTunnel, Handshaking, VerifyingExit }

enum class ReconnectCause { NetworkLost, HandshakeStale, RenewingSession }

enum class DisconnectReason { None, UserRequested, Revoked, ServiceStopped }

sealed interface ExitStatus {
    /** The Shield service confirmed the request arrived from this gateway's exit address. */
    data class Verified(val ip: String) : ExitStatus
    /** The tunnel is up (handshake completed) but the exit could not be checked; shown as unverified. */
    data class Unverified(val why: String) : ExitStatus
}

/** The real state of Mylo Shield. Every state is derived from what the tunnel and the service report. */
sealed interface ShieldState {
    data class Disconnected(val reason: DisconnectReason = DisconnectReason.None) : ShieldState
    data class Connecting(val server: ShieldServer?, val step: ConnectStep) : ShieldState
    data class Connected(val server: ShieldServer, val sinceMillis: Long, val exit: ExitStatus, val ipv6: Boolean) : ShieldState
    data class Reconnecting(val server: ShieldServer, val sinceMillis: Long, val cause: ReconnectCause) : ShieldState
    data class Error(val problem: ShieldProblem, val server: ShieldServer?) : ShieldState
}

/** The tunnel is (or is being) carried by the VPN interface, so the service must stay in the foreground. */
val ShieldState.tunnelActive: Boolean
    get() = this is ShieldState.Connected || this is ShieldState.Reconnecting ||
        (this is ShieldState.Connecting && step >= ConnectStep.Handshaking)

/** The list of gateways the Shield service offers, with this device's measurements. */
sealed interface ServerDirectory {
    data object NotConfigured : ServerDirectory
    data object Loading : ServerDirectory
    data class Loaded(val servers: List<ShieldServer>, val measurements: Map<String, Measurement>, val fetchedAtMillis: Long) : ServerDirectory
    data class Failed(val problem: ShieldProblem) : ServerDirectory
}

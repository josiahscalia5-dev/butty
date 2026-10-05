package com.mylo.browser.shield

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The Mylo Shield service (the backend that lists gateways and issues short-lived sessions). */
interface ShieldService {
    val configured: Boolean
    suspend fun servers(): List<ShieldServer>
    suspend fun openSession(server: ShieldServer, devicePublicKey: String): TunnelSpec
    suspend fun closeSession(sessionId: String)
    /** Asked through the tunnel: which address did this request arrive from? */
    suspend fun exitCheck(): ExitReport
}

/** The WireGuard tunnel on this device. */
interface TunnelDriver {
    /** Brings the tunnel up with [spec], replacing any running tunnel. Throws when Android or WireGuard refuses. */
    suspend fun up(spec: TunnelSpec, keys: DeviceKeys)
    suspend fun down()
    /** Wall-clock millis of the latest completed handshake with the gateway, or null when there has been none. */
    fun latestHandshakeMillis(): Long?
}

interface LatencyMeter {
    suspend fun measure(servers: List<ShieldServer>): Map<String, Measurement>
}

fun interface KeyMaker {
    fun newKeys(): DeviceKeys
}

/** Picks the gateway with the lowest latency measured on this device among available, reachable ones. */
object FastestServer {
    /** Gateways reporting at least this load are only used when nothing else answered. */
    const val SATURATED_LOAD = 90

    fun pick(servers: List<ShieldServer>, measurements: Map<String, Measurement>): ShieldServer? {
        val reachable = servers.filter { it.available }.mapNotNull { server ->
            measurements[server.id]?.takeIf { it.reachable }?.latencyMs?.let { server to it }
        }
        val pool = reachable.filter { (server, _) -> (server.load ?: 0) < SATURATED_LOAD }.ifEmpty { reachable }
        return pool.minWithOrNull(compareBy<Pair<ShieldServer, Int>> { it.second }.thenBy { it.first.load ?: 0 }.thenBy { it.first.id })?.first
    }
}

/**
 * Drives Mylo Shield from the user's choice to a verified tunnel and keeps it honest afterwards.
 *
 * Connected is reached only after the gateway completed a WireGuard handshake on this tunnel, and the exit
 * is reported as verified only when the Shield service saw the request arrive from that gateway. A
 * supervisor then watches the network and handshakes (Reconnecting), renews the short-lived session before
 * it expires, and gives up with an error instead of claiming a connection it cannot show.
 */
class ShieldEngine(
    private val service: ShieldService,
    private val driver: TunnelDriver,
    private val meter: LatencyMeter,
    private val keys: KeyMaker,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val sessionTimeoutMs: Long = 15_000,
        val handshakeTimeoutMs: Long = 20_000,
        val pollMs: Long = 500,
        val exitTimeoutMs: Long = 10_000,
        val superviseEveryMs: Long = 5_000,
        /** WireGuard rejects a session 180 s after its handshake; a little margin on top. */
        val staleHandshakeMs: Long = 195_000,
        val renewBeforeExpiryMs: Long = 60_000,
        val reconnectGiveUpMs: Long = 90_000,
        val directoryFreshMs: Long = 5 * 60_000,
    )

    private val _state = MutableStateFlow<ShieldState>(
        if (service.configured) ShieldState.Disconnected() else ShieldState.Error(ShieldProblem.NotConfigured, null),
    )
    val state: StateFlow<ShieldState> = _state.asStateFlow()

    private val _directory = MutableStateFlow<ServerDirectory>(
        if (service.configured) ServerDirectory.Loading else ServerDirectory.NotConfigured,
    )
    val directory: StateFlow<ServerDirectory> = _directory.asStateFlow()

    private val lock = Any()
    private var work: Job? = null
    private var session: TunnelSpec? = null
    @Volatile private var networkAvailable = true
    @Volatile private var networkReturnedAt = 0L
    /** Set while the user's disconnect runs, so the service stopping underneath it is not reported separately. */
    @Volatile private var disconnecting = false

    /** Lists the service's gateways and, when [measure] is set, measures latency to each from this device. */
    fun refreshServers(measure: Boolean = true): Job = scope.launch {
        if (!service.configured) { _directory.value = ServerDirectory.NotConfigured; return@launch }
        runCatching { loadDirectory(force = true, measure = measure) }
            .onFailure { if (it is CancellationException) throw it }
    }

    /** Connects to [choice]; replaces whatever connection attempt or tunnel is current. */
    fun connect(choice: ServerChoice): Job = exclusive("connect") {
        disconnecting = false
        establish(choice)
    }

    fun disconnect(reason: DisconnectReason = DisconnectReason.UserRequested): Job {
        disconnecting = true
        return exclusive("disconnect") {
            tearDown()
            set(ShieldState.Disconnected(reason))
        }
    }

    /** Android revoked the VPN (another VPN started, or the user turned Mylo off in Settings). */
    fun onRevoked(): Job = disconnect(DisconnectReason.Revoked)

    /** The VPN service is gone; WireGuard was already stopped with it. */
    fun onServiceStopped() {
        if (disconnecting) return
        val current = _state.value
        if (current is ShieldState.Disconnected || current is ShieldState.Error) return
        synchronized(lock) { work?.cancel(); work = null }
        session = null
        set(ShieldState.Disconnected(DisconnectReason.ServiceStopped))
    }

    /** The service address changed (debug test gateway); applies at once unless a tunnel is in use. */
    fun configurationChanged() {
        val current = _state.value
        if (current is ShieldState.Connecting || current.tunnelActive) return
        _state.value = if (service.configured) ShieldState.Disconnected() else ShieldState.Error(ShieldProblem.NotConfigured, null)
        _directory.value = if (service.configured) ServerDirectory.Loading else ServerDirectory.NotConfigured
    }

    /** Android's permission dialog was dismissed or refused. */
    fun onPermissionDenied() = set(ShieldState.Error(ShieldProblem.PermissionDenied, null))

    /** Whether any non-VPN network with internet access is available underneath the tunnel. */
    fun onUnderlyingNetwork(available: Boolean) {
        if (available && !networkAvailable) networkReturnedAt = clock()
        networkAvailable = available
        val current = _state.value
        if (!available && current is ShieldState.Connected) {
            set(ShieldState.Reconnecting(current.server, current.sinceMillis, ReconnectCause.NetworkLost))
        }
    }

    // --- connection -------------------------------------------------------------------------------

    private fun exclusive(name: String, block: suspend () -> Unit): Job = synchronized(lock) {
        val previous = work
        scope.launch(start = CoroutineStart.LAZY) {
            previous?.cancel()
            previous?.join()
            log("Shield: $name")
            block()
        }.also { work = it; it.start() }
    }

    private suspend fun establish(choice: ServerChoice) {
        if (!service.configured) return set(ShieldState.Error(ShieldProblem.NotConfigured, null))
        var server: ShieldServer? = null
        try {
            set(ShieldState.Connecting(null, ConnectStep.LoadingServers))
            val directory = loadDirectory(force = false, measure = false)
            val chosen = when (choice) {
                ServerChoice.Fastest -> {
                    set(ShieldState.Connecting(null, ConnectStep.MeasuringServers))
                    val measured = measureInto(directory)
                    FastestServer.pick(measured.servers, measured.measurements)
                        ?: throw ShieldException(ShieldProblem.NoReachableServer)
                }
                is ServerChoice.Specific -> directory.servers.firstOrNull { it.id == choice.serverId && it.available }
                    ?: throw ShieldException(ShieldProblem.ServerUnavailable)
            }
            server = chosen
            val spec = bringUp(chosen, ConnectStepReporter { set(ShieldState.Connecting(chosen, it)) })
            set(ShieldState.Connecting(chosen, ConnectStep.VerifyingExit))
            val exit = verifyExit(chosen, spec)
            set(ShieldState.Connected(chosen, clock(), exit, spec.carriesIpv6))
            supervise(chosen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val problem = (e as? ShieldException)?.problem ?: ShieldProblem.TunnelFailed
            log("Shield: connect failed (${problem.name})")
            tearDown()
            set(ShieldState.Error(problem, server))
        }
    }

    private fun interface ConnectStepReporter { fun report(step: ConnectStep) }

    /** Requests a fresh session with new device keys, starts WireGuard, and waits for a real handshake. */
    private suspend fun bringUp(server: ShieldServer, steps: ConnectStepReporter): TunnelSpec {
        steps.report(ConnectStep.RequestingSession)
        val deviceKeys = keys.newKeys()
        val spec = timed(timing.sessionTimeoutMs, ShieldProblem.ServiceUnreachable) { service.openSession(server, deviceKeys.publicKey) }
        steps.report(ConnectStep.StartingTunnel)
        val previous = session
        try {
            driver.up(spec, deviceKeys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            closeQuietly(spec)
            throw ShieldException(ShieldProblem.TunnelFailed, cause = e)
        }
        session = spec
        previous?.takeIf { it.sessionId != spec.sessionId }?.let(::closeQuietly)
        steps.report(ConnectStep.Handshaking)
        if (!awaitHandshake()) throw ShieldException(ShieldProblem.HandshakeTimeout)
        return spec
    }

    private suspend fun awaitHandshake(): Boolean {
        val deadline = clock() + timing.handshakeTimeoutMs
        while (clock() < deadline) {
            if ((driver.latestHandshakeMillis() ?: 0L) > 0L) return true
            delay(timing.pollMs)
        }
        return false
    }

    private suspend fun verifyExit(server: ShieldServer, spec: TunnelSpec): ExitStatus {
        val report = try {
            withTimeout(timing.exitTimeoutMs) { service.exitCheck() }
        } catch (e: TimeoutCancellationException) {
            return ExitStatus.Unverified("The Shield service did not answer the exit check")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExitStatus.Unverified("The exit check could not reach the Shield service")
        }
        val fromThisGateway = report.viaServerId == server.id || report.ip in spec.exitIps
        val fromElsewhere = (report.viaServerId != null && report.viaServerId != server.id) ||
            (report.viaServerId == null && spec.exitIps.isNotEmpty() && report.ip !in spec.exitIps)
        return when {
            fromThisGateway -> ExitStatus.Verified(report.ip)
            fromElsewhere -> throw ShieldException(ShieldProblem.ExitMismatch)
            else -> ExitStatus.Unverified("The Shield service did not identify this gateway")
        }
    }

    /** Keeps watching a connected tunnel until it is replaced or torn down. */
    private suspend fun supervise(server: ShieldServer) {
        var troubleSince = 0L
        while (currentCoroutineContext().isActive) {
            delay(timing.superviseEveryMs)
            val now = clock()
            val spec = session ?: return
            val current = _state.value
            val since = (current as? ShieldState.Connected)?.sinceMillis ?: (current as? ShieldState.Reconnecting)?.sinceMillis ?: now

            if (spec.expiresAtMillis - now <= timing.renewBeforeExpiryMs) {
                set(ShieldState.Reconnecting(server, since, ReconnectCause.RenewingSession))
                val renewed = try {
                    bringUp(server) { }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Shield: renewal failed")
                    return fail(ShieldProblem.ReconnectFailed, server)
                }
                set(ShieldState.Connected(server, since, verifyExitOrKeep(server, renewed, current), renewed.carriesIpv6))
                troubleSince = 0L
                continue
            }

            val handshake = driver.latestHandshakeMillis() ?: 0L
            val handshakeFresh = handshake > 0L && now - handshake <= timing.staleHandshakeMs
            val healthy = when {
                !networkAvailable -> false
                current is ShieldState.Reconnecting -> recovered(server, spec, handshake)
                else -> handshakeFresh
            }
            if (healthy) {
                troubleSince = 0L
                if (current is ShieldState.Reconnecting) {
                    set(ShieldState.Connected(server, since, verifyExitOrKeep(server, spec, current), spec.carriesIpv6))
                }
                continue
            }
            if (troubleSince == 0L) troubleSince = now
            val cause = if (!networkAvailable) ReconnectCause.NetworkLost else ReconnectCause.HandshakeStale
            if (current !is ShieldState.Reconnecting || current.cause != cause) set(ShieldState.Reconnecting(server, since, cause))
            if (networkAvailable && now - troubleSince >= timing.reconnectGiveUpMs) {
                // The old session is not coming back: start over with a new session on the same gateway.
                set(ShieldState.Reconnecting(server, since, ReconnectCause.RenewingSession))
                val renewed = try {
                    bringUp(server) { }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return fail(ShieldProblem.ReconnectFailed, server)
                }
                set(ShieldState.Connected(server, since, verifyExitOrKeep(server, renewed, current), renewed.carriesIpv6))
                troubleSince = 0L
            }
        }
    }

    /** After the network returns, a new handshake or a successful exit check proves the tunnel carries traffic. */
    private suspend fun recovered(server: ShieldServer, spec: TunnelSpec, handshake: Long): Boolean {
        if (networkReturnedAt > 0L && handshake > networkReturnedAt) return true
        return try {
            verifyExit(server, spec) is ExitStatus.Verified
        } catch (e: ShieldException) {
            if (e.problem == ShieldProblem.ExitMismatch) throw e
            false
        }
    }

    private suspend fun verifyExitOrKeep(server: ShieldServer, spec: TunnelSpec, previous: ShieldState): ExitStatus =
        runCatching { verifyExit(server, spec) }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (error is ShieldException && error.problem == ShieldProblem.ExitMismatch) throw error
            (previous as? ShieldState.Connected)?.exit ?: ExitStatus.Unverified("Exit not checked yet")
        }

    private suspend fun fail(problem: ShieldProblem, server: ShieldServer?) {
        tearDown()
        set(ShieldState.Error(problem, server))
    }

    private suspend fun tearDown() {
        runCatching { driver.down() }.onFailure { if (it is CancellationException) throw it }
        session?.let(::closeQuietly)
        session = null
    }

    private fun closeQuietly(spec: TunnelSpec) {
        scope.launch { runCatching { service.closeSession(spec.sessionId) } }
    }

    // --- directory --------------------------------------------------------------------------------

    private suspend fun loadDirectory(force: Boolean, measure: Boolean): ServerDirectory.Loaded {
        val current = _directory.value
        val fresh = current is ServerDirectory.Loaded && clock() - current.fetchedAtMillis < timing.directoryFreshMs
        val loaded = if (!force && fresh) current as ServerDirectory.Loaded else {
            if (current !is ServerDirectory.Loaded) _directory.value = ServerDirectory.Loading
            val servers = try {
                timed(timing.sessionTimeoutMs, ShieldProblem.ServiceUnreachable) { service.servers() }
            } catch (e: ShieldException) {
                if (current !is ServerDirectory.Loaded) _directory.value = ServerDirectory.Failed(e.problem)
                throw e
            }
            val kept = (current as? ServerDirectory.Loaded)?.measurements.orEmpty().filterKeys { id -> servers.any { it.id == id } }
            ServerDirectory.Loaded(servers, kept, clock()).also { _directory.value = it }
        }
        return if (measure) measureInto(loaded) else loaded
    }

    private suspend fun measureInto(directory: ServerDirectory.Loaded): ServerDirectory.Loaded {
        val measured = meter.measure(directory.servers)
        val latest = (_directory.value as? ServerDirectory.Loaded) ?: directory
        return latest.copy(measurements = latest.measurements + measured).also { _directory.value = it }
    }

    private suspend fun <T> timed(millis: Long, onTimeout: ShieldProblem, block: suspend () -> T): T = try {
        withTimeout(millis) { block() }
    } catch (e: TimeoutCancellationException) {
        throw ShieldException(onTimeout, "Timed out")
    }

    private fun set(next: ShieldState) {
        val previous = _state.value
        _state.value = next
        if (previous::class != next::class || (next is ShieldState.Connecting && previous is ShieldState.Connecting && previous.step != next.step)) {
            log("Shield: ${describe(next)}")
        }
    }

    /** State names and server IDs only: no addresses, keys, DNS or browsing data ever reach the log. */
    private fun describe(state: ShieldState): String = when (state) {
        is ShieldState.Disconnected -> "disconnected (${state.reason.name})"
        is ShieldState.Connecting -> "connecting ${state.step.name}${state.server?.let { " to ${it.id}" }.orEmpty()}"
        is ShieldState.Connected -> "connected to ${state.server.id}, exit ${if (state.exit is ExitStatus.Verified) "verified" else "unverified"}"
        is ShieldState.Reconnecting -> "reconnecting ${state.cause.name}"
        is ShieldState.Error -> "error ${state.problem.name}"
    }
}

package com.mylo.browser.shield

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine against fake tunnels and services; fixtures use fictional places and documentation addresses. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldEngineTest {
    private fun key(seed: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { (seed * 7 + it).toByte() })

    private fun server(id: String, endpoint: String) = ShieldServer(
        id, "XA", "Example Country", "Test City $id", "$id.shield.example", endpoint, key(id.hashCode()),
        VpnProtocol.WIREGUARD, supportsIpv6 = false, probe = ProbeTarget(endpoint.substringBefore(':'), 443), load = 10, available = true,
    )

    private val near = server("near", "203.0.113.10:51820")
    private val far = server("far", "203.0.113.20:51820")

    private inner class FakeService(val now: () -> Long) : ShieldService {
        override var configured = true
        var servers = listOf(near, far)
        var serversError: ShieldException? = null
        var exit: () -> ExitReport = { error("set per test") }
        val opened = mutableListOf<Pair<String, String>>()
        val closed = mutableListOf<String>()
        var sessionLifetimeMs = 60 * 60_000L
        override suspend fun servers() = serversError?.let { throw it } ?: servers
        override suspend fun openSession(server: ShieldServer, devicePublicKey: String): TunnelSpec {
            opened += server.id to devicePublicKey
            return TunnelSpec("s${opened.size}", server.id, listOf("10.64.0.${opened.size + 1}/32"), listOf("10.64.0.1"), null,
                server.publicKey, null, server.endpoint, listOf("0.0.0.0/0"), 25, now() + sessionLifetimeMs, listOf(server.endpoint.substringBefore(':')))
        }
        override suspend fun closeSession(sessionId: String) { closed += sessionId }
        override suspend fun exitCheck(): ExitReport = exit()
    }

    /** WireGuard stand-in: the first handshake lands [handshakeDelayMs] after up, then it rekeys every 2 minutes while healthy. */
    private inner class FakeDriver(val now: () -> Long) : TunnelDriver {
        val ups = mutableListOf<TunnelSpec>()
        var downs = 0
        var upError: Exception? = null
        var handshakeDelayMs: Long? = 300
        var healthyUntil = Long.MAX_VALUE
        private var upAt = 0L
        override suspend fun up(spec: TunnelSpec, keys: DeviceKeys) {
            upError?.let { throw it }
            ups += spec; upAt = now(); healthyUntil = Long.MAX_VALUE
        }
        override suspend fun down() { downs++ }
        override fun latestHandshakeMillis(): Long? {
            val first = upAt + (handshakeDelayMs ?: return null)
            val until = minOf(now(), healthyUntil)
            if (until < first) return null
            return first + (until - first) / 120_000 * 120_000
        }
    }

    private inner class Rig(scope: TestScope) {
        val now = { scope.testScheduler.currentTime }
        val service = FakeService(now)
        val driver = FakeDriver(now)
        var latencies = mapOf("near" to 20, "far" to 90)
        private var keyCount = 0
        val engine = ShieldEngine(
            service, driver,
            meter = object : LatencyMeter {
                override suspend fun measure(servers: List<ShieldServer>) =
                    servers.associate { it.id to Measurement(latencies[it.id], latencies[it.id] != null, now()) }
            },
            keys = { keyCount++; DeviceKeys("device-public-$keyCount", "device-private-$keyCount") },
            clock = now, scope = scope.backgroundScope,
        )
        val state get() = engine.state.value
    }

    private fun TestScope.settle(millis: Long = 0) { advanceTimeBy(millis); runCurrent() }

    @Test fun withoutAServiceNothingConnects() = runTest {
        val rig = Rig(this)
        rig.service.configured = false
        val engine = ShieldEngine(rig.service, rig.driver, object : LatencyMeter { override suspend fun measure(servers: List<ShieldServer>) = emptyMap<String, Measurement>() },
            { DeviceKeys("p", "k") }, rig.now, backgroundScope)
        assertEquals(ShieldState.Error(ShieldProblem.NotConfigured, null), engine.state.value)
        assertEquals(ServerDirectory.NotConfigured, engine.directory.value)
        engine.connect(ServerChoice.Fastest); settle(60_000)
        assertEquals(ShieldState.Error(ShieldProblem.NotConfigured, null), engine.state.value)
        assertTrue("No tunnel may start without a configured service", rig.driver.ups.isEmpty())
    }

    @Test fun connectedOnlyAfterHandshakeAndVerifiedExit() = runTest {
        val rig = Rig(this)
        rig.service.exit = { ExitReport("203.0.113.20", "far") }
        rig.engine.connect(ServerChoice.Specific("far")); settle()
        assertEquals(ShieldState.Connecting(far, ConnectStep.Handshaking), rig.state)
        assertEquals(listOf("far" to "device-public-1"), rig.service.opened)
        assertEquals("far", rig.driver.ups.single().serverId)
        settle(500)
        val connected = rig.state as ShieldState.Connected
        assertEquals(far, connected.server)
        assertEquals(ExitStatus.Verified("203.0.113.20"), connected.exit)
        assertFalse(connected.ipv6)
        rig.engine.disconnect(); settle()
        assertEquals(ShieldState.Disconnected(DisconnectReason.UserRequested), rig.state)
        assertEquals(1, rig.driver.downs)
        assertEquals(listOf("s1"), rig.service.closed)
    }

    @Test fun fastestConnectsToTheLowestMeasuredLatency() = runTest {
        val rig = Rig(this)
        rig.service.exit = { ExitReport("10.0.0.1", "near") }
        rig.engine.connect(ServerChoice.Fastest); settle(1_000)
        assertEquals(near, (rig.state as ShieldState.Connected).server)
        val directory = rig.engine.directory.value as ServerDirectory.Loaded
        assertEquals(20, directory.measurements.getValue("near").latencyMs)
    }

    @Test fun noReachableGatewayIsAnErrorNotAConnection() = runTest {
        val rig = Rig(this)
        rig.latencies = emptyMap()
        rig.engine.connect(ServerChoice.Fastest); settle(1_000)
        assertEquals(ShieldState.Error(ShieldProblem.NoReachableServer, null), rig.state)
        assertTrue(rig.driver.ups.isEmpty())
    }

    @Test fun missingHandshakeTearsTheTunnelDown() = runTest {
        val rig = Rig(this)
        rig.driver.handshakeDelayMs = null
        rig.engine.connect(ServerChoice.Specific("near")); settle(19_000)
        assertEquals(ShieldState.Connecting(near, ConnectStep.Handshaking), rig.state)
        settle(2_000)
        assertEquals(ShieldState.Error(ShieldProblem.HandshakeTimeout, near), rig.state)
        assertEquals(1, rig.driver.downs)
        assertEquals(listOf("s1"), rig.service.closed)
    }

    @Test fun exitFromAnotherPlaceIsAnError() = runTest {
        val rig = Rig(this)
        rig.service.exit = { ExitReport("198.51.100.7", null) }
        rig.engine.connect(ServerChoice.Specific("near")); settle(1_000)
        assertEquals(ShieldState.Error(ShieldProblem.ExitMismatch, near), rig.state)
        assertEquals(1, rig.driver.downs)
    }

    @Test fun unreachableExitCheckIsShownAsUnverified() = runTest {
        val rig = Rig(this)
        rig.service.exit = { throw IOException("offline") }
        rig.engine.connect(ServerChoice.Specific("near")); settle(1_000)
        val connected = rig.state as ShieldState.Connected
        assertTrue(connected.exit is ExitStatus.Unverified)
    }

    @Test fun refusedTunnelAndRefusedCredentialsAreErrors() = runTest {
        val rig = Rig(this)
        rig.driver.upError = IllegalStateException("VPN_NOT_AUTHORIZED")
        rig.engine.connect(ServerChoice.Specific("near")); settle(1_000)
        assertEquals(ShieldState.Error(ShieldProblem.TunnelFailed, near), rig.state)
        assertEquals("The unused session is released", listOf("s1"), rig.service.closed)

        val other = Rig(this)
        other.service.serversError = ShieldException(ShieldProblem.Unauthorized)
        other.engine.connect(ServerChoice.Fastest); settle(1_000)
        assertEquals(ShieldState.Error(ShieldProblem.Unauthorized, null), other.state)
        assertEquals(ServerDirectory.Failed(ShieldProblem.Unauthorized), other.engine.directory.value)
    }

    @Test fun losingTheNetworkReconnectsAndRecovers() = runTest {
        val rig = Rig(this)
        rig.service.exit = { ExitReport("203.0.113.10", "near") }
        rig.engine.connect(ServerChoice.Specific("near")); settle(1_000)
        val since = (rig.state as ShieldState.Connected).sinceMillis
        rig.engine.onUnderlyingNetwork(false)
        assertEquals(ShieldState.Reconnecting(near, since, ReconnectCause.NetworkLost), rig.state)
        settle(30_000)
        assertTrue(rig.state is ShieldState.Reconnecting)
        rig.engine.onUnderlyingNetwork(true); settle(5_000)
        val back = rig.state as ShieldState.Connected
        assertEquals("Connected time keeps counting from the original connection", since, back.sinceMillis)
        assertEquals("No new session was needed", 1, rig.service.opened.size)
    }

    @Test fun staleHandshakeReconnectsWithANewSession() = runTest {
        val rig = Rig(this)
        rig.service.exit = { ExitReport("203.0.113.10", "near") }
        rig.engine.connect(ServerChoice.Specific("near")); settle(1_000)
        // The gateway stops answering: no more handshakes and no exit check through the tunnel.
        rig.driver.healthyUntil = rig.now()
        rig.service.exit = { throw IOException("no route") }
        settle(200_000)
        assertEquals(ReconnectCause.HandshakeStale, (rig.state as ShieldState.Reconnecting).cause)
        assertEquals("Still the first session while it may recover", 1, rig.service.opened.size)
        settle(100_000)
        assertTrue(rig.state is ShieldState.Connected)
        assertEquals("A fresh session with fresh keys replaced the dead one", 2, rig.service.opened.size)
        assertEquals("device-public-2", rig.service.opened[1].second)
        assertTrue("s1" in rig.service.closed)
    }

    @Test fun sessionIsRenewedBeforeItExpires() = runTest {
        val rig = Rig(this)
        rig.service.sessionLifetimeMs = 10 * 60_000L
        rig.service.exit = { ExitReport("203.0.113.10", "near") }
        rig.engine.connect(ServerChoice.Specific("near")); settle(1_000)
        val since = (rig.state as ShieldState.Connected).sinceMillis
        settle(9 * 60_000L + 10_000)
        assertEquals(2, rig.service.opened.size)
        assertEquals(listOf("s1"), rig.service.closed)
        assertEquals(since, (rig.state as ShieldState.Connected).sinceMillis)
    }

    @Test fun switchingServersReplacesTheTunnel() = runTest {
        val rig = Rig(this)
        rig.service.exit = { ExitReport("x", rig.driver.ups.last().serverId) }
        rig.engine.connect(ServerChoice.Specific("near")); settle(1_000)
        rig.engine.connect(ServerChoice.Specific("far")); settle(1_000)
        assertEquals(far, (rig.state as ShieldState.Connected).server)
        assertEquals(listOf("near", "far"), rig.driver.ups.map { it.serverId })
        assertEquals("The previous session is released after the switch", listOf("s1"), rig.service.closed)
    }

    @Test fun revokedByAndroidDisconnects() = runTest {
        val rig = Rig(this)
        rig.service.exit = { ExitReport("203.0.113.10", "near") }
        rig.engine.connect(ServerChoice.Specific("near")); settle(1_000)
        rig.engine.onRevoked(); settle()
        assertEquals(ShieldState.Disconnected(DisconnectReason.Revoked), rig.state)
        assertEquals(1, rig.driver.downs)
    }

    @Test fun serviceStoppingUnderAConnectionIsReported() = runTest {
        val rig = Rig(this)
        rig.service.exit = { ExitReport("203.0.113.10", "near") }
        rig.engine.connect(ServerChoice.Specific("near")); settle(1_000)
        rig.engine.onServiceStopped()
        assertEquals(ShieldState.Disconnected(DisconnectReason.ServiceStopped), rig.state)
    }
}

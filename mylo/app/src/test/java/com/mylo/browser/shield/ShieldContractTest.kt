package com.mylo.browser.shield

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures use fictional places and documentation-only addresses (RFC 5737 / RFC 3849). */
class ShieldContractTest {
    private val gatewayKey = key(1)
    private val otherKey = key(2)

    private fun key(seed: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { (seed * 31 + it).toByte() })

    private val serversJson = """
        {"servers": [
          {"id": "xa-1", "countryCode": "XA", "country": "Example Country", "city": "Test City A",
           "hostname": "xa-1.shield.example", "endpoint": "203.0.113.10:51820", "publicKey": "$gatewayKey",
           "protocol": "wireguard", "ipv6": true, "probe": {"host": "203.0.113.10", "port": 443}, "load": 37, "available": true},
          {"id": "xb-1", "countryCode": "XB", "country": "Sample Land", "city": "Test City B",
           "hostname": "xb-1.shield.example", "endpoint": "[2001:db8::10]:51820", "publicKey": "$otherKey",
           "protocol": "wireguard"},
          {"id": "bad-key", "countryCode": "XA", "country": "Example Country", "city": "Test City C",
           "hostname": "c.shield.example", "endpoint": "203.0.113.11:51820", "publicKey": "not-a-key", "protocol": "wireguard"},
          {"id": "dns-endpoint", "countryCode": "XA", "country": "Example Country", "city": "Test City D",
           "hostname": "d.shield.example", "endpoint": "d.shield.example:51820", "publicKey": "$gatewayKey", "protocol": "wireguard"},
          {"id": "ovpn", "countryCode": "XA", "country": "Example Country", "city": "Test City E",
           "hostname": "e.shield.example", "endpoint": "203.0.113.12:1194", "publicKey": "$gatewayKey", "protocol": "openvpn"}
        ]}
    """.trimIndent()

    @Test fun serversAreParsedAndInvalidEntriesDropped() {
        val servers = ShieldContract.parseServers(serversJson)
        assertEquals(listOf("xa-1", "xb-1"), servers.map { it.id })
        val first = servers[0]
        assertEquals("Test City A, Example Country", first.label)
        assertEquals("203.0.113.10:51820", first.endpoint)
        assertEquals(ProbeTarget("203.0.113.10", 443), first.probe)
        assertEquals(37, first.load)
        assertTrue(first.supportsIpv6 && first.available)
        val second = servers[1]
        assertNull("Load is unknown when the service does not report it", second.load)
        assertFalse(second.supportsIpv6)
        assertTrue("Availability defaults to listed", second.available)
    }

    @Test fun malformedServerListIsAnInvalidResponse() {
        val error = assertThrows(ShieldException::class.java) { ShieldContract.parseServers("{\"nope\": []}") }
        assertEquals(ShieldProblem.InvalidResponse, error.problem)
    }

    private val server get() = ShieldContract.parseServers(serversJson).first()

    private fun session(
        serverId: String = "xa-1",
        endpoint: String = "203.0.113.10:51820",
        peerKey: String = gatewayKey,
        addresses: String = "\"10.64.3.7/32\", \"fd64::3:7/128\"",
        allowedIps: String = "\"0.0.0.0/0\", \"::/0\"",
        expiresAt: String = "\"2030-01-01T00:00:00Z\"",
        psk: String = ", \"presharedKey\": \"${key(9)}\"",
    ) = """
        {"sessionId": "sess-1", "serverId": "$serverId",
         "interface": {"addresses": [$addresses], "dns": ["10.64.0.1"], "mtu": 1280},
         "peer": {"publicKey": "$peerKey", "endpoint": "$endpoint", "allowedIps": [$allowedIps], "persistentKeepalive": 25 $psk},
         "expiresAt": $expiresAt,
         "exit": {"ipv4": ["203.0.113.10"], "ipv6": ["2001:db8::10"]}}
    """.trimIndent()

    private val now = 1_800_000_000_000L // 2027-01-15

    @Test fun validSessionBecomesTunnelSpec() {
        val spec = ShieldContract.parseSession(session(), server, now)
        assertEquals("sess-1", spec.sessionId)
        assertEquals(listOf("10.64.3.7/32", "fd64::3:7/128"), spec.addresses)
        assertEquals(listOf("10.64.0.1"), spec.dnsServers)
        assertEquals(listOf("0.0.0.0/0", "::/0"), spec.allowedIps)
        assertEquals(25, spec.keepaliveSeconds)
        assertEquals(1280, spec.mtu)
        assertTrue(spec.carriesIpv6)
        assertEquals(listOf("203.0.113.10", "2001:db8::10"), spec.exitIps)
        assertEquals(1_893_456_000_000L, spec.expiresAtMillis)
    }

    @Test fun expiryMayBeEpochSecondsAndPresharedKeyIsOptional() {
        val spec = ShieldContract.parseSession(session(expiresAt = "1893456000", psk = ""), server, now)
        assertEquals(1_893_456_000_000L, spec.expiresAtMillis)
        assertNull(spec.presharedKey)
    }

    @Test fun sessionsThatWouldWeakenOrRedirectTheTunnelAreRefused() {
        val refused = mapOf(
            "another server" to session(serverId = "xb-1"),
            "another endpoint" to session(endpoint = "203.0.113.99:51820"),
            "another gateway key" to session(peerKey = otherKey),
            "split IPv4 tunnel" to session(allowedIps = "\"10.0.0.0/8\", \"::/0\""),
            "IPv6 assigned but not routed" to session(allowedIps = "\"0.0.0.0/0\""),
            "already expired" to session(expiresAt = "\"2020-01-01T00:00:00Z\""),
            "host name endpoint" to session(endpoint = "xa-1.shield.example:51820"),
            "malformed address" to session(addresses = "\"10.64.3.300/32\""),
        )
        refused.forEach { (why, body) ->
            val error = assertThrows(why, ShieldException::class.java) { ShieldContract.parseSession(body, server, now) }
            assertEquals(why, ShieldProblem.InvalidResponse, error.problem)
        }
    }

    @Test fun ipv4OnlySessionNeedsNoIpv6Route() {
        val spec = ShieldContract.parseSession(session(addresses = "\"10.64.3.7/32\"", allowedIps = "\"0.0.0.0/0\""), server, now)
        assertFalse(spec.carriesIpv6)
    }

    @Test fun exitReportAndRequestBody() {
        assertEquals(ExitReport("203.0.113.10", "xa-1"), ShieldContract.parseExitReport("{\"ip\": \"203.0.113.10\", \"viaServerId\": \"xa-1\"}"))
        assertEquals(ExitReport("198.51.100.4", null), ShieldContract.parseExitReport("{\"ip\": \"198.51.100.4\"}"))
        val request = org.json.JSONObject(ShieldContract.sessionRequest("xa-1", gatewayKey))
        assertEquals("xa-1", request.getString("serverId"))
        assertEquals(gatewayKey, request.getString("publicKey"))
        assertEquals("Only the server and the device's public key are sent", 2, request.length())
    }

    @Test fun serviceAddressMustBeHttpsOrALocalDevelopmentService() {
        fun ok(url: String, debug: Boolean = false) = HttpShieldService.validatedBase(url, debug)
        assertEquals("https://shield.example/api", ok("https://shield.example/api/"))
        assertNull("Plain HTTP is refused", ok("http://shield.example"))
        assertNull("Plain HTTP to a real host is refused even in debug builds", ok("http://203.0.113.10:8080", debug = true))
        assertEquals("http://10.0.2.2:8080", ok("http://10.0.2.2:8080", debug = true))
        assertNull("…and refused in release builds", ok("http://10.0.2.2:8080"))
        assertNull("Credentials in the URL are refused", ok("https://user:pass@shield.example"))
        assertNull(ok("https://shield.example/?token=1"))
        assertNull(ok(""))
    }

    /** Responses captured from shield-gateway/mylo_shield_gateway.py (with a fake wg) parse unchanged. */
    @Test fun referenceGatewayResponsesAreAccepted() {
        fun fixture(name: String) = javaClass.getResource("/shield/$name")!!.readText()
        val servers = ShieldContract.parseServers(fixture("reference-gateway-servers.json"))
        val gateway = servers.single()
        assertEquals("xa-test-1", gateway.id)
        assertEquals("203.0.113.10:51820", gateway.endpoint)
        val sessionBody = fixture("reference-gateway-session.json")
        val expiresAt = org.json.JSONObject(sessionBody).getLong("expiresAt") * 1000
        val spec = ShieldContract.parseSession(sessionBody, gateway, nowMillis = expiresAt - 60_000)
        assertEquals(listOf("10.64.0.2/32"), spec.addresses)
        assertEquals(listOf("0.0.0.0/0"), spec.allowedIps)
        assertEquals(listOf("203.0.113.10"), spec.exitIps)
        assertEquals(ExitReport("203.0.113.10", "xa-test-1"), ShieldContract.parseExitReport(fixture("reference-gateway-connection-check.json")))
    }

    @Test fun serverChoiceRoundTrips() {
        assertEquals(ServerChoice.Fastest, ServerChoice.decode(ServerChoice.Fastest.encode()))
        assertEquals(ServerChoice.Specific("xa-1"), ServerChoice.decode(ServerChoice.Specific("xa-1").encode()))
        assertEquals(ServerChoice.Fastest, ServerChoice.decode(null))
        assertEquals(ServerChoice.Fastest, ServerChoice.decode("server:"))
    }

    @Test fun fastestPicksLowestMeasuredLatencyAmongReachableGateways() {
        fun s(id: String, load: Int? = 10, available: Boolean = true) = server.copy(id = id, load = load, available = available)
        fun m(latency: Int?, reachable: Boolean = true) = Measurement(latency, reachable, 0)
        val servers = listOf(s("slow"), s("fast"), s("down", available = false), s("unreachable"), s("busy", load = 95), s("unmeasured"))
        val measurements = mapOf("slow" to m(80), "fast" to m(20), "down" to m(5), "unreachable" to m(null, false), "busy" to m(1))
        assertEquals("fast", FastestServer.pick(servers, measurements)?.id)
        // A saturated gateway is used only when nothing else answers.
        assertEquals("busy", FastestServer.pick(listOf(s("busy", load = 95), s("unreachable")), measurements)?.id)
        assertNull(FastestServer.pick(listOf(s("unreachable"), s("unmeasured")), measurements))
    }
}

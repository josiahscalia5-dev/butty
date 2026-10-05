package com.mylo.browser.shield

import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses and validates the Mylo Shield service's JSON (see docs/shield/BACKEND_API.md). Anything that is
 * malformed or would weaken the tunnel is rejected instead of being guessed at: a server entry that fails
 * validation is left out of the list, and a session that fails validation is refused.
 */
object ShieldContract {
    /** Gateways from `GET /v1/servers`. Entries with an unsupported protocol or invalid fields are dropped. */
    fun parseServers(body: String): List<ShieldServer> {
        val array = json { JSONObject(body).getJSONArray("servers") }
        return (0 until array.length()).mapNotNull { index ->
            runCatching { parseServer(array.getJSONObject(index)) }.getOrNull()
        }.distinctBy { it.id }
    }

    private fun parseServer(item: JSONObject): ShieldServer? {
        if (item.optString("protocol") != VpnProtocol.WIREGUARD.wireName) return null
        val countryCode = item.getString("countryCode").trim()
        require(countryCode.matches(Regex("[A-Z]{2}"))) { "countryCode" }
        val probe = item.optJSONObject("probe")?.let { ProbeTarget(nonBlank(it.getString("host")), port(it.getInt("port"))) }
        val load = if (item.has("load") && !item.isNull("load")) item.getInt("load").also { require(it in 0..100) { "load" } } else null
        return ShieldServer(
            id = nonBlank(item.getString("id")).also { require(it.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "id" } },
            countryCode = countryCode,
            country = nonBlank(item.getString("country")),
            city = nonBlank(item.getString("city")),
            hostname = nonBlank(item.getString("hostname")),
            endpoint = numericEndpoint(item.getString("endpoint")),
            publicKey = wireGuardKey(item.getString("publicKey")),
            protocol = VpnProtocol.WIREGUARD,
            supportsIpv6 = item.optBoolean("ipv6", false),
            probe = probe,
            load = load,
            available = item.optBoolean("available", true),
        )
    }

    /**
     * A session from `POST /v1/sessions` for [server]. It must be for that server, use that server's endpoint
     * and key, route all IPv4 traffic (and all IPv6 traffic when an IPv6 address is assigned) into the tunnel,
     * name at least one DNS resolver, and expire in the future.
     */
    fun parseSession(body: String, server: ShieldServer, nowMillis: Long): TunnelSpec = invalidAsShieldException {
        val root = JSONObject(body)
        val iface = root.getJSONObject("interface")
        val peer = root.getJSONObject("peer")
        val spec = TunnelSpec(
            sessionId = nonBlank(root.getString("sessionId")),
            serverId = root.getString("serverId"),
            addresses = strings(iface.getJSONArray("addresses")).map(::cidr),
            dnsServers = strings(iface.getJSONArray("dns")).map(::ipAddress),
            mtu = if (iface.has("mtu")) iface.getInt("mtu").also { require(it in 1280..1500) { "mtu" } } else null,
            peerPublicKey = wireGuardKey(peer.getString("publicKey")),
            presharedKey = peer.optString("presharedKey").takeIf { it.isNotEmpty() }?.let(::wireGuardKey),
            endpoint = numericEndpoint(peer.getString("endpoint")),
            allowedIps = strings(peer.getJSONArray("allowedIps")).map(::cidr),
            keepaliveSeconds = peer.optInt("persistentKeepalive", 25).also { require(it in 1..120) { "persistentKeepalive" } },
            expiresAtMillis = expiry(root.get("expiresAt")),
            exitIps = root.optJSONObject("exit")?.let { exit ->
                listOf("ipv4", "ipv6").flatMap { family -> exit.optJSONArray(family)?.let(::strings).orEmpty() }.map(::ipAddress)
            }.orEmpty(),
        )
        require(spec.serverId == server.id) { "session is for another server" }
        require(spec.endpoint == server.endpoint) { "session endpoint differs from the listed gateway" }
        require(spec.peerPublicKey == server.publicKey) { "session key differs from the listed gateway" }
        require(spec.addresses.isNotEmpty() && spec.dnsServers.isNotEmpty()) { "addresses and dns are required" }
        require("0.0.0.0/0" in spec.allowedIps) { "all IPv4 traffic must use the tunnel" }
        require(!spec.carriesIpv6 || "::/0" in spec.allowedIps) { "all IPv6 traffic must use the tunnel" }
        require(spec.expiresAtMillis > nowMillis) { "session already expired" }
        spec
    }

    /** `GET /v1/connection-check`, made through the tunnel: the address the service saw the request from. */
    fun parseExitReport(body: String): ExitReport = invalidAsShieldException {
        val root = JSONObject(body)
        ExitReport(ipAddress(root.getString("ip")), root.optString("viaServerId").takeIf { it.isNotEmpty() })
    }

    fun sessionRequest(serverId: String, devicePublicKey: String): String =
        JSONObject().put("serverId", serverId).put("publicKey", devicePublicKey).toString()

    // --- validation helpers -------------------------------------------------------------------

    fun wireGuardKey(value: String): String {
        val decoded = runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull()
        require(decoded != null && decoded.size == 32) { "WireGuard keys are 32 bytes, base64" }
        return value.trim()
    }

    /** `a.b.c.d:port` or `[v6]:port`. Host names are refused so connecting never depends on a DNS answer. */
    fun numericEndpoint(value: String): String {
        val match = Regex("^(?:(\\d{1,3}(?:\\.\\d{1,3}){3})|\\[([0-9A-Fa-f:.]+)\\]):(\\d{1,5})$").matchEntire(value.trim())
        requireNotNull(match) { "endpoint must be a numeric ip:port" }
        val (v4, v6, portText) = match.destructured
        if (v4.isNotEmpty()) ipv4(v4) else ipv6(v6)
        port(portText.toInt())
        return value.trim()
    }

    fun cidr(value: String): String {
        val parts = value.trim().split('/')
        require(parts.size == 2) { "CIDR expected" }
        val prefix = parts[1].toIntOrNull()
        if (':' in parts[0]) { ipv6(parts[0]); require(prefix != null && prefix in 0..128) { "IPv6 prefix" } }
        else { ipv4(parts[0]); require(prefix != null && prefix in 0..32) { "IPv4 prefix" } }
        return value.trim()
    }

    fun ipAddress(value: String): String = value.trim().also { if (':' in it) ipv6(it) else ipv4(it) }

    private fun ipv4(value: String) {
        val octets = value.split('.')
        require(octets.size == 4 && octets.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() <= 255 }) { "IPv4 address" }
    }

    private fun ipv6(value: String) {
        require(value.isNotEmpty() && value.length <= 45 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } &&
            value.count { it == ':' } in 2..7 && value.split("::").size <= 2) { "IPv6 address" }
    }

    private fun port(value: Int): Int = value.also { require(it in 1..65535) { "port" } }

    private fun nonBlank(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 200) { "blank field" } }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun expiry(value: Any): Long = when (value) {
        is Number -> value.toLong() * 1000
        is String -> Instant.parse(value).toEpochMilli()
        else -> throw IllegalArgumentException("expiresAt")
    }

    private inline fun <T> json(block: () -> T): T = invalidAsShieldException(block)

    private inline fun <T> invalidAsShieldException(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw ShieldException(ShieldProblem.InvalidResponse, "Malformed Shield service response", e)
    } catch (e: IllegalArgumentException) {
        throw ShieldException(ShieldProblem.InvalidResponse, "Rejected Shield service response: ${e.message}", e)
    } catch (e: java.time.format.DateTimeParseException) {
        throw ShieldException(ShieldProblem.InvalidResponse, "Malformed expiry", e)
    }
}

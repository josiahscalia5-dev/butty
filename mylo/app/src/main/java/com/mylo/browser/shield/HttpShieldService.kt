package com.mylo.browser.shield

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the Mylo Shield service is and the credential to present; never part of the repository. */
data class ShieldEndpoint(val baseUrl: String, val accessToken: String?)

/**
 * The Mylo Shield service over HTTPS (contract: docs/shield/BACKEND_API.md). [endpoint] comes from the
 * build environment or, in debug builds, from a test gateway entered on the device. Plain HTTP is accepted
 * only in debug builds and only for a development service on this machine or the emulator's host (10.0.2.2).
 */
class HttpShieldService(
    private val endpoint: () -> ShieldEndpoint?,
    private val allowDevCleartext: Boolean,
    private val now: () -> Long,
) : ShieldService {
    private val base: String? get() = endpoint()?.let { validatedBase(it.baseUrl.trim(), allowDevCleartext) }

    override val configured: Boolean get() = base != null

    override suspend fun servers(): List<ShieldServer> =
        ShieldContract.parseServers(request("GET", "/v1/servers"))

    override suspend fun openSession(server: ShieldServer, devicePublicKey: String): TunnelSpec {
        val body = request("POST", "/v1/sessions", ShieldContract.sessionRequest(server.id, devicePublicKey), notFound = ShieldProblem.ServerUnavailable)
        return ShieldContract.parseSession(body, server, now())
    }

    override suspend fun closeSession(sessionId: String) {
        request("DELETE", "/v1/sessions/" + URLEncoder.encode(sessionId, "UTF-8"), notFound = ShieldProblem.InvalidResponse)
    }

    override suspend fun exitCheck(): ExitReport = ShieldContract.parseExitReport(request("GET", "/v1/connection-check"))

    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        notFound: ShieldProblem = ShieldProblem.ServiceUnreachable,
    ): String = withContext(Dispatchers.IO) {
        val root = base ?: throw ShieldException(ShieldProblem.NotConfigured)
        val connection = URL(root + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Mylo-Shield-Android/1")
            endpoint()?.accessToken?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            when (val code = connection.responseCode) {
                in 200..299 -> readCapped(connection.inputStream)
                401, 403 -> throw ShieldException(ShieldProblem.Unauthorized, "HTTP $code")
                404, 410 -> throw ShieldException(notFound, "HTTP $code")
                409, 429, 503 -> throw ShieldException(ShieldProblem.ServerUnavailable, "HTTP $code")
                else -> throw ShieldException(ShieldProblem.ServiceUnreachable, "HTTP $code")
            }
        } catch (e: IOException) {
            throw ShieldException(ShieldProblem.ServiceUnreachable, cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(stream: InputStream): String = stream.use {
        val bytes = it.readNBytesCompat(MAX_RESPONSE_BYTES + 1)
        if (bytes.size > MAX_RESPONSE_BYTES) throw ShieldException(ShieldProblem.InvalidResponse, "Response too large")
        String(bytes, Charsets.UTF_8)
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buffer.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private val DEV_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost")

        /** The normalized base URL, or null when it is empty or unsafe (which leaves Shield "not configured"). */
        fun validatedBase(value: String, allowDevCleartext: Boolean): String? {
            if (value.isEmpty()) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val secure = uri.scheme.equals("https", ignoreCase = true)
            val devCleartext = allowDevCleartext && uri.scheme.equals("http", ignoreCase = true) && host in DEV_HOSTS
            if (!secure && !devCleartext) return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            return value.trimEnd('/')
        }
    }
}

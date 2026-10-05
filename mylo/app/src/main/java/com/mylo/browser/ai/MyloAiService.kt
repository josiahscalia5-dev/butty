package com.mylo.browser.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the Mylo AI service is and the credential to present; never part of the repository. */
data class AiEndpoint(val baseUrl: String, val accessToken: String?)

/** Why Mylo AI couldn't answer, in words the user can act on. */
enum class AiProblem(val message: String) {
    NotConfigured("Mylo AI isn't connected in this build yet, so nothing was sent. The developer adds the Mylo AI service address (see docs/ai/BACKEND_API.md)."),
    Unauthorized("Mylo AI didn't accept this app's sign-in."),
    Unreachable("Mylo AI couldn't be reached. Check your connection and try again."),
    Busy("Mylo AI is busy right now. Please try again in a moment."),
    InvalidResponse("Mylo AI sent a reply this app couldn't read."),
}

class AiException(val problem: AiProblem, detail: String? = null, cause: Throwable? = null) : Exception(detail ?: problem.name, cause)

/** The Mylo AI service (contract: docs/ai/BACKEND_API.md). */
interface MyloAiService {
    val configured: Boolean
    /** Streams one answer; cancelling the collector stops it (and closes the connection) immediately. */
    fun chat(conversationId: String, turns: List<AiTurn>, context: AiContext, private: Boolean): Flow<AiEvent>
    /** A short-lived voice session for one realtime call. */
    suspend fun voiceSession(voice: String, private: Boolean): AiContract.VoiceSession
}

/**
 * The Mylo AI service over HTTPS. [endpoint] comes from the build environment or, in debug builds, from a
 * test service entered on the device. Plain HTTP is accepted only in debug builds and only for a
 * development service on this machine or the emulator's host (10.0.2.2), the same rule as Mylo Shield.
 */
class HttpMyloAiService(
    private val endpoint: () -> AiEndpoint?,
    private val allowDevCleartext: Boolean,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : MyloAiService {
    private val base: String? get() = endpoint()?.let { validatedBase(it.baseUrl.trim(), allowDevCleartext) }

    override val configured: Boolean get() = base != null

    override fun chat(conversationId: String, turns: List<AiTurn>, context: AiContext, private: Boolean): Flow<AiEvent> = callbackFlow {
        val root = base ?: throw AiException(AiProblem.NotConfigured)
        val body = AiContract.chatRequest(conversationId, turns, context, private)
        val connection = open(root + "/v1/chat", "text/event-stream", STREAM_READ_TIMEOUT)
        launch(Dispatchers.IO) {
            try {
                post(connection, body)
                val reader = AiContract.SseReader()
                var chars = 0
                var finished = false
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    while (isActive && !finished) {
                        val line = input.readLine() ?: break
                        chars += line.length
                        if (chars > MAX_STREAM_CHARS) throw AiException(AiProblem.InvalidResponse, "Answer too long")
                        val event = reader.line(line) ?: continue
                        send(event)
                        finished = event is AiEvent.Done || event is AiEvent.Failure
                    }
                }
                if (!finished && isActive) trySend(AiEvent.Failure("interrupted", "The answer was cut off. Please try again."))
                close()
            } catch (e: AiException) {
                close(e)
            } catch (e: IOException) {
                // Stop and Close disconnect on purpose; only a failure while still listening is a problem.
                if (isActive) close(AiException(AiProblem.Unreachable, cause = e)) else close()
            }
        }
        awaitClose { connection.disconnect() }
    }

    override suspend fun voiceSession(voice: String, private: Boolean): AiContract.VoiceSession = withContext(Dispatchers.IO) {
        val root = base ?: throw AiException(AiProblem.NotConfigured)
        val connection = open(root + "/v1/voice/sessions", "application/json", 15_000)
        try {
            post(connection, AiContract.voiceSessionRequest(voice, private))
            val text = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_JSON_BYTES) throw AiException(AiProblem.InvalidResponse, "Response too large")
                }
                buffer.toString("UTF-8")
            }
            runCatching { AiContract.parseVoiceSession(text, nowSeconds(), allowDevCleartext) }
                .getOrElse { throw AiException(AiProblem.InvalidResponse, it.message, it) }
        } catch (e: IOException) {
            throw AiException(AiProblem.Unreachable, cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, accept: String, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            this.readTimeout = readTimeout
            useCaches = false
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Accept", accept)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "Mylo-AI-Android/1")
            endpoint()?.accessToken?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    /** Sends [body] and maps the service's status codes to problems the user can act on. */
    private fun post(connection: HttpURLConnection, body: String) {
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        when (val code = connection.responseCode) {
            in 200..299 -> Unit
            401, 403 -> throw AiException(AiProblem.Unauthorized, "HTTP $code")
            429, 503 -> throw AiException(AiProblem.Busy, "HTTP $code")
            400, 413 -> throw AiException(AiProblem.InvalidResponse, "HTTP $code")
            else -> throw AiException(AiProblem.Unreachable, "HTTP $code")
        }
    }

    companion object {
        private const val STREAM_READ_TIMEOUT = 60_000
        private const val MAX_STREAM_CHARS = 512 * 1024
        private const val MAX_JSON_BYTES = 64 * 1024
        private val DEV_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost")

        /** The normalized base URL, or null when it is empty or unsafe (which leaves Mylo AI "not connected"). */
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

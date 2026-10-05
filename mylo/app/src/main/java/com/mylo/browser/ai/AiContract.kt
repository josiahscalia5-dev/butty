package com.mylo.browser.ai

import org.json.JSONArray
import org.json.JSONObject

/** One turn of a Mylo conversation. */
data class AiTurn(val role: Role, val text: String) {
    enum class Role { User, Assistant }
}

/** A page Mylo AI may read, already reduced to readable text (and redacted where the user chose). */
data class PageContext(val url: String, val title: String, val text: String, val selection: String? = null)

/** Exactly the browser data one request carries; built only from what the switchboard authorized. */
data class AiContext(
    val page: PageContext? = null,
    val tabs: List<PageContext> = emptyList(),
    val history: List<Pair<String, String>> = emptyList(),
    /** Approximate location, rounded on the device. */
    val location: Pair<Double, Double>? = null,
    val memory: List<String> = emptyList(),
) {
    /** For the Privacy Receipt: which sources this request actually used. */
    val used: Set<AiDataSource> get() = buildSet {
        if (page != null) add(AiDataSource.CurrentPage)
        if (page?.selection != null) add(AiDataSource.SelectedText)
        if (tabs.isNotEmpty()) add(AiDataSource.OtherTabs)
        if (history.isNotEmpty()) add(AiDataSource.History)
        if (location != null) add(AiDataSource.Location)
        if (memory.isNotEmpty()) add(AiDataSource.MyloMemory)
    }
}

/** A source the answer relied on, linked back to the page. */
data class Citation(val index: Int, val title: String, val url: String, val quote: String?)

/** What the Mylo AI service streams back. */
sealed interface AiEvent {
    data class Delta(val text: String) : AiEvent
    data class Cite(val citation: Citation) : AiEvent
    /** A browser action the model proposes; it still goes through [ActionGate] before anything happens. */
    data class Proposal(val type: String, val target: String, val query: String?) : AiEvent
    data object Done : AiEvent
    data class Failure(val code: String, val message: String) : AiEvent
}

/** After an answer: what was and wasn't shared, in the user's words. */
data class PrivacyReceipt(val used: Set<AiDataSource>, val redactions: Int) {
    fun lines(): List<String> = AiDataSource.entries.filter { it != AiDataSource.SelectedText || it in used }.map { source ->
        if (source in used) "${source.label} used" else "${source.label} not shared"
    } + if (redactions > 0) listOf("$redactions personal detail${if (redactions == 1) "" else "s"} hidden before sending") else emptyList()
}

/**
 * The Mylo AI service contract (docs/ai/BACKEND_API.md), as pure JSON and Server-Sent Events handling so it
 * is unit-tested off-device. The app never talks to an AI provider with a provider key: the Mylo AI service
 * holds those and mints short-lived voice sessions.
 */
object AiContract {
    /** Browser actions this app can carry out (each through Action Preview's rules). */
    val tools = listOf("scroll_to", "highlight", "find", "read_aloud", "go_back", "search", "open_link", "translate")

    fun chatRequest(conversationId: String, turns: List<AiTurn>, context: AiContext, private: Boolean): String = JSONObject().apply {
        put("conversationId", conversationId)
        put("private", private)
        put("messages", JSONArray(turns.takeLast(MAX_TURNS).map { JSONObject().put("role", if (it.role == AiTurn.Role.User) "user" else "assistant").put("text", it.text) }))
        put("context", JSONObject().apply {
            context.page?.let { put("page", page(it)) }
            if (context.tabs.isNotEmpty()) put("tabs", JSONArray(context.tabs.map(::page)))
            if (context.history.isNotEmpty()) put("history", JSONArray(context.history.map { (url, title) -> JSONObject().put("url", url).put("title", title) }))
            context.location?.let { (lat, lon) -> put("location", JSONObject().put("lat", lat).put("lon", lon)) }
            if (context.memory.isNotEmpty()) put("memory", JSONArray(context.memory))
        })
        put("tools", JSONArray(tools))
    }.toString()

    private fun page(p: PageContext) = JSONObject().put("url", p.url).put("title", p.title).put("text", p.text.take(MAX_PAGE_CHARS)).apply {
        p.selection?.let { put("selection", it.take(MAX_SELECTION_CHARS)) }
    }

    /** Parses one Server-Sent Event (`event:` and `data:` lines) into an [AiEvent]; unknown events are ignored. */
    fun parseEvent(event: String?, data: String): AiEvent? = runCatching {
        val json = if (data.isBlank()) JSONObject() else JSONObject(data)
        when (event ?: "message") {
            "delta", "message" -> json.optString("text").takeIf { it.isNotEmpty() }?.let { AiEvent.Delta(it) }
            "citation" -> {
                val url = json.optString("url")
                if (!(url.startsWith("https://") || url.startsWith("http://"))) null
                else AiEvent.Cite(Citation(json.optInt("index", 0), json.optString("title", url), url, json.optString("quote").ifBlank { null }))
            }
            "action" -> {
                val type = json.optString("type")
                if (type !in tools) null else AiEvent.Proposal(type, json.optString("target"), json.optString("query").ifBlank { null })
            }
            "done" -> AiEvent.Done
            "error" -> AiEvent.Failure(json.optString("code", "error"), json.optString("message", "Mylo AI couldn't answer."))
            else -> null
        }
    }.getOrElse { AiEvent.Failure("invalid_response", "Mylo AI sent a reply this app couldn't read.") }

    /** Splits an SSE byte stream's lines into events; feed lines in order, get events as blocks complete. */
    class SseReader {
        private var event: String? = null
        private val data = StringBuilder()

        fun line(raw: String): AiEvent? {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) {
                if (event == null && data.isEmpty()) return null
                val parsed = parseEvent(event, data.toString())
                event = null; data.setLength(0)
                return parsed
            }
            if (line.startsWith(":")) return null
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
            }
            return null
        }
    }

    /** A short-lived voice session minted by the Mylo AI service (never a provider API key). */
    data class VoiceSession(val provider: String, val clientSecret: String, val expiresAtSeconds: Long, val model: String, val voice: String, val webrtcUrl: String)

    fun voiceSessionRequest(voice: String, private: Boolean) = JSONObject().put("voice", voice).put("private", private).put("tools", JSONArray(tools)).toString()

    fun parseVoiceSession(body: String, nowSeconds: Long): VoiceSession {
        val json = JSONObject(body)
        val secret = json.getString("clientSecret")
        val url = json.getString("webrtcUrl")
        require(secret.isNotBlank() && !secret.startsWith("sk-")) { "The service returned a provider key instead of a short-lived session" }
        require(url.startsWith("https://")) { "Voice sessions must use HTTPS" }
        val expires = json.getLong("expiresAt")
        require(expires > nowSeconds) { "The voice session had already expired" }
        return VoiceSession(json.optString("provider", "openai-realtime"), secret, expires, json.getString("model"), json.getString("voice"), url)
    }

    const val MAX_TURNS = 20
    const val MAX_PAGE_CHARS = 24_000
    const val MAX_SELECTION_CHARS = 4_000
}

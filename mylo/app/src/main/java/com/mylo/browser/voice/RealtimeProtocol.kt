package com.mylo.browser.voice

import org.json.JSONArray
import org.json.JSONObject

/** What the realtime voice service tells the app over the call's event channel. */
sealed interface RealtimeEvent {
    data object SessionReady : RealtimeEvent
    /** The person started talking (Mylo stops speaking: interruption). */
    data object SpeechStarted : RealtimeEvent
    data object SpeechStopped : RealtimeEvent
    /** Live caption of what the person said. */
    data class HeardDelta(val itemId: String, val text: String) : RealtimeEvent
    data class Heard(val itemId: String, val text: String) : RealtimeEvent
    /** Live caption of what Mylo is saying. */
    data class SayingDelta(val responseId: String, val text: String) : RealtimeEvent
    data class Said(val responseId: String, val text: String) : RealtimeEvent
    data object SpeakingStarted : RealtimeEvent
    data object SpeakingStopped : RealtimeEvent
    /** Mylo wants a browser action; the app answers with [Realtime.toolResult] after Action Preview's rules. */
    data class ToolCall(val callId: String, val name: String, val arguments: JSONObject) : RealtimeEvent
    data class ResponseDone(val responseId: String, val status: String) : RealtimeEvent
    data class Failure(val code: String, val message: String) : RealtimeEvent
}

/**
 * The realtime voice protocol (OpenAI Realtime over WebRTC: JSON events on the "oai-events" data channel),
 * kept as pure JSON so it is unit-tested off-device. Accepts both the current event names and the earlier
 * beta names. Other providers (ElevenLabs, Gemini Live) get their own mapping onto [RealtimeEvent].
 */
object Realtime {
    const val DATA_CHANNEL = "oai-events"

    fun parse(text: String): RealtimeEvent? = runCatching {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "session.created", "session.updated" -> RealtimeEvent.SessionReady
            "input_audio_buffer.speech_started" -> RealtimeEvent.SpeechStarted
            "input_audio_buffer.speech_stopped" -> RealtimeEvent.SpeechStopped
            "conversation.item.input_audio_transcription.delta" -> RealtimeEvent.HeardDelta(json.optString("item_id"), json.optString("delta"))
            "conversation.item.input_audio_transcription.completed" -> RealtimeEvent.Heard(json.optString("item_id"), json.optString("transcript").trim())
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> RealtimeEvent.SayingDelta(json.optString("response_id"), json.optString("delta"))
            "response.output_audio_transcript.done", "response.audio_transcript.done" -> RealtimeEvent.Said(json.optString("response_id"), json.optString("transcript"))
            "output_audio_buffer.started" -> RealtimeEvent.SpeakingStarted
            "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> RealtimeEvent.SpeakingStopped
            "response.function_call_arguments.done" -> {
                val arguments = runCatching { JSONObject(json.optString("arguments").ifBlank { "{}" }) }.getOrElse { JSONObject() }
                RealtimeEvent.ToolCall(json.optString("call_id"), json.optString("name"), arguments)
            }
            "response.done" -> json.optJSONObject("response").let { RealtimeEvent.ResponseDone(it?.optString("id").orEmpty(), it?.optString("status").orEmpty()) }
            "error" -> json.optJSONObject("error").let { RealtimeEvent.Failure(it?.optString("code").orEmpty().ifBlank { "error" }, it?.optString("message").orEmpty()) }
            else -> null
        }
    }.getOrNull()

    /**
     * Turn-taking: hands-free lets the service hear when the person has finished (and stop Mylo when they
     * start talking); press and hold turns that off so only the held turn counts.
     */
    fun turnTaking(handsFree: Boolean, transcriptionModel: String = "gpt-4o-transcribe"): String = JSONObject()
        .put("type", "session.update")
        .put("session", JSONObject()
            .put("type", "realtime")
            .put("audio", JSONObject().put("input", JSONObject()
                .put("transcription", JSONObject().put("model", transcriptionModel))
                .put("noise_reduction", JSONObject().put("type", "near_field"))
                .put("turn_detection", if (handsFree) JSONObject().put("type", "semantic_vad").put("create_response", true).put("interrupt_response", true) else JSONObject.NULL))))
        .toString()

    /** Press and hold: forget earlier audio when the hold starts… */
    fun holdStarted(): String = JSONObject().put("type", "input_audio_buffer.clear").toString()

    /** …and answer what was said when it ends. */
    fun holdEnded(): List<String> = listOf(JSONObject().put("type", "input_audio_buffer.commit").toString(), respond())

    fun respond(): String = JSONObject().put("type", "response.create").toString()

    /** A voice sample: Mylo says [text] in the session's voice. */
    fun say(text: String): String = JSONObject().put("type", "response.create").put("response", JSONObject()
        .put("instructions", "Say exactly this, warmly and naturally, and nothing else: $text")).toString()

    /** Stop speaking: cancel the answer in progress and drop the audio not yet played. */
    fun stopSpeaking(): List<String> = listOf(JSONObject().put("type", "response.cancel").toString(), JSONObject().put("type", "output_audio_buffer.clear").toString())

    /** Browser data the switchboard allowed, added to the voice conversation as untrusted data. */
    fun context(block: String): String = JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
        .put("type", "message").put("role", "system")
        .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", block)))).toString()

    /** Something the person typed during a voice conversation (Type instead keeps the same conversation). */
    fun typed(text: String): List<String> = listOf(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
        .put("type", "message").put("role", "user")
        .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text)))).toString(), respond())

    /** The outcome of a tool call (done, refused, or waiting for the person's Allow), then let Mylo continue. */
    fun toolResult(callId: String, output: JSONObject): List<String> = listOf(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
        .put("type", "function_call_output").put("call_id", callId).put("output", output.toString()))
        .toString(), respond())
}

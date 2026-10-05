package com.mylo.browser.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why listening stopped without words, in words the user can act on. */
enum class ListenProblem(val message: String) {
    NoPermission("Mylo needs microphone permission to listen. You can allow it in Android Settings."),
    Unavailable("Speech recognition isn’t available on this phone. You can type instead."),
    LanguageMissing("Speech recognition for your language isn’t installed on this phone yet. You can type instead, or add it in Android’s speech settings."),
    NothingHeard("I didn’t catch that. Tap the microphone and try again."),
    Busy("The microphone is busy with another app. Try again in a moment."),
    Network("Speech recognition needs a connection right now. You can type instead."),
    Failed("Listening stopped unexpectedly. Please try again."),
}

/** What the microphone side of a voice conversation is doing. */
data class ListenState(
    val listening: Boolean = false,
    /** 0..1 from the recognizer's live input level (real microphone amplitude). */
    val level: Float = 0f,
    /** Words so far, updated while the person speaks. */
    val partial: String = "",
    val problem: ListenProblem? = null,
)

/**
 * Turns speech into text with Android's own speech recognizer, preferring on-device recognition (Android
 * 12+ when the phone has it). This is the microphone half of Voice Mode until the Mylo AI realtime voice
 * service is connected; the words go into the same conversation as typing. Main thread only.
 */
class DeviceSpeech(private val context: Context, private val onWords: (String) -> Unit) {
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null
    private var gotWords = false
    private var holdToTalk = false
    private var usingOnDevice = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable

    private val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Starts listening; [holdToTalk] keeps listening through pauses until [finish] (press and hold). */
    fun start(holdToTalk: Boolean) {
        cancel()
        if (!available) { _state.value = ListenState(problem = ListenProblem.Unavailable); return }
        this.holdToTalk = holdToTalk
        begin(onDevice = onDeviceAvailable)
    }

    /** Starts a recognizer: on-device first; Android's standard recognizer when the phone lacks the language. */
    private fun begin(onDevice: Boolean) {
        val created = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (created == null) { _state.value = ListenState(problem = ListenProblem.Unavailable); return }
        recognizer = created
        usingOnDevice = onDevice
        gotWords = false
        created.setRecognitionListener(Listener(created))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (holdToTalk) {
            // Press and hold: pauses don't end the turn; releasing does.
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
        }
        _state.value = ListenState(listening = true)
        runCatching { created.startListening(intent) }.onFailure { end(ListenProblem.Failed) }
    }

    /** The person finished (released the button): use what was heard. */
    fun finish() { recognizer?.let { runCatching { it.stopListening() } } }

    /** Stops listening and discards anything not yet sent (Close voice mode, Stop). */
    fun cancel() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        _state.value = ListenState()
    }

    fun clearProblem() { if (_state.value.problem != null) _state.value = _state.value.copy(problem = null) }

    private fun end(problem: ListenProblem?) {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        _state.value = ListenState(problem = problem)
    }

    private inner class Listener(private val owner: SpeechRecognizer) : RecognitionListener {
        private fun current() = recognizer === owner
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            if (current()) _state.value = _state.value.copy(level = levelFromRms(rmsdB))
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { if (current()) _state.value = _state.value.copy(level = 0f) }
        override fun onPartialResults(partialResults: Bundle?) {
            val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (current() && words.isNotBlank()) _state.value = _state.value.copy(partial = words)
        }
        override fun onResults(results: Bundle?) {
            if (!current()) return
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                .ifBlank { _state.value.partial.trim() }
            if (words.isBlank()) { end(ListenProblem.NothingHeard); return }
            gotWords = true
            end(null)
            onWords(words)
        }
        override fun onError(error: Int) {
            if (!current()) return
            // A released hold with partial words still counts as what the person said.
            val partial = _state.value.partial.trim()
            if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && partial.isNotEmpty()) {
                end(null); onWords(partial); return
            }
            // The phone has on-device recognition but not this language: use Android's standard recognizer.
            if (usingOnDevice && error in LANGUAGE_ERRORS) {
                recognizer = null
                runCatching { owner.destroy() }
                _state.value = ListenState(listening = true)
                begin(onDevice = false)
                return
            }
            end(problemFor(error))
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        /** Android reports roughly -2..10 dB; the screen wants 0..1. */
        fun levelFromRms(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

        fun problemFor(error: Int): ListenProblem = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenProblem.NoPermission
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenProblem.NothingHeard
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> ListenProblem.Busy
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> ListenProblem.Network
            in LANGUAGE_ERRORS -> ListenProblem.LanguageMissing
            ERROR_SERVER_DISCONNECTED -> ListenProblem.Network
            else -> ListenProblem.Failed
        }

        /** Android 12's "language not supported" (12) and "language unavailable" (13). */
        private val LANGUAGE_ERRORS = setOf(12, 13)
        private const val ERROR_SERVER_DISCONNECTED = 11
    }
}

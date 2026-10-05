package com.mylo.browser.voice

import android.content.Context
import android.media.AudioManager
import com.mylo.browser.ai.AiContract
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A realtime voice call's state, as Voice Mode shows it. */
data class CallState(
    val phase: Phase = Phase.Off,
    /** 0..1: the microphone's live level while the person talks, Mylo's playback level while it speaks. */
    val micLevel: Float = 0f,
    val speakerLevel: Float = 0f,
    val muted: Boolean = false,
    val heard: String = "",
    val saying: String = "",
    val problem: String? = null,
) {
    enum class Phase { Off, Connecting, Listening, Thinking, Speaking }
}

/**
 * Mylo's realtime voice: one WebRTC call to the provider the Mylo AI service chose (OpenAI Realtime), opened
 * with a short-lived session secret, never an API key. Microphone audio goes up the call; Mylo's voice
 * comes back as an audio track; captions, interruptions and tool calls arrive on the event channel.
 * Hands-free uses the service's turn detection; press and hold sends only the held turn.
 */
class RealtimeVoice(private val context: Context, private val onEvent: (RealtimeEvent) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()
    private val _sent = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Every event the app sent (for the test harness and the privacy receipt). */
    val sent: SharedFlow<String> = _sent.asSharedFlow()

    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var connection: PeerConnection? = null
    private var channel: DataChannel? = null
    private var micTrack: AudioTrack? = null
    private var levels: Job? = null
    private var handsFree = true
    private var microphone = true
    private var sayingFor: String? = null
    private var savedAudioMode: Int? = null

    /** The event channel is open (context, typed text and tool results can be sent). */
    val ready: Boolean get() = channel?.state() == DataChannel.State.OPEN

    /** Opens the call with [session]; [handsFree] picks the turn-taking; [microphone] false for voice samples. */
    suspend fun open(session: AiContract.VoiceSession, handsFree: Boolean, microphone: Boolean = true) {
        close()
        this.handsFree = handsFree
        this.microphone = microphone
        _state.value = CallState(phase = CallState.Phase.Connecting)
        try {
            val factory = createFactory()
            val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
            val pc = factory.createPeerConnection(config, Observer()) ?: error("Couldn't start the call")
            connection = pc
            val source = factory.createAudioSource(MediaConstraints())
            micTrack = factory.createAudioTrack("mylo-mic", source).also { it.setEnabled(microphone); pc.addTrack(it, listOf("mylo")) }
            channel = pc.createDataChannel(Realtime.DATA_CHANNEL, DataChannel.Init()).also { it.registerObserver(ChannelObserver(it)) }
            val offer = pc.awaitOffer()
            pc.awaitSetLocal(offer)
            val answer = withContext(Dispatchers.IO) { exchange(session, offer.description) }
            pc.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))
            useCallAudio(true)
            startLevels()
        } catch (e: Exception) {
            close()
            _state.value = CallState(problem = "Mylo’s voice couldn’t connect. Check your connection and try again.")
        }
    }

    fun setMuted(muted: Boolean) {
        micTrack?.setEnabled(!muted)
        _state.value = _state.value.copy(muted = muted, micLevel = if (muted) 0f else _state.value.micLevel)
    }

    /** Press and hold: the microphone counts only while held. */
    fun holdStarted() { send(Realtime.holdStarted()); micTrack?.setEnabled(true) }
    fun holdEnded() {
        Realtime.holdEnded().forEach(::send)
        if (!handsFree) micTrack?.setEnabled(false)
        _state.value = _state.value.copy(phase = CallState.Phase.Thinking)
    }

    /** Stop speaking (and let the person talk). */
    fun stopSpeaking() { Realtime.stopSpeaking().forEach(::send) }

    fun type(text: String) { Realtime.typed(text).forEach(::send) }
    fun say(text: String) = send(Realtime.say(text))

    /** Ends the call with a plain-language problem the screen shows. */
    fun fail(message: String) { close(); _state.value = CallState(problem = message) }
    fun addContext(block: String) = send(Realtime.context(block))
    fun toolResult(callId: String, output: org.json.JSONObject) = Realtime.toolResult(callId, output).forEach(::send)

    /** Ends the call: microphone off, audio released, nothing kept. */
    fun close() {
        levels?.cancel(); levels = null
        runCatching { channel?.unregisterObserver(); channel?.close(); channel?.dispose() }
        runCatching { connection?.close(); connection?.dispose() }
        runCatching { micTrack?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioModule?.release() }
        channel = null; connection = null; micTrack = null; factory = null; audioModule = null
        useCallAudio(false)
        _state.value = CallState()
    }

    fun destroy() { close(); scope.cancel() }

    private fun send(event: String) {
        val open = channel?.takeIf { it.state() == DataChannel.State.OPEN } ?: return
        open.send(DataChannel.Buffer(ByteBuffer.wrap(event.toByteArray(Charsets.UTF_8)), false))
        _sent.tryEmit(event)
    }

    private fun createFactory(): PeerConnectionFactory {
        if (!initialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            initialized = true
        }
        val module = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            // The real microphone level, from the samples WebRTC records.
            .setSamplesReadyCallback { samples -> micLevel = pcm16Level(samples.data) }
            .createAudioDeviceModule()
        audioModule = module
        return PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory().also { factory = it }
    }

    @Volatile private var micLevel = 0f

    /** Publishes the microphone and playback levels about 15 times a second. */
    private fun startLevels() {
        levels = scope.launch {
            while (isActive) {
                val speaker = connection?.let { speakerLevel(it) } ?: 0f
                val muted = _state.value.muted
                _state.value = _state.value.copy(micLevel = if (muted || !microphone) 0f else micLevel, speakerLevel = speaker)
                delay(66)
            }
        }
    }

    private suspend fun speakerLevel(pc: PeerConnection): Float = suspendCancellableCoroutine { done ->
        pc.getStats { report ->
            val level = report.statsMap.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
                ?.members?.get("audioLevel") as? Double
            if (done.isActive) done.resume(((level ?: 0.0) * 1.6).toFloat().coerceIn(0f, 1f))
        }
    }

    /** Call audio: the voice-communication mode (echo cancellation) on the loudspeaker; restored after. */
    private fun useCallAudio(on: Boolean) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (on) { savedAudioMode = audio.mode; audio.mode = AudioManager.MODE_IN_COMMUNICATION; @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = true } }
        else savedAudioMode?.let { audio.mode = it; @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = false }; savedAudioMode = null }
    }

    private fun lost() { if (ready || _state.value.phase != CallState.Phase.Connecting) _state.value = _state.value.copy(problem = "Mylo’s voice lost the connection.") }

    private fun handle(event: RealtimeEvent) {
        val now = _state.value
        _state.value = when (event) {
            RealtimeEvent.SessionReady -> now.copy(phase = if (now.phase == CallState.Phase.Connecting) CallState.Phase.Listening else now.phase)
            RealtimeEvent.SpeechStarted -> now.copy(phase = CallState.Phase.Listening, heard = "", saying = "")
            RealtimeEvent.SpeechStopped -> now.copy(phase = CallState.Phase.Thinking)
            is RealtimeEvent.HeardDelta -> now.copy(heard = now.heard + event.text)
            is RealtimeEvent.Heard -> now.copy(heard = event.text)
            // Each answer's caption starts fresh; it stays on screen until the person speaks again.
            is RealtimeEvent.SayingDelta -> if (event.responseId != sayingFor) { sayingFor = event.responseId; now.copy(saying = event.text) } else now.copy(saying = now.saying + event.text)
            is RealtimeEvent.Said -> { sayingFor = event.responseId; now.copy(saying = event.text) }
            RealtimeEvent.SpeakingStarted -> now.copy(phase = CallState.Phase.Speaking)
            RealtimeEvent.SpeakingStopped -> now.copy(phase = CallState.Phase.Listening)
            is RealtimeEvent.ResponseDone -> if (now.phase == CallState.Phase.Thinking) now.copy(phase = CallState.Phase.Listening) else now
            is RealtimeEvent.Failure -> now.copy(problem = event.message.ifBlank { "Mylo’s voice had a problem." })
            else -> now
        }
        onEvent(event)
    }

    /** The SDP offer/answer exchange with the provider, authorized by the short-lived session secret. */
    private fun exchange(session: AiContract.VoiceSession, offer: String): String {
        val call = (URL(session.webrtcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 15_000; instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer ${session.clientSecret}")
            setRequestProperty("Content-Type", "application/sdp")
        }
        try {
            call.outputStream.use { it.write(offer.toByteArray(Charsets.UTF_8)) }
            check(call.responseCode in 200..299) { "HTTP ${call.responseCode}" }
            return call.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally { call.disconnect() }
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            // A brief disconnection often recovers on its own; only a lasting one (or a failure) is reported.
            if (state == PeerConnection.IceConnectionState.FAILED) scope.launch { lost() }
            if (state == PeerConnection.IceConnectionState.DISCONNECTED) scope.launch {
                delay(5_000)
                val now = connection?.iceConnectionState()
                if (now == PeerConnection.IceConnectionState.DISCONNECTED || now == PeerConnection.IceConnectionState.FAILED) lost()
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    private inner class ChannelObserver(private val dc: DataChannel) : DataChannel.Observer {
        override fun onBufferedAmountChange(previous: Long) = Unit
        override fun onStateChange() {
            if (dc.state() == DataChannel.State.OPEN) scope.launch {
                send(Realtime.turnTaking(handsFree))
                if (!handsFree || !microphone) micTrack?.setEnabled(false)
                if (_state.value.phase == CallState.Phase.Connecting) _state.value = _state.value.copy(phase = CallState.Phase.Listening)
            }
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            val event = Realtime.parse(String(bytes, Charsets.UTF_8)) ?: return
            scope.launch { handle(event) }
        }
    }

    companion object {
        @Volatile private var initialized = false

        /** RMS of 16-bit little-endian PCM, scaled to 0..1 for the waveform. */
        fun pcm16Level(data: ByteArray): Float {
            if (data.size < 2) return 0f
            var sum = 0.0
            val n = data.size / 2
            for (i in 0 until n) {
                val sample = (data[2 * i].toInt() and 0xFF) or (data[2 * i + 1].toInt() shl 8)
                sum += sample.toDouble() * sample
            }
            val rms = sqrt(sum / n) / 32768.0
            return (rms * 6).toFloat().coerceIn(0f, 1f)
        }
    }
}

private suspend fun PeerConnection.awaitOffer(): SessionDescription = suspendCancellableCoroutine { done ->
    createOffer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { if (done.isActive) done.resume(sdp) }
        override fun onCreateFailure(error: String?) { if (done.isActive) done.resumeWithException(IllegalStateException(error)) }
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String?) = Unit
    }, MediaConstraints())
}

private suspend fun PeerConnection.awaitSetLocal(sdp: SessionDescription) = awaitSet { setLocalDescription(it, sdp) }
private suspend fun PeerConnection.awaitSetRemote(sdp: SessionDescription) = awaitSet { setRemoteDescription(it, sdp) }

private suspend fun awaitSet(block: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { done ->
    block(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetSuccess() { if (done.isActive) done.resume(Unit) }
        override fun onSetFailure(error: String?) { if (done.isActive) done.resumeWithException(IllegalStateException(error)) }
    })
}

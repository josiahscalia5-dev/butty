package com.mylo.browser.voice

import android.content.Context
import com.mylo.browser.ai.AiConversation
import com.mylo.browser.ai.AiContract
import com.mylo.browser.ai.AiDataSource
import com.mylo.browser.ai.AiException
import com.mylo.browser.ai.AiPrivacy
import com.mylo.browser.ai.AiSwitchboard
import com.mylo.browser.ai.AiTurn
import com.mylo.browser.ai.BrowserAction
import com.mylo.browser.ai.Gathered
import com.mylo.browser.ai.MyloAiService
import com.mylo.browser.ai.PrivacyReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the browser does for a voice tool call, after Action Preview has allowed it. */
interface ToolHost {
    /** The page on screen, or null on Home. */
    val pageUrl: String?
    /** Runs [action]; whether it worked and what to tell the model. */
    suspend fun run(action: BrowserAction): Pair<Boolean, String>
}

/** A consequential tool call waiting for the person's Allow. */
data class PendingTool(val callId: String, val action: BrowserAction, val summary: String, val details: List<String>)

/**
 * One realtime voice conversation with Mylo: a short-lived session from the Mylo AI service, the call itself,
 * the browser data the switchboard allows (sent once at the start, hidden details removed), every spoken turn
 * recorded in the shared conversation, and tool calls judged by Action Preview. Closing ends the call and
 * releases the microphone; nothing about the call is kept beyond the conversation on screen.
 */
class VoiceCall(
    context: Context,
    private val service: MyloAiService,
    private val conversation: AiConversation,
    private val switchboard: AiSwitchboard,
    private val gather: suspend (Set<AiDataSource>) -> Gathered,
    private val tools: ToolHost,
    private val scope: CoroutineScope,
    private val private: Boolean,
) {
    private val realtime = RealtimeVoice(context) { handle(it) }
    val state: StateFlow<CallState> = realtime.state
    private val _pending = MutableStateFlow<PendingTool?>(null)
    val pending: StateFlow<PendingTool?> = _pending.asStateFlow()
    private val _receipt = MutableStateFlow<PrivacyReceipt?>(null)
    /** What this call was given from the browser (shown with Mylo's spoken answers). */
    val receipt: StateFlow<PrivacyReceipt?> = _receipt.asStateFlow()
    private var starting: Job? = null
    private var sampleText: String? = null

    val active: Boolean get() = state.value.phase != CallState.Phase.Off || starting?.isActive == true

    /** Starts a conversation in [voice]; [handsFree] lets the service hear when the person has finished. */
    fun start(voice: String, handsFree: Boolean) {
        if (active) return
        starting = scope.launch {
            try {
                val session = service.voiceSession(voice, private)
                realtime.open(session, handsFree)
                if (state.value.problem != null) return@launch
                val (context, hidden) = AiPrivacy.prepare(gather(switchboard.authorize(AiConversation.DEFAULT_WANTS)).context)
                _receipt.value = PrivacyReceipt(context.used, hidden)
                AiContract.contextBlock(context)?.let { block -> waitForChannel(); realtime.addContext(block) }
            } catch (e: AiException) {
                realtime.fail(e.problem.message)
            }
        }
    }

    /** Plays a short sample of [voice] (no microphone), then hangs up. */
    fun sample(voice: String, text: String) {
        if (active) return
        sampleText = text
        starting = scope.launch {
            try {
                realtime.open(service.voiceSession(voice, private), handsFree = false, microphone = false)
                waitForChannel()
                realtime.say(text)
                withTimeoutOrNull(20_000) { while (state.value.phase != CallState.Phase.Off && sampleText != null) delay(200) }
            } catch (e: AiException) {
                realtime.fail(e.problem.message)
            } finally {
                if (sampleText != null) { sampleText = null; realtime.close() }
            }
        }
    }

    fun mute(on: Boolean) = realtime.setMuted(on)
    fun holdStarted() = realtime.holdStarted()
    fun holdEnded() = realtime.holdEnded()

    /** The person interrupted (tap while Mylo speaks): Mylo stops and listens. */
    fun interrupt() = realtime.stopSpeaking()

    /** Type instead during a call: the same conversation, answered aloud. */
    fun type(text: String) {
        conversation.record(AiTurn.Role.User, text)
        realtime.type(text)
    }

    /** The person's answer to a pending consequential action. */
    fun answer(allow: Boolean) {
        val waiting = _pending.value ?: return
        _pending.value = null
        scope.launch {
            val output = if (allow) tools.run(waiting.action).let { (ok, detail) -> BrowserTools.result(ok, detail) }
            else BrowserTools.result(false, "The person chose not to allow it.")
            realtime.toolResult(waiting.callId, output)
        }
    }

    fun close() {
        starting?.cancel(); starting = null
        _pending.value = null
        sampleText = null
        realtime.close()
    }

    fun destroy() { close(); realtime.destroy() }

    private suspend fun waitForChannel() { withTimeoutOrNull(10_000) { while (!realtime.ready) delay(50) } }

    private fun handle(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.Heard -> conversation.record(AiTurn.Role.User, event.text)
            is RealtimeEvent.Said -> if (sampleText == null) conversation.record(AiTurn.Role.Assistant, event.text, _receipt.value)
            RealtimeEvent.SpeakingStopped -> if (sampleText != null) { sampleText = null; scope.launch { delay(400); realtime.close() } }
            is RealtimeEvent.ToolCall -> when (val plan = BrowserTools.plan(event.name, event.arguments, tools.pageUrl)) {
                is ToolPlan.Run -> scope.launch { realtime.toolResult(event.callId, tools.run(plan.action).let { (ok, detail) -> BrowserTools.result(ok, detail) }) }
                is ToolPlan.Ask -> _pending.value = PendingTool(event.callId, plan.action, plan.preview.summary, plan.preview.details)
                is ToolPlan.Refuse -> realtime.toolResult(event.callId, BrowserTools.result(false, plan.reason))
            }
            else -> Unit
        }
    }
}

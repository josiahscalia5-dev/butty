package com.mylo.browser.ai

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One message in a Mylo conversation, as the chat and voice screens show it. */
data class ChatMessage(
    val id: Long,
    val role: AiTurn.Role,
    val text: String,
    val state: State = State.Done,
    val citations: List<Citation> = emptyList(),
    /** What this answer used and didn't (assistant answers only). */
    val receipt: PrivacyReceipt? = null,
    /** A plain-language explanation when nothing (or not everything) could be answered. */
    val note: String? = null,
    /** Mylo asking before it reads something the switchboard has off. */
    val ask: AccessAsk? = null,
    /** Browser actions the answer proposed; each still goes through [ActionGate]. */
    val proposals: List<AiEvent.Proposal> = emptyList(),
    /** Answered on the phone without the service (for example "Open a page first"); never sent later. */
    val local: Boolean = false,
) {
    enum class State { Streaming, Done, Stopped, Failed, NotSent }
}

/** "Explain this page needs Current Page, which is off. Allow once?" */
data class AccessAsk(val missing: Set<AiDataSource>, val answered: Boolean = false) {
    val prompt: String get() {
        val names = missing.map { it.label }
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        return "To answer that, I need to read $list, which ${if (names.size == 1) "is" else "are"} off in What Mylo can see. May I look, just this once?"
    }
}

/** What one request gathered from the sources it was allowed to read. */
data class Gathered(val context: AiContext, val unavailable: Set<AiDataSource> = emptySet())

/**
 * A Mylo conversation: the same one whether the person types or talks. Every request reads only what the
 * [switchboard] allows (one-time grants are used up), hides card numbers, ID numbers, bank accounts and
 * secrets in links before anything leaves the phone, and ends with a Privacy Receipt. Without a Mylo AI
 * service, nothing is sent and the conversation says so. Private Mode keeps no copy: [clear] forgets it.
 */
class AiConversation(
    private val service: MyloAiService,
    private val switchboard: AiSwitchboard,
    private val scope: CoroutineScope,
    val private: Boolean,
    private val gather: suspend (Set<AiDataSource>) -> Gathered,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var conversationId = newId()
    private var nextId = 1L
    private var job: Job? = null
    private var pending: Pending? = null

    private data class Pending(val userId: Long, val wants: Set<AiDataSource>, val needs: Set<AiDataSource>, val askId: Long)

    /**
     * Sends what the person typed or said. [wants] are read if allowed; [needs] are sources this question
     * can't be answered without, so Mylo asks first when one is off. Returns false for empty text.
     */
    fun send(text: String, wants: Set<AiDataSource> = DEFAULT_WANTS, needs: Set<AiDataSource> = emptySet()): Boolean {
        val words = text.trim()
        if (words.isEmpty()) return false
        stop()
        pending?.let { resolveAsk(it.askId) }
        pending = null
        val user = add(ChatMessage(nextId++, AiTurn.Role.User, words))
        val missing = needs.filterNot(switchboard::allowed).toSet()
        if (missing.isNotEmpty()) {
            val ask = add(ChatMessage(nextId++, AiTurn.Role.Assistant, "", ask = AccessAsk(missing)))
            pending = Pending(user.id, wants, needs, ask.id)
            return true
        }
        run(user.id, wants + needs)
        return true
    }

    /** The person's answer to [AccessAsk]: allow the missing sources once, or answer without them. */
    fun answerAsk(allowOnce: Boolean) {
        val waiting = pending ?: return
        pending = null
        resolveAsk(waiting.askId)
        if (allowOnce) {
            val missing = waiting.needs.filterNot(switchboard::allowed)
            missing.forEach { switchboard.set(it, AiGrant.Once) }
            run(waiting.userId, waiting.wants + waiting.needs)
        } else {
            add(ChatMessage(nextId++, AiTurn.Role.Assistant, "Okay, I won't look. I can still help with anything that doesn't need it."))
        }
    }

    /**
     * A spoken turn from a realtime voice call (the person's words or Mylo's), kept in the same conversation so
     * Type instead shows it and later typed questions carry it. [receipt] goes on Mylo's turns.
     */
    fun record(role: AiTurn.Role, text: String, receipt: PrivacyReceipt? = null) {
        val words = text.trim()
        if (words.isEmpty()) return
        add(ChatMessage(nextId++, role, words, receipt = receipt))
    }

    /** A question Mylo can answer without the service, such as "open a page first". Nothing is sent. */
    fun answerLocally(question: String, reply: String) {
        stop()
        pending?.let { resolveAsk(it.askId) }
        pending = null
        add(ChatMessage(nextId++, AiTurn.Role.User, question.trim(), local = true))
        add(ChatMessage(nextId++, AiTurn.Role.Assistant, reply, local = true))
    }

    /** Stops the answer being written (the part already shown stays, marked as stopped). */
    fun stop() {
        val running = job ?: return
        job = null
        running.cancel()
        update { if (it.state == ChatMessage.State.Streaming) it.copy(state = ChatMessage.State.Stopped) else it }
        _busy.value = false
    }

    /** Forgets the whole conversation (Private Mode's burn, or "New conversation"). */
    fun clear() {
        stop()
        pending = null
        _messages.value = emptyList()
        conversationId = newId()
    }

    private fun run(userId: Long, wants: Set<AiDataSource>) {
        if (!service.configured) {
            update(userId) { it.copy(state = ChatMessage.State.NotSent) }
            add(ChatMessage(nextId++, AiTurn.Role.Assistant, "", ChatMessage.State.NotSent, note = AiProblem.NotConfigured.message))
            return
        }
        val answer = add(ChatMessage(nextId++, AiTurn.Role.Assistant, "", ChatMessage.State.Streaming))
        _busy.value = true
        val turns = history(userId)
        job = scope.launch {
            var receipt: PrivacyReceipt? = null
            try {
                val gathered = gather(switchboard.authorize(wants))
                val (context, redactions) = AiPrivacy.prepare(gathered.context)
                receipt = PrivacyReceipt(context.used, redactions, gathered.unavailable)
                service.chat(conversationId, turns, context, private).collect { event ->
                    when (event) {
                        is AiEvent.Delta -> update(answer.id) { it.copy(text = it.text + event.text) }
                        is AiEvent.Cite -> update(answer.id) { it.copy(citations = it.citations + event.citation) }
                        is AiEvent.Proposal -> update(answer.id) { it.copy(proposals = it.proposals + event) }
                        AiEvent.Done -> update(answer.id) { it.copy(state = ChatMessage.State.Done, receipt = receipt) }
                        is AiEvent.Failure -> update(answer.id) { it.copy(state = ChatMessage.State.Failed, note = event.message, receipt = receipt) }
                    }
                }
                update(answer.id) { if (it.state == ChatMessage.State.Streaming) it.copy(state = ChatMessage.State.Done, receipt = receipt) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                update(answer.id) { it.copy(state = ChatMessage.State.Failed, note = e.problem.message, receipt = receipt) }
            } catch (e: Exception) {
                update(answer.id) { it.copy(state = ChatMessage.State.Failed, note = AiProblem.Unreachable.message, receipt = receipt) }
            } finally {
                if (job === coroutineContext[Job]) { job = null; _busy.value = false }
            }
        }
    }

    /** The turns sent with a question: earlier exchanges that were really sent, then the question. */
    private fun history(userId: Long): List<AiTurn> = _messages.value
        .filter { it.id <= userId && it.ask == null && !it.local && it.text.isNotBlank() }
        .filter { it.role == AiTurn.Role.User && it.state != ChatMessage.State.NotSent || it.role == AiTurn.Role.Assistant && it.state in setOf(ChatMessage.State.Done, ChatMessage.State.Stopped) }
        .map { AiTurn(it.role, it.text) }

    private fun resolveAsk(id: Long) = update(id) { m -> m.copy(ask = m.ask?.copy(answered = true)) }

    private fun add(message: ChatMessage): ChatMessage {
        _messages.value = _messages.value + message
        return message
    }

    private fun update(id: Long, change: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) change(it) else it }
    }

    private fun update(change: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map(change)
    }

    companion object {
        /** Every question may use whatever What Mylo can see allows; the switchboard decides. */
        val DEFAULT_WANTS: Set<AiDataSource> = AiDataSource.entries.toSet()
    }
}

/** What Mylo hides from page and tab text before it leaves the phone, whatever the switchboard says. */
object AiPrivacy {
    val alwaysHidden = setOf(SensitiveKind.Card, SensitiveKind.NationalId, SensitiveKind.Iban, SensitiveKind.SecretInLink)

    /** The context with sensitive details replaced, and how many were hidden. */
    fun prepare(context: AiContext): Pair<AiContext, Int> {
        var hidden = 0
        fun clean(text: String): String {
            val found = Redactor.find(text).count { it.kind in alwaysHidden }
            if (found == 0) return text
            hidden += found
            return Redactor.redact(text, alwaysHidden)
        }
        fun page(p: PageContext) = p.copy(url = clean(p.url), title = clean(p.title), text = clean(p.text), selection = p.selection?.let(::clean))
        val prepared = context.copy(
            page = context.page?.let(::page),
            tabs = context.tabs.map(::page),
            history = context.history.map { (url, title) -> clean(url) to clean(title) },
            memory = context.memory.map(::clean),
            // Location is rounded on the phone to about a kilometre before it is used at all.
            location = context.location?.let { (lat, lon) -> roundCoordinate(lat) to roundCoordinate(lon) },
            screenshot = context.screenshot?.takeIf { it.length <= AiContract.MAX_SCREENSHOT_CHARS },
        )
        return prepared to hidden
    }

    /** Two decimal places: roughly 1 km, enough for "near me" and never a street address. */
    fun roundCoordinate(value: Double): Double = Math.round(value * 100.0) / 100.0
}

package com.mylo.browser

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mylo.browser.ai.AiConversation
import com.mylo.browser.ai.AiDataSource
import com.mylo.browser.ai.AiEndpoint
import com.mylo.browser.ai.AiGrant
import com.mylo.browser.ai.AiPreferences
import com.mylo.browser.ai.AiSwitchboard
import com.mylo.browser.ai.AiTurn
import com.mylo.browser.ai.ChatMessage
import com.mylo.browser.ai.DeviceContext
import com.mylo.browser.ai.MyloAi
import com.mylo.browser.ai.MyloMemory
import com.mylo.browser.ai.MyloVoice
import com.mylo.browser.ai.PrivacyReceipt
import com.mylo.browser.voice.CallState
import com.mylo.browser.voice.DeviceSpeech
import com.mylo.browser.voice.PageHelper
import com.mylo.browser.voice.PageTargets
import com.mylo.browser.voice.PageTranslator
import com.mylo.browser.voice.VoiceCall
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.launch
import com.mylo.browser.voice.ListenProblem
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.delay

private val SheetNight = Color(0xFF0B1636)
private val SheetCard = Color(0xFF15234A)
private val SheetLine = Color(0xFF2A3A6E)
private val SheetInk = Color(0xFFF3F4FC)
private val SheetMuted = Color(0xFFB4BAD8)
private val SheetAccent = Color(0xFFB9ACFF)
private val UserBubble = Color(0xFFCEC5FF)
private val UserInk = Color(0xFF1F1A5C)
private val NoteBack = Color(0xFF33290F)
private val NoteInk = Color(0xFFFFDB91)
private val OnMint = Color(0xFF52E0AE)

private enum class VoiceSheet { Chat, Access, Settings, Translate }



@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.voiceAutomation() = semantics { testTagsAsResourceId = true }

/** What a page action asks Mylo, and the browser data it can't be answered without. */
internal fun voicePrompt(action: VoiceAction, language: String = Locale.getDefault().displayLanguage): Pair<String, Set<AiDataSource>> = when (action) {
    VoiceAction.Explain -> "Explain this page in simple words." to setOf(AiDataSource.CurrentPage)
    VoiceAction.FindPricing -> "Where is the pricing on this page, and what does it say?" to setOf(AiDataSource.CurrentPage)
    VoiceAction.HelpCancel -> "Help me cancel. Guide me step by step, using this page." to setOf(AiDataSource.CurrentPage)
    VoiceAction.CompareTabs -> "Compare this page with my other open tabs. What are the key differences?" to setOf(AiDataSource.CurrentPage, AiDataSource.OtherTabs)
    VoiceAction.SiteSafety -> "Is this site safe? Check this page for red flags." to setOf(AiDataSource.CurrentPage)
    VoiceAction.Translate -> "Translate the main text of this page into ${language.ifBlank { "English" }}." to setOf(AiDataSource.CurrentPage)
}

/**
 * Voice Mode in the browser: the approved screen, with the typed chat (Type instead), the switchboard
 * (What Mylo can see / Adjust) and Mylo's voice settings. The conversation lives in [conversation], so
 * typing and (later) talking continue the same one, and closing Voice Mode stops any answer in progress.
 */
@Composable fun VoiceRoute(
    conversation: AiConversation,
    switchboard: AiSwitchboard,
    call: VoiceCall,
    page: PageHelper,
    hasPage: Boolean,
    tabs: Int,
    onClose: () -> Unit,
    onNav: (VoiceNav) -> Unit,
    onMoreSettings: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { AiPreferences(context.applicationContext) }
    val messages by conversation.messages.collectAsState()
    val busy by conversation.busy.collectAsState()
    var sheet by rememberSaveable { mutableStateOf<VoiceSheet?>(null) }
    var accessVersion by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var connectedHost by remember { mutableStateOf(MyloAi.connectedHost(context)) }
    LaunchedEffect(problem) { if (problem != null) { delay(7_000); problem = null } }
    // One-time grants are used up by a question, so the chips are re-read whenever the conversation moves.
    val access = remember(accessVersion, messages) {
        AiAccessUi(switchboard.allowed(AiDataSource.CurrentPage), switchboard.allowed(AiDataSource.OtherTabs),
            switchboard.allowed(AiDataSource.History), switchboard.allowed(AiDataSource.Location))
    }
    // Talking: Mylo's realtime voice when the Mylo AI service is connected; otherwise Android's speech recognizer
    // (on-device where the phone has it) turns speech into the same conversation, answered in text.
    val speech = remember { DeviceSpeech(context.applicationContext) { words -> conversation.send(words) } }
    val listen by speech.state.collectAsState()
    val callState by call.state.collectAsState()
    val pendingTool by call.pending.collectAsState()
    val inCall = callState.phase != CallState.Phase.Off
    DisposableEffect(speech) { onDispose { speech.cancel(); call.close() } }
    var holdPending by remember { mutableStateOf<Boolean?>(null) }
    fun begin(hold: Boolean) {
        if (connectedHost != null) call.start(preferences.voice.id, handsFree = !hold) else speech.start(holdToTalk = hold)
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val hold = holdPending
        holdPending = null
        if (!granted) problem = ListenProblem.NoPermission.message
        else if (hold == false) begin(hold = false)
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            switchboard.set(AiDataSource.Location, AiGrant.Off); accessVersion++
            problem = "Location stays off: Android's location permission wasn't given."
        }
    }
    LaunchedEffect(listen.problem) { listen.problem?.let { problem = it.message; speech.clearProblem() } }
    LaunchedEffect(callState.problem) { callState.problem?.let { problem = it } }
    fun startListening(hold: Boolean) {
        problem = null
        conversation.stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin(hold)
        // A hold can't survive Android's permission dialog: after allowing, the next hold (or a tap) listens.
        else { holdPending = hold; askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }
    }
    val lastAnswer = messages.lastOrNull { it.role == AiTurn.Role.Assistant && (it.text.isNotBlank() || it.note != null) }
    val caption = when {
        inCall && callState.phase == CallState.Phase.Connecting -> "Connecting to Mylo’s voice…"
        // Mylo's words while (and after) it speaks; the person's words once they speak again.
        inCall && callState.saying.isNotBlank() -> "Mylo: " + callState.saying.trim().takeLast(160)
        inCall && callState.heard.isNotBlank() -> "You: " + callState.heard.trim().takeLast(160)
        listen.listening -> "You: " + listen.partial.ifBlank { "…" }.takeLast(160)
        lastAnswer != null && lastAnswer.text.isNotBlank() -> "Mylo: " + lastAnswer.text.trim().take(160)
        lastAnswer?.note != null -> lastAnswer.note
        else -> null
    }
    val phase = when {
        inCall -> when (callState.phase) {
            CallState.Phase.Speaking -> VoicePhase.Speaking
            CallState.Phase.Listening -> VoicePhase.Listening
            else -> VoicePhase.Thinking
        }
        listen.listening -> VoicePhase.Listening
        busy -> VoicePhase.Thinking
        else -> VoicePhase.Idle
    }
    val level = if (inCall) (if (callState.phase == CallState.Phase.Speaking) callState.speakerLevel else callState.micLevel) else listen.level
    val ui = VoiceModeUi(phase = phase, level = level, micLive = listen.listening || (inCall && !callState.muted && callState.phase != CallState.Phase.Connecting),
        access = access, tabs = tabs, caption = caption, problem = problem, inCall = inCall, muted = callState.muted)
    fun close() { speech.cancel(); call.close(); conversation.stop(); onClose() }
    val scope = rememberCoroutineScope()
    fun ask(action: VoiceAction) {
        val (prompt, needs) = voicePrompt(action)
        conversation.send(prompt, wants = AiConversation.DEFAULT_WANTS + needs, needs = needs)
        sheet = VoiceSheet.Chat
    }
    fun runAction(action: VoiceAction) {
        val title = action.title.replace("\n", " ")
        if (!hasPage) {
            conversation.answerLocally(title, "Open a web page first, then ask me again and I’ll help with “$title”.")
            sheet = VoiceSheet.Chat
            return
        }
        when (action) {
            // Finding a section happens on the phone; Mylo AI is only asked when the page has no such section
            // (pricing) or for step-by-step guidance (cancelling).
            VoiceAction.FindPricing -> scope.launch {
                if (page.show(PageTargets.pricing) != null) { page.note("Mylo found the pricing section and marked it."); close() }
                else if (connectedHost != null) ask(action)
                else { conversation.answerLocally(title, "I couldn’t find a pricing section on this page."); sheet = VoiceSheet.Chat }
            }
            VoiceAction.HelpCancel -> scope.launch {
                val found = page.show(PageTargets.cancel) != null
                when {
                    connectedHost != null -> ask(action)
                    found -> { page.note("Mylo found how to cancel and marked it. Step-by-step help needs the Mylo AI service."); close() }
                    else -> { conversation.answerLocally(title, "I couldn’t find a cancel option on this page. Look for Account, Plan or Subscription settings."); sheet = VoiceSheet.Chat }
                }
            }
            VoiceAction.Translate -> sheet = VoiceSheet.Translate
            else -> ask(action)
        }
    }
    BackHandler(enabled = sheet == null) { close() }
    Box(Modifier.fillMaxSize().background(Color(0xFF071430)).voiceAutomation()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        VoiceModeScreen(
            ui = ui,
            // Tap: start talking; while Mylo speaks, stop it (interrupt); during a call otherwise, hang up.
            // Without the voice service, tap again to send what was heard so far.
            onTalk = {
                when {
                    inCall && callState.phase == CallState.Phase.Speaking -> call.interrupt()
                    inCall -> call.close()
                    listen.listening -> speech.finish()
                    else -> startListening(hold = false)
                }
            },
            onHoldStart = { if (inCall) call.holdStarted() else startListening(hold = true) },
            onHoldEnd = { if (inCall) call.holdEnded() else if (listen.listening) speech.finish() },
            onTypeInstead = { speech.cancel(); sheet = VoiceSheet.Chat },
            onMute = { call.mute(!callState.muted) },
            onClose = ::close,
            onAction = ::runAction,
            onAdjustAccess = { sheet = VoiceSheet.Access },
            onToggleAccess = { source ->
                val data = when (source) {
                    AiSource.CurrentPage -> AiDataSource.CurrentPage
                    AiSource.OtherTabs -> AiDataSource.OtherTabs
                    AiSource.History -> AiDataSource.History
                    AiSource.Location -> AiDataSource.Location
                }
                switchboard.toggle(data); accessVersion++
            },
            onModeMenu = { sheet = VoiceSheet.Chat },
            onSettings = { sheet = VoiceSheet.Settings },
            onNav = { if (it == VoiceNav.Mylo) Unit else { speech.cancel(); call.close(); conversation.stop(); onNav(it) } },
        )
    }
    when (sheet) {
        VoiceSheet.Chat -> ChatSheet(messages, busy, connectedHost,
            // During a voice call the typed words join the same conversation and Mylo answers aloud.
            onSend = { if (inCall) call.type(it) else conversation.send(it) }, onStop = conversation::stop, onAnswerAsk = { conversation.answerAsk(it); accessVersion++ },
            onNewChat = conversation::clear, onAdjust = { sheet = VoiceSheet.Access }, onClose = { sheet = null })
        VoiceSheet.Access -> AccessSheet(switchboard, accessVersion, { source, grant ->
            switchboard.set(source, grant); accessVersion++
            // Location needs Android's (approximate) location permission; asked for when it is turned on.
            if (source == AiDataSource.Location && grant != AiGrant.Off && !DeviceContext.hasLocationPermission(context))
                askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }, onClose = { sheet = null })
        VoiceSheet.Translate -> TranslateSheet(page, onDone = { message -> sheet = null; page.note(message); close() }, onClose = { sheet = null })
        VoiceSheet.Settings -> VoiceSettingsSheet(preferences, connectedHost, onServiceChanged = { connectedHost = MyloAi.connectedHost(context) },
            onSample = { voice -> if (!inCall) call.sample(voice.id, VOICE_SAMPLE) }, sampling = inCall,
            onMoreSettings = { sheet = null; onMoreSettings() }, onClose = { sheet = null })
        null -> Unit
    }
    // Action Preview: a consequential step Mylo's voice asked for waits for the person.
    pendingTool?.let { tool ->
        AlertDialog(onDismissRequest = { call.answer(false) }, containerColor = SheetCard, titleContentColor = SheetInk, textContentColor = SheetMuted,
            modifier = Modifier.voiceAutomation().testTag("voice-action-preview"),
            title = { Text("Before Mylo continues") },
            text = { Column { Text(tool.summary, color = SheetInk, fontSize = 16.sp); tool.details.forEach { Text("• $it", Modifier.padding(top = 4.dp), fontSize = 13.sp) } } },
            confirmButton = { TextButton(onClick = { call.answer(true) }, modifier = Modifier.testTag("voice-action-allow")) { Text("Allow", color = SheetAccent) } },
            dismissButton = { TextButton(onClick = { call.answer(false) }, modifier = Modifier.testTag("voice-action-deny")) { Text("Don’t allow", color = SheetInk) } })
    }
}

/** What Mylo says when a voice is auditioned in settings. */
internal const val VOICE_SAMPLE = "Hi, I’m Mylo, your browsing buddy. Ask me anything about the page you’re on, and I’ll help you find your way."

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun VoiceSheetFrame(title: String, tag: String, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = SheetNight,
        contentColor = SheetInk, scrimColor = Color(0xAA030817), dragHandle = { BottomSheetDefaults.DragHandle(color = SheetLine) }) {
        Column(Modifier.fillMaxWidth().voiceAutomation().testTag(tag)) {
            Row(Modifier.padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                actions()
                IconButton(onClick = onClose, modifier = Modifier.testTag("$tag-close")) { Icon(Icons.Rounded.Close, "Close $title", tint = SheetMuted) }
            }
            content()
        }
    }
}

// Type instead ------------------------------------------------------------------------------------------

@Composable private fun ChatSheet(
    messages: List<ChatMessage>,
    busy: Boolean,
    connectedHost: String?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAnswerAsk: (Boolean) -> Unit,
    onNewChat: () -> Unit,
    onAdjust: () -> Unit,
    onClose: () -> Unit,
) {
    VoiceSheetFrame("Chat with Mylo", "voice-chat", onClose, actions = {
        if (messages.isNotEmpty()) TextButton(onClick = onNewChat, modifier = Modifier.testTag("voice-chat-new")) { Text("New chat", color = SheetAccent) }
    }) {
        Text(if (connectedHost != null) "Mylo AI · connected to $connectedHost" else "Mylo AI isn’t connected in this build",
            Modifier.padding(horizontal = 22.dp).testTag("voice-chat-status"), fontSize = 12.5.sp, color = if (connectedHost != null) OnMint else NoteInk)
        val list = rememberLazyListState()
        val last = messages.lastOrNull()
        LaunchedEffect(messages.size, last?.text?.length, last?.state) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 520.dp).padding(top = 10.dp), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.isEmpty()) item {
                Text("Ask Mylo anything about this page, or about the web in general. Mylo reads only what What Mylo can see allows.",
                    Modifier.padding(6.dp).testTag("voice-chat-empty"), color = SheetMuted, fontSize = 14.sp, lineHeight = 19.sp)
            }
            items(messages, key = { it.id }) { message -> ChatRow(message, onAnswerAsk, onAdjust) }
        }
        ChatInput(busy, onSend, onStop)
    }
}

@Composable private fun ChatRow(message: ChatMessage, onAnswerAsk: (Boolean) -> Unit, onAdjust: () -> Unit) {
    if (message.role == AiTurn.Role.User) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Text(message.text, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(UserBubble)
                .padding(horizontal = 14.dp, vertical = 9.dp).testTag("voice-chat-user"), color = UserInk, fontSize = 15.sp, lineHeight = 20.sp)
            if (message.state == ChatMessage.State.NotSent) Text("Not sent", Modifier.padding(top = 3.dp, end = 4.dp), color = SheetMuted, fontSize = 11.sp)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(SheetCard), contentAlignment = Alignment.Center) {
            Icon(HomeArt.MyloFace, null, tint = SheetAccent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.padding(start = 8.dp).widthIn(max = 310.dp).clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)).background(SheetCard)
            .padding(horizontal = 14.dp, vertical = 10.dp).testTag("voice-chat-mylo")) {
            val streaming = message.state == ChatMessage.State.Streaming
            if (message.text.isNotEmpty() || streaming) {
                Text(message.text.ifEmpty { "Thinking…" } + if (streaming && message.text.isNotEmpty()) " ▍" else "",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("voice-chat-answer"),
                    color = if (message.text.isEmpty()) SheetMuted else SheetInk, fontSize = 15.sp, lineHeight = 21.sp)
            }
            if (message.state == ChatMessage.State.Stopped) Text("Stopped", Modifier.padding(top = 4.dp), color = SheetMuted, fontSize = 11.5.sp)
            message.citations.forEach { Text("Source: ${it.title}", Modifier.padding(top = 4.dp), color = SheetAccent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            message.note?.let { note ->
                Text(note, Modifier.padding(top = if (message.text.isEmpty()) 0.dp else 8.dp).clip(RoundedCornerShape(10.dp)).background(NoteBack)
                    .padding(10.dp).testTag("voice-chat-note"), color = NoteInk, fontSize = 13.5.sp, lineHeight = 18.sp)
            }
            message.ask?.let { ask ->
                Text(ask.prompt, color = SheetInk, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.testTag("voice-chat-ask"))
                if (!ask.answered) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAnswerAsk(true) }, colors = ButtonDefaults.buttonColors(containerColor = SheetAccent, contentColor = UserInk),
                        modifier = Modifier.testTag("voice-ask-allow")) { Text("Allow once") }
                    OutlinedButton(onClick = { onAnswerAsk(false) }, modifier = Modifier.testTag("voice-ask-deny")) { Text("Not now", color = SheetInk) }
                }
                if (!ask.answered) TextButton(onClick = onAdjust) { Text("Change What Mylo can see", color = SheetAccent, fontSize = 13.sp) }
            }
            message.receipt?.let { ReceiptRow(it) }
        }
    }
}

/** The Privacy Receipt under an answer: what it used, what it didn't, and what was hidden first. */
@Composable private fun ReceiptRow(receipt: PrivacyReceipt) {
    var open by remember { mutableStateOf(false) }
    val used = receipt.used.map { it.label }
    val summary = (if (used.isEmpty()) "Nothing from your browser was shared" else used.joinToString(" · ") + " shared") +
        if (receipt.redactions > 0) " · ${receipt.redactions} hidden" else ""
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, SheetLine, RoundedCornerShape(10.dp))
        .clickable(onClickLabel = if (open) "Hide privacy receipt" else "Show privacy receipt") { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp)
        .testTag("voice-receipt")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = OnMint, modifier = Modifier.size(14.dp))
            Text("Privacy receipt: $summary", Modifier.padding(start = 6.dp), color = SheetMuted, fontSize = 12.sp, lineHeight = 15.sp)
        }
        if (open) receipt.lines().forEach { line ->
            Text("• $line", Modifier.padding(start = 20.dp, top = 3.dp), color = if (line.endsWith(" used")) SheetInk else SheetMuted, fontSize = 12.sp)
        }
    }
}

@Composable private fun ChatInput(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() { if (text.isNotBlank()) { onSend(text); text = "" } }
    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Color(0xFFF3F1FF)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (text.isEmpty()) Text("Message Mylo", color = Color(0xFF6A6E99), fontSize = 15.5.sp)
            BasicTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("voice-chat-input").semantics { contentDescription = "Message Mylo" },
                textStyle = TextStyle(color = Color(0xFF1E2150), fontSize = 15.5.sp), cursorBrush = SolidColor(Color(0xFF493B96)), maxLines = 4,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
        }
        Spacer(Modifier.width(8.dp))
        if (busy) FilledIconButton(onClick = onStop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = SheetCard),
            modifier = Modifier.size(48.dp).testTag("voice-chat-stop")) { Icon(Icons.Rounded.Stop, "Stop answer", tint = SheetInk) }
        else FilledIconButton(onClick = ::send, enabled = text.isNotBlank(), colors = IconButtonDefaults.filledIconButtonColors(containerColor = SheetAccent, contentColor = UserInk),
            modifier = Modifier.size(48.dp).testTag("voice-chat-send")) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
    }
}

// Translate this page -----------------------------------------------------------------------------------

/** Translate on the phone: detect the page's language, pick a target, agree to a one-time language pack, translate. */
@Composable private fun TranslateSheet(page: PageHelper, onDone: (String) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<String?>(null) }
    var detecting by remember { mutableStateOf(true) }
    var target by rememberSaveable { mutableStateOf(PageTranslator.deviceLanguage()) }
    var download by remember { mutableStateOf<Boolean?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val translated = page.translated
    LaunchedEffect(Unit) {
        source = page.sample()?.let { PageTranslator.detect(it) }
        detecting = false
        if (source == target) target = if (target == "en") "es" else "en"
    }
    LaunchedEffect(source, target) { source?.let { download = PageTranslator.needsDownload(it, target) } }
    VoiceSheetFrame("Translate this page", "voice-translate", onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
            Text("Translation happens on this phone: the page's text isn't sent anywhere.", color = SheetMuted, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            when {
                translated != null -> {
                    Text("This page is translated from ${PageTranslator.name(translated.first)} to ${PageTranslator.name(translated.second)}.", fontSize = 15.sp)
                    Button(onClick = { scope.launch { page.showOriginal(); onDone("Showing the original page.") } }, Modifier.padding(top = 12.dp).testTag("translate-original"),
                        colors = ButtonDefaults.buttonColors(containerColor = SheetAccent, contentColor = UserInk)) { Text("Show original") }
                }
                detecting -> Text("Finding the page’s language…", fontSize = 15.sp)
                source == null -> Text("Mylo couldn’t tell which language this page is in, so it can’t translate it.", Modifier.testTag("translate-unknown"), fontSize = 15.sp)
                else -> {
                    Text("This page is in ${PageTranslator.name(source!!)}. Translate to:", Modifier.testTag("translate-source"), fontSize = 15.sp)
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageTranslator.choices.filter { it != source }.forEach { code ->
                            val selected = code == target
                            Box(Modifier.clip(CircleShape).background(if (selected) SheetAccent else SheetCard).border(1.dp, if (selected) SheetAccent else SheetLine, CircleShape)
                                .selectable(selected, role = Role.RadioButton) { target = code }.padding(horizontal = 14.dp, vertical = 7.dp).testTag("translate-to-$code")) {
                                Text(PageTranslator.name(code), color = if (selected) UserInk else SheetInk, fontSize = 13.sp)
                            }
                        }
                    }
                    if (download == true) Text("The first time, Mylo downloads the ${PageTranslator.name(source!!)}–${PageTranslator.name(target)} language pack (about 30 MB each) from Google. Pages are still translated on the phone.",
                        Modifier.padding(top = 10.dp).testTag("translate-download-note"), color = NoteInk, fontSize = 12.5.sp, lineHeight = 17.sp)
                    failed?.let { Text(it, Modifier.padding(top = 10.dp), color = NoteInk, fontSize = 13.sp) }
                    progress?.let { Text(it, Modifier.padding(top = 10.dp).testTag("translate-progress"), color = SheetMuted, fontSize = 13.sp) }
                    Button(onClick = {
                        val from = source ?: return@Button
                        progress = if (download == true) "Downloading the language pack…" else "Translating…"; failed = null
                        scope.launch {
                            val count = runCatching { page.translate(from, target) { done, total -> progress = "Translating $done of $total…" } }
                                .getOrElse { failed = "The translation couldn’t finish. Check your connection for the language pack and try again."; progress = null; return@launch }
                            if (count > 0) onDone("Translated from ${PageTranslator.name(from)} to ${PageTranslator.name(target)} on this phone. Tap Translate this page again for the original.")
                            else { failed = "There's no text on this page to translate."; progress = null }
                        }
                    }, Modifier.padding(top = 14.dp).testTag("translate-go"), enabled = progress == null,
                        colors = ButtonDefaults.buttonColors(containerColor = SheetAccent, contentColor = UserInk)) {
                        Text(if (download == true) "Download and translate" else "Translate")
                    }
                }
            }
        }
    }
}

// What Mylo can see -------------------------------------------------------------------------------------

@Composable private fun AccessSheet(switchboard: AiSwitchboard, version: Int, onSet: (AiDataSource, AiGrant) -> Unit, onClose: () -> Unit) {
    VoiceSheetFrame("What Mylo can see", "voice-access", onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).testTag("voice-access-list")) {
            Text("Mylo reads these only when you ask something, and only if they're on. “Allow once” is used up by your next question.",
                color = SheetMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            AiDataSource.entries.forEach { source ->
                key(source, version) { AccessRow(source, switchboard.grant(source)) { onSet(source, it) } }
                HorizontalDivider(color = SheetLine.copy(alpha = .6f))
            }
            Text("Card numbers, ID numbers, bank accounts and secrets in links are always hidden before anything leaves your phone. After each answer, a privacy receipt shows what was used.",
                Modifier.padding(vertical = 14.dp), color = SheetMuted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
}

@Composable private fun AccessRow(source: AiDataSource, grant: AiGrant, onSet: (AiGrant) -> Unit) {
    Column(Modifier.padding(vertical = 12.dp).testTag("access-${source.name.lowercase()}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(source.label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Text(source.explanation, Modifier.padding(top = 2.dp), color = SheetMuted, fontSize = 12.5.sp, lineHeight = 16.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AiGrant.Off to "Off", AiGrant.Once to "Allow once", AiGrant.Always to "Always").forEach { (value, label) ->
                val enabled = value != AiGrant.Always || source.allowsAlways
                val selected = grant == value
                Box(Modifier.clip(CircleShape).background(if (selected) SheetAccent else SheetCard)
                    .border(1.dp, if (selected) SheetAccent else SheetLine, CircleShape)
                    .selectable(selected, enabled = enabled, role = Role.RadioButton) { onSet(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp).testTag("access-${source.name.lowercase()}-${value.name.lowercase()}")) {
                    Text(label, color = when { selected -> UserInk; enabled -> SheetInk; else -> SheetMuted.copy(alpha = .45f) }, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (!source.allowsAlways) Text("Shared one question at a time only.", Modifier.padding(top = 4.dp), color = SheetMuted, fontSize = 11.5.sp)
    }
}

// Settings ----------------------------------------------------------------------------------------------

@Composable private fun VoiceSettingsSheet(preferences: AiPreferences, connectedHost: String?, onServiceChanged: () -> Unit, onSample: (MyloVoice) -> Unit,
    sampling: Boolean, onMoreSettings: () -> Unit, onClose: () -> Unit) {
    var voice by remember { mutableStateOf(preferences.voice) }
    VoiceSheetFrame("Voice Mode settings", "voice-settings", onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Text("Mylo’s voice", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            MyloVoice.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(SheetCard)
                    .selectable(voice == option, role = Role.RadioButton) { voice = option; preferences.voice = option }
                    .padding(horizontal = 14.dp, vertical = 12.dp).testTag("voice-option-${option.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(option.description, color = SheetMuted, fontSize = 12.5.sp)
                    }
                    TextButton(onClick = { onSample(option) }, enabled = connectedHost != null && !sampling,
                        modifier = Modifier.testTag("voice-sample-${option.id}")) { Text("Play sample", color = if (connectedHost != null && !sampling) SheetAccent else SheetMuted) }
                    if (voice == option) Icon(Icons.Rounded.Check, "Selected", tint = OnMint)
                }
            }
            Text(if (connectedHost != null) "Mylo speaks with the voice you pick, through the Mylo AI voice service. Samples play the real voice."
                else "Mylo answers in text until the Mylo AI voice service is connected; then you'll hear the voice you pick, and can play samples here.",
                Modifier.padding(top = 8.dp), color = SheetMuted, fontSize = 12.5.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(18.dp))
            MemoryEditor()
            Spacer(Modifier.height(18.dp))
            Text("Mylo AI service", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(if (connectedHost != null) "Connected to $connectedHost" else "Not connected in this build", Modifier.padding(top = 4.dp).testTag("voice-service-status"),
                color = if (connectedHost != null) OnMint else NoteInk, fontSize = 14.sp)
            Text("Mylo never keeps AI provider keys in the app. It talks only to the Mylo AI service, which holds them and gives voice conversations short-lived passes.",
                Modifier.padding(top = 4.dp), color = SheetMuted, fontSize = 12.5.sp, lineHeight = 17.sp)
            if (BuildConfig.DEBUG) TestServiceEditor(preferences, onServiceChanged)
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onMoreSettings, contentPadding = PaddingValues(0.dp)) { Text("More Mylo settings", color = SheetAccent, fontSize = 15.sp) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Saved Mylo Memory: what the person asked Mylo to remember, on this phone only; used when allowed. */
@Composable private fun MemoryEditor() {
    val context = LocalContext.current
    val memory = remember { MyloMemory(context) }
    var items by remember { mutableStateOf(memory.items) }
    var draft by rememberSaveable { mutableStateOf("") }
    Text("Saved Mylo Memory", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text("Things Mylo can remember for you, kept on this phone. Mylo reads them only when Saved Mylo Memory is on in What Mylo can see.",
        Modifier.padding(top = 4.dp), color = SheetMuted, fontSize = 12.5.sp, lineHeight = 17.sp)
    items.forEach { item ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(SheetCard).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(item, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 14.sp)
            IconButton(onClick = { memory.remove(item); items = memory.items }, modifier = Modifier.testTag("memory-remove")) {
                Icon(Icons.Rounded.Close, "Forget “$item”", tint = SheetMuted)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = SheetInk, unfocusedTextColor = SheetInk, focusedBorderColor = SheetAccent,
            unfocusedBorderColor = SheetLine, focusedLabelColor = SheetAccent, unfocusedLabelColor = SheetMuted)
        OutlinedTextField(draft, { draft = it.take(MyloMemory.MAX_CHARS) }, Modifier.weight(1f).testTag("memory-input"), label = { Text("Something to remember") },
            singleLine = true, colors = colors)
        TextButton(onClick = { if (memory.add(draft)) { draft = ""; items = memory.items } }, enabled = draft.isNotBlank(),
            modifier = Modifier.testTag("memory-add")) { Text("Remember", color = if (draft.isNotBlank()) SheetAccent else SheetMuted) }
    }
}

/** Debug builds only: point this phone at a test Mylo AI service (for example the reference gateway). */
@Composable private fun TestServiceEditor(preferences: AiPreferences, onChanged: () -> Unit) {
    var url by remember { mutableStateOf(preferences.testService?.baseUrl.orEmpty()) }
    var token by remember { mutableStateOf(preferences.testService?.accessToken.orEmpty()) }
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, SheetLine, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text("Test service (debug builds only)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = SheetInk, unfocusedTextColor = SheetInk, focusedBorderColor = SheetAccent, unfocusedBorderColor = SheetLine,
            focusedLabelColor = SheetAccent, unfocusedLabelColor = SheetMuted)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("voice-test-url"), label = { Text("Service address (https://…)") }, singleLine = true, colors = colors)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().padding(top = 6.dp).testTag("voice-test-token"), label = { Text("Access token") }, singleLine = true, colors = colors)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { preferences.setTestService(AiEndpoint(url, token.ifBlank { null })); onChanged() }, enabled = url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SheetAccent, contentColor = UserInk)) { Text("Save") }
            OutlinedButton(onClick = { url = ""; token = ""; preferences.setTestService(null); onChanged() }) { Text("Clear", color = SheetInk) }
        }
    }
}

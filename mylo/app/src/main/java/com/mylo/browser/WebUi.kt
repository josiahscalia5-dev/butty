package com.mylo.browser

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mylo.browser.web.DownloadPrompt
import com.mylo.browser.web.EngineMode
import com.mylo.browser.web.ExternalApps
import com.mylo.browser.web.ExternalPrompt
import com.mylo.browser.web.FullscreenRequest
import com.mylo.browser.web.JsDialogKind
import com.mylo.browser.web.JsDialogPrompt
import com.mylo.browser.web.Origins
import com.mylo.browser.web.PageNotice
import com.mylo.browser.web.PermissionPrompt
import com.mylo.browser.web.SiteDecision
import com.mylo.browser.web.SitePermission
import com.mylo.browser.web.TabEngine
import com.mylo.browser.web.isGranted
import com.mylo.browser.web.WebPrompt
import kotlinx.coroutines.delay

private val WebPanel = Color(0xFF162345)
private val WebAccent = Color(0xFFCEC5FF)
private val WebMuted = Color(0xFFAEB9DB)
private val WebText = Color(0xFFF6F4FF)
private val WebSheet = Color(0xFF101B35)

/**
 * Answers what the visible page asks for: site permissions (then Android's own permission), JavaScript
 * dialogs, downloads and links to other apps. One question at a time; nothing is granted without a tap.
 */
@Composable fun WebPromptHost(engine: TabEngine) {
    val context = LocalContext.current
    var awaitingAndroid by remember { mutableStateOf<Pair<WebPrompt, Boolean>?>(null) }

    fun androidDeclined(prompt: WebPrompt, what: String) {
        engine.page(prompt.tabId).notice = PageNotice.Info("Android hasn't allowed Mylo to use $what. You can allow it in App settings, then try again.")
    }
    val androidPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val (prompt, remember) = awaitingAndroid ?: return@rememberLauncherForActivityResult
        awaitingAndroid = null
        when (prompt) {
            is PermissionPrompt -> {
                val granted = prompt.androidSatisfied { context.isGranted(it) }
                prompt.answer(allow = true, remember = remember, androidGranted = granted)
                if (!granted) androidDeclined(prompt, permissionPhrase(prompt.permissions))
            }
            is DownloadPrompt -> {
                val granted = context.isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                prompt.answer(granted)
                if (!granted) androidDeclined(prompt, "storage to save downloads")
            }
            else -> Unit
        }
        engine.answered(prompt)
    }

    fun allowSite(prompt: PermissionPrompt, remember: Boolean) {
        if (prompt.androidSatisfied { context.isGranted(it) }) {
            prompt.answer(allow = true, remember = remember)
            engine.answered(prompt)
        } else {
            awaitingAndroid = prompt to remember
            androidPermissions.launch(prompt.androidPermissions.toTypedArray())
        }
    }

    val prompt = engine.prompts.firstOrNull { it.tabId == engine.visibleTab } ?: return
    if (awaitingAndroid?.first === prompt) return
    when (prompt) {
        is PermissionPrompt ->
            // A choice the user saved for this site is reused; Android may still need to ask.
            if (prompt.preApproved) LaunchedEffect(prompt) { allowSite(prompt, remember = false) }
            else PermissionDialog(prompt, sessionOnly = engine.mode == EngineMode.Private, onAllow = { allowSite(prompt, it) }, onBlock = { remember ->
                prompt.answer(allow = false, remember = remember)
                engine.answered(prompt)
            })
        is JsDialogPrompt -> JsDialog(prompt) { engine.answered(prompt) }
        is DownloadPrompt -> DownloadDialog(prompt) { download ->
            if (download && prompt.needsStoragePermission && !context.isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                awaitingAndroid = prompt to false
                androidPermissions.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            } else {
                prompt.answer(download)
                engine.answered(prompt)
            }
        }
        is ExternalPrompt -> ExternalDialog(prompt) { open ->
            prompt.answer(open)
            engine.answered(prompt)
        }
    }
}

private fun permissionPhrase(permissions: List<SitePermission>): String = permissions.joinToString(" and ") {
    when (it) {
        SitePermission.Camera -> "your camera"
        SitePermission.Microphone -> "your microphone"
        SitePermission.Location -> "your location"
        SitePermission.ProtectedMedia -> "protected content playback"
        SitePermission.Popups -> "pop-ups"
    }
}

@Composable private fun PermissionDialog(prompt: PermissionPrompt, sessionOnly: Boolean, onAllow: (Boolean) -> Unit, onBlock: (Boolean) -> Unit) {
    var remember by remember(prompt) { mutableStateOf(false) }
    val canRemember = prompt.origin?.let(Origins::rememberable) == true
    AlertDialog(
        onDismissRequest = { onBlock(false) },
        modifier = Modifier.testTag("site-permission-prompt"),
        title = { Text("Allow ${prompt.host} to use ${permissionPhrase(prompt.permissions)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (sessionOnly) "Only allow sites you trust. In Private Mode, permissions end when the session burns." else "Only allow sites you trust. You can change this later in Site settings.", color = WebMuted, fontSize = 14.sp)
                if (canRemember) CheckRow(if (sessionOnly) "Remember for this private session" else "Remember my choice for this site", remember) { remember = it }
            }
        },
        confirmButton = { TextButton(onClick = { onAllow(remember) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { onBlock(remember) }) { Text("Don't allow") } },
    )
}

@Composable private fun JsDialog(prompt: JsDialogPrompt, onDone: () -> Unit) {
    var text by remember(prompt) { mutableStateOf(prompt.defaultValue.orEmpty()) }
    var suppress by remember(prompt) { mutableStateOf(false) }
    fun finish(ok: Boolean) { prompt.answer(ok, text, suppress); onDone() }
    val leaving = prompt.kind == JsDialogKind.BeforeUnload
    AlertDialog(
        onDismissRequest = { finish(prompt.kind == JsDialogKind.Alert) },
        modifier = Modifier.testTag("page-dialog"),
        title = { Text(if (leaving) "Leave this page?" else "${prompt.host} says", fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (leaving) "Changes you made may not be saved." else prompt.message)
                if (prompt.kind == JsDialogKind.Prompt) OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (prompt.offerSuppress) CheckRow("Don't let this page show more dialogs", suppress) { suppress = it }
            }
        },
        confirmButton = { TextButton(onClick = { finish(true) }) { Text(if (leaving) "Leave" else "OK") } },
        dismissButton = if (prompt.kind == JsDialogKind.Alert) null else {
            { TextButton(onClick = { finish(false) }) { Text(if (leaving) "Stay" else "Cancel") } }
        },
    )
}

@Composable private fun DownloadDialog(prompt: DownloadPrompt, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        modifier = Modifier.testTag("download-prompt"),
        title = { Text("Download file?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(prompt.fileName, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(readableSize(prompt.sizeBytes), "from ${prompt.host}").joinToString(" · "), color = WebMuted, fontSize = 14.sp)
                Text("It will be saved to your Downloads folder.", color = WebMuted, fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
}

private fun readableSize(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
}

@Composable private fun ExternalDialog(prompt: ExternalPrompt, onAnswer: (Boolean) -> Unit) {
    val app = prompt.target.packageName?.let { " ($it)" }.orEmpty()
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        modifier = Modifier.testTag("external-app-prompt"),
        title = { Text("Open another app?") },
        text = {
            Text("${prompt.host} wants to open ${prompt.target.kind.label}$app outside Mylo. Only continue if you trust this site.")
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
}

@Composable private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = WebAccent))
        Text(label, fontSize = 14.sp)
    }
}

/** A short, dismissible message under the address bar (blocked pop-up, refused page, download status). */
@Composable fun PageNoticeBar(notice: PageNotice, onDismiss: () -> Unit, onAllowPopups: () -> Unit, onOtherBrowser: () -> Unit) {
    val (message, action) = when (notice) {
        is PageNotice.PopupBlocked -> "Pop-up blocked on ${notice.host}." to ("Always allow" to onAllowPopups)
        is PageNotice.Refused -> notice.message to ("Open in another browser" to onOtherBrowser)
        is PageNotice.Info -> notice.message to (if (notice.offerOtherBrowser) "Open in another browser" to onOtherBrowser else null)
    }
    if (notice is PageNotice.Info && !notice.offerOtherBrowser) {
        LaunchedEffect(notice) { delay(8_000); onDismiss() }
    }
    Surface(color = WebPanel, contentColor = WebText, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).testTag("page-notice")) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (notice is PageNotice.Refused) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, tint = WebAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(message, fontSize = 13.sp, lineHeight = 17.sp)
                action?.let { (label, run) ->
                    TextButton(onClick = run, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        if (run === onOtherBrowser) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                        Text(label, color = WebAccent, fontSize = 13.sp)
                    }
                }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss message", tint = WebMuted) }
        }
    }
}

/** Shown instead of a page whose renderer stopped; the rest of Mylo keeps running. */
@Composable fun CrashedPage(onReload: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = WebAccent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.size(12.dp))
        Text("This page stopped working", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(6.dp))
        Text("Mylo closed it safely. Reload to try again.", color = WebMuted)
        Spacer(Modifier.size(16.dp))
        Button(onClick = onReload) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Reload") }
    }
}

/** Starts the system file picker for a page's file input and always reports back to the page. */
@Composable fun FileChooserHost(engine: TabEngine) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        engine.finishFileChooser(FileChoices.parse(result.resultCode, result.data))
    }
    val request = engine.fileChooser
    LaunchedEffect(request) {
        if (request == null || request.launched) return@LaunchedEffect
        request.launched = true
        runCatching { picker.launch(FileChoices.intent(request.params)) }.onFailure {
            engine.finishFileChooser(null)
            engine.page(request.tabId).notice = PageNotice.Info("No app on this device can pick files.")
        }
    }
}

object FileChoices {
    /** A document picker for the input's accepted types (MIME types or extensions such as .pdf). */
    fun intent(params: WebChromeClient.FileChooserParams): Intent {
        val types = params.acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.mapNotNull {
            when {
                it.contains('/') -> it
                it.startsWith('.') -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1))
                else -> null
            }
        }.distinct()
        return Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = types.singleOrNull() ?: "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    fun parse(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }.filterNotNull().toTypedArray().takeIf { it.isNotEmpty() }
        return data.data?.let { arrayOf(it) }
    }
}

/** Full-screen video: the page's video view over everything, system bars hidden, Back exits. */
@Composable fun FullscreenHost(request: FullscreenRequest, onExit: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(request) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    BackHandler(onBack = onExit)
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                isClickable = true
                (request.view.parent as? ViewGroup)?.removeView(request.view)
                addView(request.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        onRelease = { it.removeAllViews() },
        modifier = Modifier.fillMaxSize().background(Color.Black).testTag("fullscreen-video"),
    )
}

/** Per-site choices Mylo remembered for the current page's site, with a way to reset them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SiteSettingsSheet(engine: TabEngine, pageUrl: String, onClose: () -> Unit) {
    val origin = Origins.of(pageUrl)
    var choices by remember(origin) { mutableStateOf(origin?.let(engine.permissions::forOrigin).orEmpty()) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = WebSheet, contentColor = WebText) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("site-settings"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Site settings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(origin ?: "This page", color = WebMuted, fontSize = 14.sp)
            if (origin == null || !Origins.rememberable(origin)) {
                Text("Mylo asks every time on this page and doesn't remember choices for sites without a secure (https) connection.", color = WebMuted, fontSize = 14.sp)
            }
            HorizontalDivider(color = WebPanel)
            SitePermission.entries.forEach { permission ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(permission.label, Modifier.weight(1f))
                    Text(when (choices[permission]) { SiteDecision.Allow -> "Allowed"; SiteDecision.Block -> "Blocked"; null -> "Ask" }, color = WebMuted)
                }
            }
            if (origin != null && choices.isNotEmpty()) {
                TextButton(onClick = { engine.permissions.clear(origin); choices = emptyMap() }) { Text("Reset permissions for this site", color = WebAccent) }
            }
            Text("Mylo never grants camera, microphone or location without asking you first. Android may also ask.", color = WebMuted, fontSize = 13.sp)
        }
    }
}

/** Offers the page to whichever other browser the user picks. */
fun openInOtherBrowser(context: Context, url: String, onUnavailable: () -> Unit) {
    if (!(url.startsWith("https://") || url.startsWith("http://")) || !ExternalApps.openInOtherBrowser(context, url)) onUnavailable()
}

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}


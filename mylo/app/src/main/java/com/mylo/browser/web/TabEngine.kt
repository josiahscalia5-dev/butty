package com.mylo.browser.web

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mylo.browser.BrowserTab
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/** Observable state of one tab's page, updated from WebView callbacks on the main thread. */
class PageState(val tabId: Long) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var favicon by mutableStateOf<Bitmap?>(null)
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** The page's renderer process ended; the tab needs a reload. */
    var crashed by mutableStateOf(false)
    var notice by mutableStateOf<PageNotice?>(null)
    /** Tracker requests blocked on the current page. */
    var trackersBlocked by mutableIntStateOf(0)
    internal var loadingUrl: String? = null
    internal var dialogsShown = 0
    internal var dialogsSuppressed = false
}

sealed interface PageNotice {
    data class PopupBlocked(val origin: String?, val host: String) : PageNotice
    /** The site refuses Android WebView by its own policy; Mylo offers another browser. */
    data class Refused(val message: String) : PageNotice
    data class Info(val message: String, val offerOtherBrowser: Boolean = false) : PageNotice
}

/** Something a page asks the user. Each is answered exactly once. */
sealed interface WebPrompt { val tabId: Long }

class PermissionPrompt internal constructor(
    override val tabId: Long,
    val origin: String?,
    val host: String,
    val permissions: List<SitePermission>,
    /** The site was allowed before; only Android's own permission may still be needed. */
    val preApproved: Boolean,
    internal val key: Any,
    private val store: SitePermissionStore,
    private val onGrant: () -> Unit,
    private val onDeny: () -> Unit,
) : WebPrompt {
    private var answered = false

    /** Android runtime permissions the granted site permissions need. */
    val androidPermissions: List<String> get() = permissions.flatMap {
        when (it) {
            SitePermission.Camera -> listOf(Manifest.permission.CAMERA)
            SitePermission.Microphone -> listOf(Manifest.permission.RECORD_AUDIO)
            SitePermission.Location -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> emptyList()
        }
    }

    /** Whether Android already lets Mylo use what this request needs (either precision for location). */
    fun androidSatisfied(isGranted: (String) -> Boolean): Boolean = permissions.all {
        when (it) {
            SitePermission.Camera -> isGranted(Manifest.permission.CAMERA)
            SitePermission.Microphone -> isGranted(Manifest.permission.RECORD_AUDIO)
            SitePermission.Location -> isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> true
        }
    }

    /** [allow]: the user's choice. [remember]: keep it for this site. [androidGranted]: Android's answer. */
    fun answer(allow: Boolean, remember: Boolean, androidGranted: Boolean = true) {
        if (answered) return
        answered = true
        if (remember) permissions.forEach { store.remember(origin, it, if (allow) SiteDecision.Allow else SiteDecision.Block) }
        if (allow && androidGranted) onGrant() else onDeny()
    }
}

enum class JsDialogKind { Alert, Confirm, Prompt, BeforeUnload }

class JsDialogPrompt internal constructor(
    override val tabId: Long,
    val host: String,
    val kind: JsDialogKind,
    val message: String,
    val defaultValue: String?,
    val offerSuppress: Boolean,
    private val onAnswer: (ok: Boolean, text: String?, suppress: Boolean) -> Unit,
) : WebPrompt {
    private var answered = false
    fun answer(ok: Boolean, text: String? = null, suppress: Boolean = false) { if (!answered) { answered = true; onAnswer(ok, text, suppress) } }
}

class DownloadPrompt internal constructor(
    override val tabId: Long,
    val fileName: String,
    val sizeBytes: Long,
    val host: String,
    /** Android 9 needs storage permission to save into Downloads. */
    val needsStoragePermission: Boolean,
    private val onAnswer: (Boolean) -> Unit,
) : WebPrompt {
    private var answered = false
    fun answer(download: Boolean) { if (!answered) { answered = true; onAnswer(download) } }
}

class ExternalPrompt internal constructor(
    override val tabId: Long,
    val host: String,
    val target: ExternalTarget,
    private val onAnswer: (Boolean) -> Unit,
) : WebPrompt {
    private var answered = false
    fun answer(open: Boolean) { if (!answered) { answered = true; onAnswer(open) } }
}

class FileChooserRequest internal constructor(
    val tabId: Long,
    val params: WebChromeClient.FileChooserParams,
    private val callback: ValueCallback<Array<Uri>>,
) {
    private var done = false
    /** The system picker was started for this request (it survives activity recreation). */
    var launched = false
    /** Always called exactly once (null when cancelled), or the page's file inputs stop working. */
    fun complete(uris: Array<Uri>?) { if (!done) { done = true; callback.onReceiveValue(uris) } }
}

class FullscreenRequest internal constructor(val tabId: Long, val view: View, internal val callback: WebChromeClient.CustomViewCallback)

sealed interface EngineEvent {
    /** Show this tab (a page opened a new window, or a pop-up closed and its opener returns). */
    data class ShowTab(val tabId: Long) : EngineEvent
    data object ShowHome : EngineEvent
}

/**
 * Mylo's browser engine: one long-lived [WebView] per tab, configured once for every site and every search
 * provider. Pages may open windows (target=_blank, window.open, sign-in pop-ups), which become Mylo tabs
 * linked to their opener and close back to it. Sensitive requests become [prompts]; nothing sensitive is
 * granted without the user. The same engine runs normal browsing and Private Mode ([mode]); with [trackers]
 * it blocks third-party trackers for every page.
 */
class TabEngine(
    private val app: Application,
    private val store: TabHost,
    val mode: EngineMode = EngineMode.Normal,
    val trackers: TrackerBlocker? = null,
    /** Private Mode: site permissions kept in memory for the session only. */
    sessionPermissions: SessionValues? = null,
) {
    private class LiveTab(val id: Long, val webView: WebView, val context: MutableContextWrapper, var openerId: Long?)

    private val live = LinkedHashMap<Long, LiveTab>(16, .75f, true)
    private val pages = mutableMapOf<Long, PageState>()
    private val savedStates = mutableMapOf<Long, Bundle>()
    private val pending = mutableMapOf<Long, String>()
    private var activity: Activity? = null

    val permissions = if (sessionPermissions != null) SitePermissionStore(sessionPermissions) else SitePermissionStore.from(app)
    /** Tracker requests blocked by this engine since it started or was last reset. */
    var trackersBlocked by mutableIntStateOf(0)
        private set
    /** Each tab's current page, for decisions WebView asks off the main thread. */
    private val pageUrls = ConcurrentHashMap<Long, String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    val prompts = mutableStateListOf<WebPrompt>()
    var fileChooser by mutableStateOf<FileChooserRequest?>(null)
        private set
    var fullscreen by mutableStateOf<FullscreenRequest?>(null)
        private set
    var visibleTab by mutableStateOf<Long?>(null)
        private set

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    fun page(tabId: Long): PageState = pages.getOrPut(tabId) { PageState(tabId) }

    fun isLive(tabId: Long) = live.containsKey(tabId)

    /** WebViews keep a wrapper whose base is the current activity, so dialogs and pickers have a window. */
    fun attach(activity: Activity) {
        this.activity = activity
        live.values.forEach { it.context.baseContext = activity }
    }

    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        this.activity = null
        live.values.forEach { it.context.baseContext = app }
    }

    /** The tab's WebView, created on first use and restored or loaded. */
    fun webView(tab: BrowserTab): WebView {
        live[tab.id]?.let { return it.webView }
        val created = create(tab.id, tab.openerId)
        val page = page(tab.id)
        page.crashed = false
        val target = pending.remove(tab.id)
        val restored = savedStates.remove(tab.id)?.let { created.webView.restoreState(it) }
        when {
            target != null -> created.webView.loadUrl(target)
            restored == null && tab.url.isNotBlank() -> created.webView.loadUrl(tab.url)
        }
        trim(keep = tab.id)
        return created.webView
    }

    /** Loads [url] in the tab: at once when its WebView is alive, otherwise when it is next shown. */
    fun load(tabId: Long, url: String) {
        page(tabId).apply { error = null; notice = null }
        val view = live[tabId]?.webView
        if (view == null) { pending[tabId] = url; return }
        view.stopLoading()
        view.loadUrl(url)
    }

    fun webViewIfLive(tabId: Long): WebView? = live[tabId]?.webView

    /** Back within the tab, or (for a pop-up with no history) close it and return to its opener. */
    fun back(tabId: Long): Boolean {
        val tab = live[tabId] ?: return false
        if (tab.webView.canGoBack()) { tab.webView.goBack(); return true }
        val opener = tab.openerId?.takeIf { live.containsKey(it) } ?: return false
        closeTab(tabId)
        _events.tryEmit(EngineEvent.ShowTab(opener))
        return true
    }

    /** Stops the tab's page load (the toolbar's Stop while a page is loading). */
    fun stop(tabId: Long) {
        live[tabId]?.webView?.stopLoading()
    }

    fun reload(tab: BrowserTab) {
        val page = page(tab.id)
        if (page.crashed || !live.containsKey(tab.id)) {
            page.crashed = false
            pending[tab.id] = page.url.ifBlank { tab.url }
        } else {
            live[tab.id]?.webView?.reload()
        }
    }

    /** Closes the tab and its WebView (a page's window.closed becomes true). */
    fun closeTab(tabId: Long) {
        store.closeTab(tabId)
        release(tabId)
    }

    fun release(tabId: Long) {
        live.remove(tabId)?.let(::destroy)
        live.values.filter { it.openerId == tabId }.forEach { it.openerId = null }
        pages.remove(tabId)
        pageUrls.remove(tabId)
        savedStates.remove(tabId)
        pending.remove(tabId)
        prompts.filter { it.tabId == tabId }.forEach(::dismiss)
        if (fileChooser?.tabId == tabId) finishFileChooser(null)
        if (fullscreen?.tabId == tabId) exitFullscreen()
        if (visibleTab == tabId) visibleTab = null
    }

    fun setVisible(tabId: Long?) {
        visibleTab = tabId
        live.values.forEach { if (it.id == tabId) it.webView.onResume() else it.webView.onPause() }
        // Background tabs don't keep dialogs or permission requests open.
        prompts.filter { it.tabId != tabId }.forEach(::dismiss)
    }

    fun onActivityPause() {
        live.values.forEach { it.webView.onPause() }
        CookieManager.getInstance().flush()
    }

    fun onActivityResume() { visibleTab?.let { live[it]?.webView?.onResume() } }

    fun destroyAll() {
        live.values.toList().forEach(::destroy)
        live.clear()
    }

    /**
     * Private Mode's Burn: every tab, page state, saved navigation, pending prompt and file request is
     * dropped, and the tracker counts start again. Cookies and storage are cleared by the session.
     */
    fun burn() {
        prompts.toList().forEach(::dismiss)
        finishFileChooser(null)
        exitFullscreen()
        live.keys.toList().forEach(::release)
        live.clear(); pages.clear(); savedStates.clear(); pending.clear(); pageUrls.clear()
        visibleTab = null
        trackers?.reset()
        trackersBlocked = 0
    }

    /** Counts are reset when the user turns blocking off and on again, never silently. */
    fun resetTrackerCount() { trackers?.reset(); trackersBlocked = 0 }

    fun answered(prompt: WebPrompt) { prompts.remove(prompt) }

    fun finishFileChooser(uris: Array<Uri>?) {
        fileChooser?.complete(uris)
        fileChooser = null
    }

    fun exitFullscreen() {
        val request = fullscreen ?: return
        fullscreen = null
        runCatching { request.callback.onCustomViewHidden() }
    }

    fun dismissNotice(tabId: Long) { page(tabId).notice = null }

    fun allowPopups(origin: String?) = permissions.remember(origin, SitePermission.Popups, SiteDecision.Allow)

    // --- creation and memory --------------------------------------------------------------------------

    private fun create(tabId: Long, openerId: Long?): LiveTab {
        val context = MutableContextWrapper(activity ?: app)
        val view = WebView(context).apply {
            id = View.generateViewId()
            contentDescription = "Mylo web page"
            setBackgroundColor(android.graphics.Color.WHITE)
            configure(this)
            webViewClient = Client(tabId)
            webChromeClient = Chrome(tabId)
            setDownloadListener(if (mode == EngineMode.Private) DownloadListener { _, _, _, _, _ ->
                page(tabId).notice = PageNotice.Info("Downloads are off in Private Mode, so nothing is left on this device. Use a regular tab to download.")
            } else Downloads(tabId))
        }
        return LiveTab(tabId, view, context, openerId).also { live[tabId] = it }
    }

    /** One configuration for every site: full modern-web support with Mylo's safety limits. */
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // window.open and target=_blank reach onCreateWindow; pop-ups without a tap stay blocked there.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = true
            // Never relaxed for compatibility: no file/content access, no mixed content, Safe Browsing on.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }
        // Normal browsing matches Chrome's defaults so sign-in, SSO and embedded services work: first- and
        // third-party cookies are accepted. Private Mode keeps first-party cookies for the session only (its
        // own process and data directory) and blocks third-party cookies.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, mode == EngineMode.Normal)
        }
    }

    private fun destroy(tab: LiveTab) {
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
    }

    /** Keeps at most [MAX_LIVE] WebViews; older tabs are saved and restored when shown again. */
    private fun trim(keep: Long) {
        if (live.size <= MAX_LIVE) return
        val linked = live.values.mapNotNull { it.openerId }.toSet() + live.values.filter { it.openerId != null }.map { it.id }
        for (tab in live.values.toList()) {
            if (live.size <= MAX_LIVE) break
            if (tab.id == keep || tab.id == visibleTab || tab.id in linked) continue
            savedStates[tab.id] = Bundle().also { tab.webView.saveState(it) }
            live.remove(tab.id)
            destroy(tab)
        }
    }

    private fun dismiss(prompt: WebPrompt) {
        when (prompt) {
            is PermissionPrompt -> prompt.answer(allow = false, remember = false)
            is JsDialogPrompt -> prompt.answer(ok = false)
            is DownloadPrompt -> prompt.answer(false)
            is ExternalPrompt -> prompt.answer(false)
        }
        prompts.remove(prompt)
    }

    private fun tabIdOf(view: WebView): Long? = live.values.firstOrNull { it.webView === view }?.id

    // --- navigation -----------------------------------------------------------------------------------

    private inner class Client(private val tabId: Long) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (val decision = WebPolicy.decide(url, request.isForMainFrame, request.hasGesture(), request.isRedirect)) {
                NavigationDecision.LoadInMylo -> false
                is NavigationDecision.OpenExternal -> { openExternal(tabId, view.url, decision.target); true }
                is NavigationDecision.Blocked -> true
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val blocker = trackers ?: return null
            blocker.shouldBlock(request.url.toString(), pageUrls[tabId], request.isForMainFrame) ?: return null
            main.post { trackersBlocked++; pages[tabId]?.let { it.trackersBlocked++ } }
            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Mylo", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageUrls[tabId] = url
            page(tabId).apply {
                trackersBlocked = 0
                loading = true
                error = null
                if (notice !is PageNotice.Info) notice = null
                this.url = url
                loadingUrl = url
                dialogsShown = 0
                dialogsSuppressed = false
                favicon?.let { this.favicon = it }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            pageUrls[tabId] = url
            page(tabId).apply {
                this.url = url
                canGoBack = view.canGoBack()
                canGoForward = view.canGoForward()
            }
            store.updateTab(tabId, url, view.title.orEmpty())
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url != view.url) return
            val page = page(tabId)
            page.loading = false
            page.canGoBack = view.canGoBack()
            page.canGoForward = view.canGoForward()
            page.title = view.title.orEmpty()
            store.updateTab(tabId, url, view.title.orEmpty())
            if (page.error == null) store.recordVisit(url, view.title.orEmpty())
            WebViewRefusals.fromUrl(url)?.let { page.notice = PageNotice.Refused(it.message) }
            if (page.notice !is PageNotice.Refused && WebViewRefusals.hostNeedsCheck(url)) {
                view.evaluateJavascript(PAGE_TEXT) { raw ->
                    val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                    WebViewRefusals.detect(url, text)?.let { page.notice = PageNotice.Refused(it.message) }
                }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            page(tabId).apply { loading = false; this.error = "This page couldn't load (${error.description}). Check your connection and try Reload." }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400) {
                page(tabId).error = "The website returned an error (${response.statusCode}). Try Reload, or another search result."
            }
        }

        /** Certificate errors are never bypassed. */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            val page = page(tabId)
            if (error.url == page.loadingUrl || error.url == view.url) {
                page.loading = false
                page.error = "Mylo stopped loading this page because its security certificate couldn't be verified."
            }
        }

        /** A crashed renderer is contained to its tabs (returning false would end the whole app). */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            live.remove(tabId)?.let { (it.webView.parent as? ViewGroup)?.removeView(it.webView); it.webView.destroy() }
            page(tabId).apply { crashed = true; loading = false }
            if (fullscreen?.tabId == tabId) fullscreen = null
            return true
        }
    }

    private fun openExternal(tabId: Long, pageUrl: String?, target: ExternalTarget) {
        if (!target.needsConfirmation) return launchExternal(tabId, target)
        prompts += ExternalPrompt(tabId, Origins.host(pageUrl), target) { open -> if (open) launchExternal(tabId, target) }
    }

    private fun launchExternal(tabId: Long, target: ExternalTarget) {
        val context = activity ?: return
        when (val outcome = ExternalApps.launch(context, target)) {
            ExternalApps.Outcome.Launched -> Unit
            is ExternalApps.Outcome.LoadInMylo -> load(tabId, outcome.url)
            ExternalApps.Outcome.NoApp -> page(tabId).notice = PageNotice.Info("No app on this device can open this ${target.kind.label} link.")
        }
    }

    // --- windows, permissions, dialogs, files, fullscreen ---------------------------------------------

    private inner class Chrome(private val tabId: Long) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { page(tabId).progress = newProgress }

        override fun onReceivedTitle(view: WebView, title: String?) {
            page(tabId).title = title.orEmpty()
            view.url?.let { store.updateTab(tabId, it, title.orEmpty()) }
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) { page(tabId).favicon = icon }

        /**
         * target=_blank, window.open() and sign-in pop-ups open as a new Mylo tab linked to this one, which
         * stays alive so the pop-up can message it and close back to it. Without a tap, pop-ups are blocked
         * (as in Chrome) unless the user allowed pop-ups for the site.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val origin = Origins.of(view.url)
            if (!isUserGesture && permissions.decision(origin, SitePermission.Popups) != SiteDecision.Allow) {
                page(tabId).notice = PageNotice.PopupBlocked(origin, Origins.host(view.url))
                return false
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val tab = store.createChildTab(tabId)
            val child = create(tab.id, tabId)
            transport.webView = child.webView
            resultMsg.sendToTarget()
            _events.tryEmit(EngineEvent.ShowTab(tab.id))
            trim(keep = tab.id)
            return true
        }

        /** window.close(): the pop-up's tab closes and its opener comes back. */
        override fun onCloseWindow(window: WebView) {
            val closing = tabIdOf(window) ?: return
            val opener = live[closing]?.openerId
            val wasVisible = visibleTab == closing
            closeTab(closing)
            if (wasVisible) _events.tryEmit(if (opener != null && live.containsKey(opener)) EngineEvent.ShowTab(opener) else EngineEvent.ShowHome)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = request.resources.mapNotNull {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SitePermission.Camera
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SitePermission.Microphone
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> SitePermission.ProtectedMedia
                    else -> null
                }
            }.distinct()
            val resources = request.resources.filter {
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                    it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
            }.toTypedArray()
            if (wanted.isEmpty() || tabId != visibleTab) return request.deny()
            val origin = Origins.of(request.origin.toString())
            val decisions = wanted.map { permissions.decision(origin, it) }
            if (SiteDecision.Block in decisions) return request.deny()
            prompts += PermissionPrompt(tabId, origin, Origins.host(request.origin.toString()), wanted,
                preApproved = decisions.all { it == SiteDecision.Allow }, key = request, store = permissions,
                onGrant = { request.grant(resources) }, onDeny = { request.deny() })
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            prompts.removeAll { it is PermissionPrompt && it.key === request }
        }

        override fun onGeolocationPermissionsShowPrompt(originUrl: String, callback: GeolocationPermissions.Callback) {
            val origin = Origins.of(originUrl)
            val decision = permissions.decision(origin, SitePermission.Location)
            if (tabId != visibleTab || decision == SiteDecision.Block) return callback.invoke(originUrl, false, false)
            prompts += PermissionPrompt(tabId, origin, Origins.host(originUrl), listOf(SitePermission.Location),
                preApproved = decision == SiteDecision.Allow, key = callback, store = permissions,
                onGrant = { callback.invoke(originUrl, true, false) }, onDeny = { callback.invoke(originUrl, false, false) })
        }

        override fun onGeolocationPermissionsHidePrompt() {
            prompts.removeAll { it is PermissionPrompt && it.tabId == tabId && SitePermission.Location in it.permissions }
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
            finishFileChooser(null)
            if (tabId != visibleTab) { filePathCallback.onReceiveValue(null); return true }
            fileChooser = FileChooserRequest(tabId, fileChooserParams, filePathCallback)
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult) =
            jsDialog(tabId, url, JsDialogKind.Alert, message, null, result)

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult) =
            jsDialog(tabId, url, JsDialogKind.Confirm, message, null, result)

        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult) =
            jsDialog(tabId, url, JsDialogKind.Prompt, message, defaultValue, result)

        override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult) =
            jsDialog(tabId, url, JsDialogKind.BeforeUnload, message, null, result)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            exitFullscreen()
            fullscreen = FullscreenRequest(tabId, view, callback)
        }

        override fun onHideCustomView() {
            if (fullscreen?.tabId == tabId) fullscreen = null
        }

        /** Page console output stays out of the system log: it can contain page data. */
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage) = true

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun jsDialog(tabId: Long, url: String, kind: JsDialogKind, message: String, defaultValue: String?, result: JsResult): Boolean {
        val page = page(tabId)
        // Background tabs and pages the user silenced can't show dialogs.
        if (tabId != visibleTab || page.dialogsSuppressed) { result.cancel(); return true }
        page.dialogsShown++
        prompts += JsDialogPrompt(tabId, Origins.host(url), kind, message.take(2_000), defaultValue, offerSuppress = page.dialogsShown > 1) { ok, text, suppress ->
            if (suppress) page.dialogsSuppressed = true
            when {
                !ok -> result.cancel()
                result is JsPromptResult -> result.confirm(text.orEmpty())
                else -> result.confirm()
            }
        }
        return true
    }

    // --- downloads ------------------------------------------------------------------------------------

    private inner class Downloads(private val tabId: Long) : DownloadListener {
        override fun onDownloadStart(url: String, userAgent: String, contentDisposition: String?, mimetype: String?, contentLength: Long) {
            when (url.substringBefore(':').lowercase()) {
                "http", "https" -> {
                    val name = DownloadNames.sanitize(URLUtil.guessFileName(url, contentDisposition, mimetype))
                    val referer = live[tabId]?.webView?.url
                    prompts += DownloadPrompt(tabId, name, contentLength, Origins.host(url), Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { ok ->
                        if (ok) enqueue(tabId, url, userAgent, mimetype, name, referer)
                    }
                }
                "data" -> {
                    val name = DownloadNames.sanitize(URLUtil.guessFileName(url, contentDisposition, mimetype))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        page(tabId).notice = PageNotice.Info("Saving files the page creates needs Android 10 or later.", offerOtherBrowser = true)
                    } else {
                        prompts += DownloadPrompt(tabId, name, -1, Origins.host(live[tabId]?.webView?.url), false) { ok -> if (ok) saveDataUrl(tabId, url, name) }
                    }
                }
                else -> page(tabId).notice = PageNotice.Info("This file was created by the page in a way Mylo can't save yet.", offerOtherBrowser = true)
            }
        }
    }

    private fun enqueue(tabId: Long, url: String, userAgent: String, mimetype: String?, name: String, referer: String?) {
        val manager = app.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .addRequestHeader("User-Agent", userAgent)
        mimetype?.takeIf { it.isNotBlank() }?.let(request::setMimeType)
        // The download belongs to the signed-in session that started it.
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        referer?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { request.addRequestHeader("Referer", it) }
        val message = runCatching { manager.enqueue(request); "Downloading $name. Find it in Downloads." }
            .getOrElse { "Mylo couldn't start this download." }
        page(tabId).notice = PageNotice.Info(message)
    }

    private fun saveDataUrl(tabId: Long, url: String, name: String) {
        val message = runCatching {
            val header = url.substringBefore(',')
            val payload = url.substringAfter(',', "")
            val bytes = if (header.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
            require(bytes.size <= MAX_DATA_DOWNLOAD) { "too large" }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" })
            }
            val resolver = app.contentResolver
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no target")
            resolver.openOutputStream(target)!!.use { it.write(bytes) }
            "Saved $name to Downloads."
        }.getOrElse { "Mylo couldn't save this file." }
        page(tabId).notice = PageNotice.Info(message)
    }

    companion object {
        const val MAX_LIVE = 6
        private const val MAX_DATA_DOWNLOAD = 50 * 1024 * 1024
        /** Read only on hosts known to refuse WebView, to recognise their refusal page. */
        private const val PAGE_TEXT = "(function(){return (document.title||'')+'\\n'+((document.body&&document.body.innerText)||'').slice(0,4000)})()"
    }
}

fun Context.isGranted(permission: String) =
    checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

package com.mylo.browser.privacy

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mylo.browser.BrowserTab
import com.mylo.browser.web.EngineMode
import com.mylo.browser.web.SessionValues
import com.mylo.browser.web.TabEngine
import com.mylo.browser.web.TabHost
import com.mylo.browser.web.TrackerBlocker

/** Private Mode's switches. Preferences only (no browsing data), read and written in the private process. */
class PrivateSettings(context: Context) {
    private val prefs = context.getSharedPreferences("mylo_private_settings", Context.MODE_PRIVATE)
    var blockTrackers by mutableStateOf(prefs.getBoolean(BLOCK, true)); private set
    var lockTabs by mutableStateOf(prefs.getBoolean(LOCK, false)); private set
    var burnOnExit by mutableStateOf(prefs.getBoolean(BURN, true)); private set

    fun updateBlockTrackers(on: Boolean) { blockTrackers = on; prefs.edit().putBoolean(BLOCK, on).apply() }
    fun updateLockTabs(on: Boolean) { lockTabs = on; prefs.edit().putBoolean(LOCK, on).apply() }
    fun updateBurnOnExit(on: Boolean) { burnOnExit = on; prefs.edit().putBoolean(BURN, on).apply() }

    private companion object {
        const val BLOCK = "block_trackers"
        const val LOCK = "lock_tabs"
        const val BURN = "burn_on_exit"
    }
}

/** Private tabs: kept in memory only, never in normal storage. Main thread. */
class PrivateTabs : TabHost {
    private var nextId = 1L
    val tabs = mutableStateListOf<BrowserTab>()
    /** Pages finished loading this session. A count only; no addresses are kept for it. */
    var visits by mutableIntStateOf(0)
        private set

    fun create(url: String = ""): BrowserTab = BrowserTab(nextId++, "Private tab", url, privateMode = true).also { tabs.add(it) }

    override fun createChildTab(openerId: Long): BrowserTab {
        val tab = BrowserTab(nextId++, "New window", "", privateMode = true, openerId = openerId)
        val index = tabs.indexOfFirst { it.id == openerId }
        if (index < 0) tabs.add(tab) else tabs.add(index + 1, tab)
        return tab
    }

    override fun updateTab(id: Long, url: String, title: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0 || !(url.startsWith("https://") || url.startsWith("http://"))) return
        tabs[index] = tabs[index].copy(url = url, title = title.trim().ifEmpty { url })
    }

    override fun closeTab(id: Long) { tabs.removeAll { it.id == id } }

    /** Never written to Mylo history; only counted for the "No history saved" page. */
    override fun recordVisit(url: String, title: String) { visits++ }

    fun clear() { tabs.clear(); visits = 0 }
}

/** Something another Mylo feature keeps for a private session (AI conversation, captures), destroyed on burn. */
interface Burnable {
    val label: String
    fun burn()
}

/** What a burn cleared, for the confirmation shown to the user. */
data class BurnReport(val tabs: Int, val trackersForgotten: Int, val extras: List<String>)

/**
 * One private session, living in Mylo's separate private process with its own WebView data directory (see
 * [PrivateDataJanitor]). It runs the shared [TabEngine] in [EngineMode.Private] with tracker blocking and
 * session-only permissions. [burn] destroys the session's tabs, cookies, site storage, cache, permissions,
 * tracker log and everything registered as [Burnable].
 */
class PrivateSession(private val app: Application) {
    val settings = PrivateSettings(app)
    val tabs = PrivateTabs()
    private val trackerBlocker = TrackerBlocker().also { it.enabled = settings.blockTrackers }
    private val permissions = SessionValues()
    val engine = TabEngine(app, tabs, EngineMode.Private, trackerBlocker, permissions)
    private val burnables = mutableListOf<Burnable>()
    /** Increments on every burn, so screens can react. */
    var burns by mutableIntStateOf(0)
        private set

    fun register(burnable: Burnable) { if (burnable !in burnables) burnables += burnable }
    fun unregister(burnable: Burnable) { burnables -= burnable }

    fun setBlockTrackers(on: Boolean) {
        settings.updateBlockTrackers(on)
        trackerBlocker.enabled = on
    }

    /** The most-blocked tracker domains this session, with their category. */
    fun blockedSummary() = trackerBlocker.summary().map { (domain, count) -> Triple(domain, count, trackerBlocker.categoryOf(domain)) }

    /** Destroys everything this session holds. Main thread. */
    fun burn(): BurnReport {
        val report = BurnReport(tabs.tabs.size, engine.trackersBlocked, burnables.map { it.label })
        engine.burn()
        tabs.clear()
        burnables.toList().forEach { runCatching { it.burn() } }
        permissions.clear()
        clearWebData(app)
        burns++
        return report
    }

    companion object {
        /** Cookies, site storage (local storage, IndexedDB, caches, service workers), HTTP cache, auth and location grants. */
        fun clearWebData(context: Context) {
            CookieManager.getInstance().apply { removeAllCookies(null); removeSessionCookies(null); flush() }
            WebStorage.getInstance().deleteAllData()
            GeolocationPermissions.getInstance().clearAll()
            runCatching { WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword() }
            // The HTTP cache is per data directory and needs a WebView to clear.
            runCatching { WebView(context).apply { clearCache(true); clearHistory(); destroy() } }
        }
    }
}
